package com.slotmind.service;

import com.slotmind.entity.Provider;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.enums.TriggerReason;
import com.slotmind.event.WorkflowEvent;
import com.slotmind.repository.ProviderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderServiceTest {

    @Mock
    private ProviderRepository providers;

    @Mock
    private ApplicationEventPublisher events;

    @Test
    void repeatedOfflineStatusDoesNotSaveOrPublishAnotherWorkflowEvent() {
        Provider provider = provider(ProviderStatus.OFFLINE);
        when(providers.lockById("PRV-001")).thenReturn(Optional.of(provider));

        Provider result = service().status("PRV-001", ProviderStatus.OFFLINE);

        assertSame(provider, result);
        verify(providers, never()).save(any(Provider.class));
        verify(events, never()).publishEvent(any(WorkflowEvent.class));
    }

    @Test
    void transitionToOfflinePublishesOneWorkflowEvent() {
        Provider provider = provider(ProviderStatus.AVAILABLE);
        when(providers.lockById("PRV-001")).thenReturn(Optional.of(provider));
        when(providers.save(provider)).thenReturn(provider);

        Provider result = service().status("PRV-001", ProviderStatus.OFFLINE);

        assertEquals(ProviderStatus.OFFLINE, result.getStatus());
        verify(providers).save(provider);
        verify(events).publishEvent(
                new WorkflowEvent(TriggerReason.PROVIDER_OFFLINE, "PRV-001"));
    }

    private ProviderService service() {
        return new ProviderService(providers, events);
    }

    private Provider provider(ProviderStatus status) {
        return new Provider("PRV-001", "Test provider", Specialty.GENERAL, status, 0);
    }
}
