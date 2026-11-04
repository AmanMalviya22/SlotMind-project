package com.slotmind.service;

import com.slotmind.dto.CreateAppointment;
import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.exception.ApiExceptions.NotFound;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.repository.SlotRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class AppointmentServiceTest {

    @Mock
    private AppointmentRepository appointments;

    @Mock
    private ProviderRepository providers;

    @Mock
    private SlotRepository slots;

    @Mock
    private EntityManager entityManager;

    @Test
    void booksOpenFutureSlotAndCreatesConfirmedAppointment() {
        Provider provider = provider("P1", ProviderStatus.AVAILABLE);
        Slot slot = slot("S1", provider, Instant.now().plusSeconds(3600), SlotStatus.OPEN);
        stubBookingRecords(slot, provider);
        when(appointments.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Appointment created = service().create(request("P1", "S1"));

        assertEquals(SlotStatus.BOOKED, slot.getStatus());
        assertEquals(AppointmentStatus.CONFIRMED, created.getStatus());
        assertEquals(AppointmentType.ROUTINE, created.getType());
        assertEquals("Patient", created.getPatientName());
        assertEquals("P1", created.getProvider().getId());
        assertEquals("S1", created.getSlot().getId());
        verify(entityManager).refresh(provider, LockModeType.PESSIMISTIC_WRITE);
        verify(appointments).save(created);
    }

    @Test
    void trimsPatientNameBeforePersistingTheAppointment() {
        Provider provider = provider("P1", ProviderStatus.AVAILABLE);
        Slot slot = slot("S1", provider, Instant.now().plusSeconds(3600), SlotStatus.OPEN);
        stubBookingRecords(slot, provider);
        when(appointments.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        CreateAppointment request = new CreateAppointment(
                "  Patient Name  ", AppointmentType.ROUTINE, "P1", "S1");

        Appointment created = service().create(request);

        assertEquals("Patient Name", created.getPatientName());
    }

    @Test
    void rejectsSlotThatIsAlreadyInThePast() {
        Provider provider = provider("P1", ProviderStatus.AVAILABLE);
        Slot pastSlot = slot("S1", provider, Instant.now().minusSeconds(60), SlotStatus.OPEN);
        stubBookingRecords(pastSlot, provider);

        assertThrows(Conflict.class, () -> service().create(request("P1", "S1")));

        assertEquals(SlotStatus.OPEN, pastSlot.getStatus());
        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void rejectsSlotWithMissingStartTime() {
        Provider provider = provider("P1", ProviderStatus.AVAILABLE);
        Slot slot = slot("S1", provider, null, SlotStatus.OPEN);
        stubBookingRecords(slot, provider);

        assertEquals("Slot must be in the future",
                assertThrows(Conflict.class, () -> service().create(request("P1", "S1"))).getMessage());

        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void rejectsSlotThatIsNotOpen() {
        Provider provider = provider("P1", ProviderStatus.AVAILABLE);
        Slot booked = slot("S1", provider, Instant.now().plusSeconds(3600), SlotStatus.BOOKED);
        stubBookingRecords(booked, provider);

        assertEquals("Slot is not open",
                assertThrows(Conflict.class, () -> service().create(request("P1", "S1"))).getMessage());

        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void rejectsSlotOwnedByAnotherProvider() {
        Provider selectedProvider = provider("P1", ProviderStatus.AVAILABLE);
        Provider slotProvider = provider("P2", ProviderStatus.AVAILABLE);
        Slot slot = slot("S1", slotProvider, Instant.now().plusSeconds(3600), SlotStatus.OPEN);
        stubBookingRecords(slot, selectedProvider);

        assertEquals("Slot does not belong to provider",
                assertThrows(Conflict.class, () -> service().create(request("P1", "S1"))).getMessage());

        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void rejectsBookingWithOfflineProvider() {
        Provider provider = provider("P1", ProviderStatus.OFFLINE);
        Slot slot = slot("S1", provider, Instant.now().plusSeconds(3600), SlotStatus.OPEN);
        stubBookingRecords(slot, provider);

        assertEquals("Provider is offline",
                assertThrows(Conflict.class, () -> service().create(request("P1", "S1"))).getMessage());

        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void throwsNotFoundWhenSlotDoesNotExist() {
        when(slots.lockById("S1")).thenReturn(Optional.empty());

        assertThrows(NotFound.class, () -> service().create(request("P1", "S1")));

        verify(providers, never()).lockById("P1");
        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void throwsNotFoundWhenProviderDoesNotExist() {
        Provider provider = provider("P1", ProviderStatus.AVAILABLE);
        Slot slot = slot("S1", provider, Instant.now().plusSeconds(3600), SlotStatus.OPEN);
        when(slots.lockById("S1")).thenReturn(Optional.of(slot));
        when(providers.lockById("P1")).thenReturn(Optional.empty());

        assertThrows(NotFound.class, () -> service().create(request("P1", "S1")));

        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void findsAppointmentById() {
        Appointment expected = org.mockito.Mockito.mock(Appointment.class);
        when(appointments.findById("A1")).thenReturn(Optional.of(expected));

        assertSame(expected, service().findById("A1"));
    }

    @Test
    void throwsNotFoundWhenAppointmentCannotBeFound() {
        when(appointments.findById("missing")).thenReturn(Optional.empty());

        assertEquals("Appointment not found",
                assertThrows(NotFound.class, () -> service().findById("missing")).getMessage());
    }

    @Test
    void listsAllAppointmentsWhenNoStatusFilterIsSupplied() {
        List<Appointment> expected = List.of(org.mockito.Mockito.mock(Appointment.class));
        when(appointments.findAll()).thenReturn(expected);

        assertSame(expected, service().list(null));
        verify(appointments).findAll();
    }

    @Test
    void listsAppointmentsByStatusWhenFilterIsSupplied() {
        List<Appointment> expected = List.of(org.mockito.Mockito.mock(Appointment.class));
        when(appointments.findByStatus(AppointmentStatus.CONFIRMED)).thenReturn(expected);

        assertSame(expected, service().list(AppointmentStatus.CONFIRMED));
        verify(appointments).findByStatus(AppointmentStatus.CONFIRMED);
        verify(appointments, never()).findAll();
    }

    private void stubBookingRecords(Slot slot, Provider provider) {
        when(slots.lockById("S1")).thenReturn(Optional.of(slot));
        when(providers.lockById("P1")).thenReturn(Optional.of(provider));
    }

    private AppointmentService service() {
        return new AppointmentService(appointments, providers, slots, entityManager);
    }

    private CreateAppointment request(String providerId, String slotId) {
        return new CreateAppointment("Patient", AppointmentType.ROUTINE, providerId, slotId);
    }

    private Provider provider(String id, ProviderStatus status) {
        return new Provider(id, "Provider " + id, Specialty.GENERAL, status, 0);
    }

    private Slot slot(String id, Provider provider, Instant start, SlotStatus status) {
        return new Slot(id, provider, start, 30, status, 0);
    }
}
