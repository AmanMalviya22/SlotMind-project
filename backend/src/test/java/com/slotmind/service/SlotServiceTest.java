package com.slotmind.service;

import com.slotmind.dto.CreateSlot;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.enums.TriggerReason;
import com.slotmind.event.WorkflowEvent;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.exception.ApiExceptions.NotFound;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    @Mock
    private SlotRepository slots;

    @Mock
    private ProviderRepository providers;

    @Mock
    private ApplicationEventPublisher events;

    @Test
    void listsAllSlotsFromRepository() {
        List<Slot> expected = List.of(slot(SlotStatus.OPEN));

        when(slots.findAll()).thenReturn(expected);

        assertSame(expected, service().list());
        verify(slots).findAll();
    }

    @Test
    void throwsNotFoundWhenSlotDoesNotExist() {
        when(slots.lockById("S1")).thenReturn(Optional.empty());

        assertThrows(NotFound.class, () -> service().status("S1", SlotStatus.CANCELLED));

        verify(slots, never()).save(any(Slot.class));
        verify(events, never()).publishEvent(any(WorkflowEvent.class));
    }

    @Test
    void rejectsNullStatusBeforeAccessingRepository() {
        assertEquals(
                "Slot status is required",
                assertThrows(IllegalArgumentException.class,
                        () -> service().status("S1", null)).getMessage());

        verify(slots, never()).lockById("S1");
    }

    @Test
    void repeatedOpenStatusIsIdempotent() {
        Slot open = slot(SlotStatus.OPEN);
        when(slots.lockById("S1")).thenReturn(Optional.of(open));

        assertSame(open, service().status("S1", SlotStatus.OPEN));

        verify(slots, never()).save(any(Slot.class));
        verify(events, never()).publishEvent(any(WorkflowEvent.class));
    }

    @Test
    void cancellationSavesSlotAndPublishesOneWorkflowEvent() {
        Slot open = slot(SlotStatus.OPEN);
        when(slots.lockById("S1")).thenReturn(Optional.of(open));
        when(slots.save(open)).thenReturn(open);

        Slot result = service().status("S1", SlotStatus.CANCELLED);

        assertSame(open, result);
        assertEquals(SlotStatus.CANCELLED, result.getStatus());
        verify(slots).save(open);
        verify(events).publishEvent(
                new WorkflowEvent(TriggerReason.SLOT_CANCELLED, "S1"));
    }

    @Test
    void repeatedCancelledStatusIsIdempotentAndDoesNotPublishAgain() {
        Slot cancelled = slot(SlotStatus.CANCELLED);
        when(slots.lockById("S1")).thenReturn(Optional.of(cancelled));

        assertSame(cancelled, service().status("S1", SlotStatus.CANCELLED));

        verify(slots, never()).save(any(Slot.class));
        verify(events, never()).publishEvent(any(WorkflowEvent.class));
    }

    @Test
    void cancelledSlotCannotChangeToAnotherStatus() {
        Slot cancelled = slot(SlotStatus.CANCELLED);
        when(slots.lockById("S1")).thenReturn(Optional.of(cancelled));

        assertThrows(Conflict.class, () -> service().status("S1", SlotStatus.OPEN));

        verify(slots, never()).save(any(Slot.class));
    }

    @Test
    void onlyBookingWorkflowCanReopenBookedSlot() {
        Slot booked = slot(SlotStatus.BOOKED);
        when(slots.lockById("S1")).thenReturn(Optional.of(booked));

        assertThrows(Conflict.class, () -> service().status("S1", SlotStatus.OPEN));

        verify(slots, never()).save(any(Slot.class));
    }

    @Test
    void onlyBookingWorkflowCanBookOpenSlot() {
        Slot open = slot(SlotStatus.OPEN);
        when(slots.lockById("S1")).thenReturn(Optional.of(open));

        assertThrows(Conflict.class, () -> service().status("S1", SlotStatus.BOOKED));

        verify(slots, never()).save(any(Slot.class));
    }

    @Test
    void createsFutureReplacementSlotForAnAvailableProvider() {
        Provider provider = slot(SlotStatus.OPEN).getProvider();
        Instant startTime = Instant.now().plusSeconds(7200);
        when(providers.lockById("P1")).thenReturn(Optional.of(provider));
        when(slots.existsByProviderAndStartTime(provider, startTime)).thenReturn(false);
        when(slots.save(any(Slot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Slot created = service().create(new CreateSlot("P1", startTime, 45));

        assertEquals("P1", created.getProvider().getId());
        assertEquals(startTime, created.getStartTime());
        assertEquals(45, created.getDurationMinutes());
        assertEquals(SlotStatus.OPEN, created.getStatus());
        verify(slots).save(created);
    }

    @Test
    void rejectsReplacementSlotForAnOfflineProvider() {
        Provider provider = new Provider("P1", "Provider", Specialty.GENERAL, ProviderStatus.OFFLINE, 0);
        Instant startTime = Instant.now().plusSeconds(7200);
        when(providers.lockById("P1")).thenReturn(Optional.of(provider));

        assertThrows(Conflict.class,
                () -> service().create(new CreateSlot("P1", startTime, 30)));

        verify(slots, never()).save(any(Slot.class));
    }

    @Test
    void rejectsDuplicateProviderStartTime() {
        Provider provider = slot(SlotStatus.OPEN).getProvider();
        Instant startTime = Instant.now().plusSeconds(7200);
        when(providers.lockById("P1")).thenReturn(Optional.of(provider));
        when(slots.existsByProviderAndStartTime(provider, startTime)).thenReturn(true);

        assertThrows(Conflict.class,
                () -> service().create(new CreateSlot("P1", startTime, 30)));

        verify(slots, never()).save(any(Slot.class));
    }

    @Test
    void rejectsReplacementSlotInThePast() {
        assertThrows(Conflict.class,
                () -> service().create(new CreateSlot("P1", Instant.now().minusSeconds(60), 30)));

        verify(providers, never()).lockById("P1");
        verify(slots, never()).save(any(Slot.class));
    }

    @Test
    void rejectsReplacementSlotWithInvalidDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> service().create(new CreateSlot(
                        "P1", Instant.now().plusSeconds(3600), 0)));
        verify(providers, never()).lockById("P1");
        verify(slots, never()).save(any(Slot.class));
    }

    @Test
    void rejectsUnknownProviderWhenCreatingReplacementSlot() {
        when(providers.lockById("MISSING")).thenReturn(Optional.empty());

        assertThrows(NotFound.class,
                () -> service().create(new CreateSlot(
                        "MISSING", Instant.now().plusSeconds(3600), 30)));

        verify(slots, never()).save(any(Slot.class));
    }

    private SlotService service() {
        return new SlotService(slots, providers, events);
    }

    private Slot slot(SlotStatus status) {
        Provider provider = new Provider(
                "P1", "Provider", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        return new Slot(
                "S1", provider, Instant.parse("2099-11-04T09:00:00Z"), 30, status, 0);
    }
}
