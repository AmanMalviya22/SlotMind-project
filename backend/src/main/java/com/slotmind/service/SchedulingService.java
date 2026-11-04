package com.slotmind.service;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.exception.ApiExceptions.NotFound;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.repository.SuggestionRepository;
import com.slotmind.strategy.SchedulingStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SchedulingService {
    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);
    private final SlotRepository slots;
    private final SuggestionRepository suggestions;
    private final SchedulingStrategy strategy;
    private final AppointmentRepository appointments;
    private final EntityManager entityManager;

    public SchedulingService(
            SlotRepository slots,
            SuggestionRepository suggestions,
            SchedulingStrategy strategy,
            AppointmentRepository appointments,
            EntityManager entityManager) {
        this.slots = slots;
        this.suggestions = suggestions;
        this.strategy = strategy;
        this.appointments = appointments;
        this.entityManager = entityManager;
    }

    @Transactional
    public Optional<RescheduleSuggestion> generate(Appointment appointment, TriggerReason reason) {
        Appointment lockedAppointment = appointments.lockById(appointment.getId())
                .orElseThrow(() -> new NotFound("Appointment not found"));

        if (isFinalized(lockedAppointment.getStatus())) {
            return Optional.empty();
        }

        if (suggestions.existsByAppointmentAndTriggerReasonAndStatus(
                lockedAppointment, reason, SuggestionStatus.PENDING)) {
            return Optional.empty();
        }

        Set<String> proposedSlotIds = suggestions
                .findByStatusOrderByCreatedAtDesc(SuggestionStatus.PENDING)
                .stream()
                .map(suggestion -> suggestion.getRecommendedSlot().getId())
                .collect(Collectors.toSet());

        List<Slot> candidates = slots
                .findByStatusAndStartTimeAfterOrderByStartTimeAsc(SlotStatus.OPEN, Instant.now())
                .stream()
                .filter(slot -> !proposedSlotIds.contains(slot.getId()))
                .filter(slot -> slot.getProvider().getStatus() != ProviderStatus.OFFLINE)
                .filter(slot -> reason != TriggerReason.PROVIDER_OFFLINE
                        || !slot.getProvider().getId().equals(lockedAppointment.getProvider().getId()))
                .toList();

        List<SchedulingStrategy.Recommendation> recommendations =
                strategy.recommend(lockedAppointment, reason, candidates);

        // Strategies return candidates in preference order. Re-check each one under
        // a database lock so a stale first choice does not discard valid alternatives.
        for (SchedulingStrategy.Recommendation recommendation : recommendations) {
            // The recommendation already references a managed candidate. Refreshing it
            // with a write lock atomically obtains the lock and updates its @Version value;
            // re-querying the same entity can fail when another transaction changed it.
            Slot selected = recommendation.slot();
            try {
                refreshSlotAndProvider(selected);
            } catch (EntityNotFoundException exception) {
                continue;
            }

            if (!isEligible(selected, lockedAppointment, reason)) {
                continue;
            }
            if (suggestions.existsByRecommendedSlotAndStatus(
                    selected, SuggestionStatus.PENDING)) {
                continue;
            }

            RescheduleSuggestion suggestion = new RescheduleSuggestion(
                    UUID.randomUUID().toString(),
                    lockedAppointment,
                    selected,
                    selected.getProvider(),
                    lockedAppointment.getProvider().getName(),
                    lockedAppointment.getSlot().getStartTime(),
                    recommendation.confidence(),
                    recommendation.reasoning(),
                    recommendation.source(),
                    SuggestionStatus.PENDING,
                    reason,
                    Instant.now(),
                    0);

            RescheduleSuggestion savedSuggestion = suggestions.save(suggestion);
            log.info("Scheduling suggestion persisted | trigger={} | source={} | suggestionId={} | candidateCount={}",
                    reason, savedSuggestion.getSource(), savedSuggestion.getId(), candidates.size());
            if (lockedAppointment.getStatus() == AppointmentStatus.CONFIRMED) {
                lockedAppointment.setStatus(AppointmentStatus.RESCHEDULE_PENDING);
                appointments.save(lockedAppointment);
            }
            return Optional.of(savedSuggestion);
        }

        return Optional.empty();
    }

    @Transactional
    public void processAutomatic(String appointmentId, TriggerReason reason) {
        Appointment appointment = appointments.lockById(appointmentId)
                .orElseThrow(() -> new NotFound("Appointment not found"));

        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            return;
        }

        if (reason == TriggerReason.PROVIDER_OFFLINE) {
            // Lock and refresh the provider before acting on an asynchronous event.
            // A delayed OFFLINE event must not reschedule appointments after the
            // provider has already been brought back online.
            try {
                entityManager.refresh(appointment.getProvider(), LockModeType.PESSIMISTIC_WRITE);
            } catch (EntityNotFoundException exception) {
                throw new NotFound("Provider not found");
            }
            if (appointment.getProvider().getStatus() != ProviderStatus.OFFLINE) {
                return;
            }
        }

        appointment.setStatus(AppointmentStatus.RESCHEDULE_PENDING);
        appointments.save(appointment);
        generate(appointment, reason);
    }

    public List<RescheduleSuggestion> list(SuggestionStatus status) {
        return status == null
                ? suggestions.findAll()
                : suggestions.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public RescheduleSuggestion decide(String id, String action, Boolean patientConfirmed) {
        if (!"ACCEPT".equals(action) && !"REJECT".equals(action)) {
            throw new IllegalArgumentException("Action must be ACCEPT or REJECT");
        }
        if ("ACCEPT".equals(action) && !Boolean.TRUE.equals(patientConfirmed)) {
            throw new Conflict("Confirm the new appointment time with the patient before accepting");
        }

        // Always lock the appointment before the suggestion. This consistent lock
        // order serializes decisions for one appointment and avoids sibling-suggestion
        // deadlocks when an accepted suggestion rejects the remaining pending ones.
        RescheduleSuggestion snapshot = suggestions.findById(id)
                .orElseThrow(() -> new NotFound("Suggestion not found"));

        // Suggestions eagerly load their appointment. Refreshing that managed entity with
        // a write lock avoids re-querying a stale @Version instance after another decision
        // has already committed. Appointment-first lock ordering serializes sibling decisions.
        Appointment appointment = snapshot.getAppointment();
        try {
            entityManager.refresh(appointment, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException exception) {
            // A version mismatch on refresh can be reported as EntityNotFoundException
            // by Hibernate even while the row exists. In a concurrent decision, return
            // a conflict so the client can reload instead of misreporting a missing row.
            throw new Conflict("Appointment changed while the decision was being processed; reload and retry");
        }

        // Reuse and refresh the already-managed suggestion too; this makes the loser of a
        // concurrent sibling decision observe the winner's ACCEPTED/REJECTED status.
        RescheduleSuggestion selected = snapshot;
        try {
            entityManager.refresh(selected, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException exception) {
            throw new Conflict("Suggestion changed while the decision was being processed; reload and retry");
        }

        if (selected.getStatus() != SuggestionStatus.PENDING) {
            throw new Conflict("Suggestion is already resolved");
        }

        if ("REJECT".equals(action)) {
            selected.setStatus(SuggestionStatus.REJECTED);
            return suggestions.save(selected);
        }

        if (isFinalized(appointment.getStatus())) {
            throw new Conflict("Appointment is already finalized");
        }

        // The suggestion's eager association may already be in the persistence context
        // with an old version. Refreshing it directly obtains the write lock and reloads
        // the latest status before deciding whether it can be booked.
        Slot destination = selected.getRecommendedSlot();
        try {
            refreshSlotAndProvider(destination);
        } catch (EntityNotFoundException exception) {
            throw new NotFound("Destination slot not found");
        }

        if (destination.getStatus() != SlotStatus.OPEN
                || destination.getStartTime().isBefore(Instant.now())
                || destination.getProvider().getStatus() == ProviderStatus.OFFLINE) {
            throw new Conflict("Recommended slot is no longer available");
        }

        Slot originalSlotReference = appointment.getSlot();
        Slot originalSlot = originalSlotReference;
        try {
            entityManager.refresh(originalSlot, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException exception) {
            throw new NotFound("Original slot not found");
        }

        if (originalSlot.getStatus() != SlotStatus.CANCELLED) {
            originalSlot.setStatus(SlotStatus.OPEN);
        }

        destination.setStatus(SlotStatus.BOOKED);
        appointment.setSlot(destination);
        appointment.setProvider(destination.getProvider());
        appointment.setStatus(AppointmentStatus.RESCHEDULED);
        selected.setStatus(SuggestionStatus.ACCEPTED);

        String selectedId = selected.getId();
        suggestions.findByAppointmentAndStatus(appointment, SuggestionStatus.PENDING)
                .stream()
                .filter(other -> !other.getId().equals(selectedId))
                .forEach(other -> other.setStatus(SuggestionStatus.REJECTED));

        return suggestions.save(selected);
    }

    private void refreshSlotAndProvider(Slot slot) {
        entityManager.refresh(slot, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(slot.getProvider(), LockModeType.PESSIMISTIC_WRITE);
    }

    private boolean isEligible(Slot slot, Appointment appointment, TriggerReason reason) {
        if (slot.getStatus() != SlotStatus.OPEN
                || slot.getStartTime().isBefore(Instant.now())
                || slot.getProvider().getStatus() == ProviderStatus.OFFLINE) {
            return false;
        }

        return reason != TriggerReason.PROVIDER_OFFLINE
                || !slot.getProvider().getId().equals(appointment.getProvider().getId());
    }

    private boolean isFinalized(AppointmentStatus status) {
        return status == AppointmentStatus.RESCHEDULED || status == AppointmentStatus.COMPLETED;
    }
}
