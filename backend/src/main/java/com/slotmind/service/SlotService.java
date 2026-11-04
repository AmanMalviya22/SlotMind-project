package com.slotmind.service;

import com.slotmind.dto.CreateSlot;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.event.WorkflowEvent;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.exception.ApiExceptions.NotFound;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.repository.SlotRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SlotService {

    private final SlotRepository repo;
    private final ProviderRepository providers;
    private final ApplicationEventPublisher events;

    public SlotService(
            SlotRepository repo,
            ProviderRepository providers,
            ApplicationEventPublisher events) {
        this.repo = repo;
        this.providers = providers;
        this.events = events;
    }

    @Transactional
    public Slot create(CreateSlot request) {
        if (request.startTime() == null || !request.startTime().isAfter(Instant.now())) {
            throw new Conflict("A replacement slot must start in the future");
        }
        if (request.durationMinutes() <= 0 || request.durationMinutes() > 480) {
            throw new IllegalArgumentException("Slot duration must be between 1 and 480 minutes");
        }

        Provider provider = providers.lockById(request.providerId())
                .orElseThrow(() -> new NotFound("Provider not found"));
        if (provider.getStatus() == ProviderStatus.OFFLINE) {
            throw new Conflict("Cannot create a slot for an offline provider");
        }
        if (repo.existsByProviderAndStartTime(provider, request.startTime())) {
            throw new Conflict("A slot already exists for this provider at that start time");
        }

        Slot slot = new Slot(
                "SLT-" + UUID.randomUUID(),
                provider,
                request.startTime(),
                request.durationMinutes(),
                SlotStatus.OPEN,
                0);
        return repo.save(slot);
    }

    public List<Slot> list() {
        return repo.findAll();
    }

    @Transactional
    public Slot status(String id, SlotStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("Slot status is required");
        }

        Slot slot = repo.lockById(id)
                .orElseThrow(() -> new NotFound("Slot not found"));

        // Make retries idempotent and avoid publishing duplicate cancellation events.
        if (slot.getStatus() == status) {
            return slot;
        }
        if (slot.getStatus() == SlotStatus.CANCELLED) {
            throw new Conflict("Cancelled slot cannot change status");
        }
        if (status == SlotStatus.OPEN && slot.getStatus() != SlotStatus.OPEN) {
            throw new Conflict("Only the booking workflow can release a booked slot");
        }
        if (status == SlotStatus.BOOKED) {
            throw new Conflict("Only the booking workflow can book a slot");
        }

        slot.setStatus(status);
        Slot saved = repo.save(slot);
        if (status == SlotStatus.CANCELLED) {
            events.publishEvent(new WorkflowEvent(TriggerReason.SLOT_CANCELLED, saved.getId()));
        }
        return saved;
    }
}
