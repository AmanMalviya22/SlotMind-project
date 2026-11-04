package com.slotmind.service;

import com.slotmind.entity.Provider;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.event.WorkflowEvent;
import com.slotmind.exception.ApiExceptions.NotFound;
import com.slotmind.repository.ProviderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProviderService {

    private final ProviderRepository repo;
    private final ApplicationEventPublisher events;

    public ProviderService(ProviderRepository repo, ApplicationEventPublisher events) {
        this.repo = repo;
        this.events = events;
    }

    public List<Provider> list() {
        return repo.findAll();
    }

    @Transactional
    public Provider status(String id, ProviderStatus status) {
        Provider provider = repo.lockById(id)
                .orElseThrow(() -> new NotFound("Provider not found"));

        // Repeated status requests should be idempotent and must not launch duplicate workflows.
        if (provider.getStatus() == status) {
            return provider;
        }

        provider.setStatus(status);
        Provider saved = repo.save(provider);

        if (status == ProviderStatus.OFFLINE) {
            events.publishEvent(new WorkflowEvent(TriggerReason.PROVIDER_OFFLINE, saved.getId()));
        }

        return saved;
    }
}
