package com.slotmind.controller;

import com.slotmind.dto.CreateSlot;
import com.slotmind.dto.SlotView;
import com.slotmind.dto.StatusRequest;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.service.MappingService;
import com.slotmind.service.SlotService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotControllerTest {

    @Test
    void createsReplacementSlotAndMapsItsResponse() {
        SlotService service = mock(SlotService.class);
        MappingService mapper = mock(MappingService.class);
        CreateSlot request = new CreateSlot(
                "P-1", Instant.parse("2099-11-04T09:00:00Z"), 45);
        Slot created = slot(SlotStatus.OPEN);
        SlotView view = view(SlotStatus.OPEN);
        when(service.create(request)).thenReturn(created);
        when(mapper.slot(created)).thenReturn(view);

        SlotController controller = new SlotController(service, mapper);

        assertEquals(view, controller.create(request));
        verify(service).create(request);
        verify(mapper).slot(created);
    }

    @Test
    void listsSlotsAsResponseDtos() {
        SlotService service = mock(SlotService.class);
        MappingService mapper = mock(MappingService.class);
        Slot slot = slot(SlotStatus.OPEN);
        SlotView view = view(SlotStatus.OPEN);
        when(service.list()).thenReturn(List.of(slot));
        when(mapper.slot(slot)).thenReturn(view);

        SlotController controller = new SlotController(service, mapper);

        assertEquals(List.of(view), controller.slots());
        verify(service).list();
        verify(mapper).slot(slot);
    }

    @Test
    void parsesCaseInsensitiveStatusAndMapsUpdatedSlot() {
        SlotService service = mock(SlotService.class);
        MappingService mapper = mock(MappingService.class);
        Slot updated = slot(SlotStatus.CANCELLED);
        SlotView view = view(SlotStatus.CANCELLED);
        when(service.status("S-1", SlotStatus.CANCELLED)).thenReturn(updated);
        when(mapper.slot(updated)).thenReturn(view);

        SlotController controller = new SlotController(service, mapper);

        assertEquals(view, controller.slotStatus("S-1", new StatusRequest(" cancelled ")));
        verify(service).status("S-1", SlotStatus.CANCELLED);
        verify(mapper).slot(updated);
    }

    @Test
    void rejectsUnknownSlotStatusWithoutCallingService() {
        SlotService service = mock(SlotService.class);
        SlotController controller = new SlotController(service, mock(MappingService.class));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> controller.slotStatus("S-1", new StatusRequest("HELD")));

        assertEquals("Status must be OPEN, BOOKED, or CANCELLED", error.getMessage());
        verify(service, never()).status(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsNullSlotStatusWithoutCallingService() {
        SlotService service = mock(SlotService.class);
        SlotController controller = new SlotController(service, mock(MappingService.class));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> controller.slotStatus("S-1", new StatusRequest(null)));

        assertEquals("Status must be OPEN, BOOKED, or CANCELLED", error.getMessage());
        verify(service, never()).status(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private Slot slot(SlotStatus status) {
        Provider provider = new Provider(
                "P-1", "Dr. Rao", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        return new Slot(
                "S-1", provider, Instant.parse("2099-11-04T09:00:00Z"), 30, status, 0);
    }

    private SlotView view(SlotStatus status) {
        return new SlotView(
                "S-1", "P-1", "Dr. Rao",
                Instant.parse("2099-11-04T09:00:00Z"), 30, status);
    }
}
