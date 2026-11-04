package com.slotmind.controller;

import com.slotmind.dto.ProviderView;
import com.slotmind.dto.StatusRequest;
import com.slotmind.entity.Provider;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.service.MappingService;
import com.slotmind.service.ProviderService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderControllerTest {

    @Test
    void listsProvidersAsResponseDtos() {
        ProviderService service = mock(ProviderService.class);
        MappingService mapper = mock(MappingService.class);
        Provider provider = provider(ProviderStatus.AVAILABLE);
        ProviderView view = new ProviderView(
                "P-1", "Dr. Rao", Specialty.GENERAL, ProviderStatus.AVAILABLE);
        when(service.list()).thenReturn(List.of(provider));
        when(mapper.provider(provider)).thenReturn(view);

        ProviderController controller = new ProviderController(service, mapper);

        assertEquals(List.of(view), controller.providers());
        verify(service).list();
        verify(mapper).provider(provider);
    }

    @Test
    void parsesCaseInsensitiveStatusAndMapsUpdatedProvider() {
        ProviderService service = mock(ProviderService.class);
        MappingService mapper = mock(MappingService.class);
        Provider updated = provider(ProviderStatus.OFFLINE);
        ProviderView view = new ProviderView(
                "P-1", "Dr. Rao", Specialty.GENERAL, ProviderStatus.OFFLINE);
        when(service.status("P-1", ProviderStatus.OFFLINE)).thenReturn(updated);
        when(mapper.provider(updated)).thenReturn(view);

        ProviderController controller = new ProviderController(service, mapper);

        assertEquals(view, controller.providerStatus("P-1", new StatusRequest(" offline ")));
        verify(service).status("P-1", ProviderStatus.OFFLINE);
        verify(mapper).provider(updated);
    }

    @Test
    void rejectsUnknownProviderStatusWithoutCallingService() {
        ProviderService service = mock(ProviderService.class);
        ProviderController controller = new ProviderController(service, mock(MappingService.class));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> controller.providerStatus("P-1", new StatusRequest("CLOSED")));

        assertEquals("Status must be AVAILABLE, BUSY, or OFFLINE", error.getMessage());
        verify(service, never()).status(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsNullProviderStatusWithoutCallingService() {
        ProviderService service = mock(ProviderService.class);
        ProviderController controller = new ProviderController(service, mock(MappingService.class));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> controller.providerStatus("P-1", new StatusRequest(null)));

        assertEquals("Status must be AVAILABLE, BUSY, or OFFLINE", error.getMessage());
        verify(service, never()).status(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private Provider provider(ProviderStatus status) {
        return new Provider("P-1", "Dr. Rao", Specialty.GENERAL, status, 0);
    }
}
