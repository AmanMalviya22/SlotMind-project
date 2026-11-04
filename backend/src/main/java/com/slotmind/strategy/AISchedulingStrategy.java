package com.slotmind.strategy;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Slot;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.TriggerReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class AISchedulingStrategy implements SchedulingStrategy {
    private static final Logger log = LoggerFactory.getLogger(AISchedulingStrategy.class);
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "\\b(?:[01]?\\d|2[0-3])(?::[0-5]\\d)?\\s?(?:AM|PM)\\b|\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_ZONE = Pattern.compile(
            "\\b(?:UTC|GMT|IST|PST|PDT|MST|MDT|CST|CDT|EST|EDT|BST|CET|CEST|JST|AEST|AEDT)\\b"
                    + "|\\b(?:Africa|America|Antarctica|Asia|Atlantic|Australia|Europe|Indian|Pacific)/[A-Za-z_+-]+(?:/[A-Za-z_+-]+)?\\b",
            Pattern.CASE_INSENSITIVE);

    private final LLMGateway gateway;
    private final RuleBasedSchedulingStrategy fallback;

    public AISchedulingStrategy(
            LLMGateway gateway,
            RuleBasedSchedulingStrategy fallback) {
        this.gateway = gateway;
        this.fallback = fallback;
    }

    @Override
    public List<Recommendation> recommend(
            Appointment appointment,
            TriggerReason reason,
            List<Slot> candidates) {
        try {
            Optional<LLMGateway.Recommendation> answer =
                    gateway.recommend(appointment, reason, candidates);

            if (answer.isPresent()) {
                Optional<Recommendation> validated =
                        validate(answer.get(), appointment, reason, candidates);
                if (validated.isPresent()) {
                    // Keep the AI recommendation first, then retain deterministic
                    // alternatives in case the selected slot becomes unavailable.
                    List<Recommendation> ordered = new ArrayList<>();
                    Recommendation primary = validated.get();
                    ordered.add(primary);
                    fallback.recommend(appointment, reason, candidates).stream()
                            .filter(candidate -> !candidate.slot().getId()
                                    .equals(primary.slot().getId()))
                            .forEach(ordered::add);
                    log.info("Scheduling recommendation selected | trigger={} | source=AI | candidateCount={} | alternatives={}",
                            reason, candidates.size(), ordered.size() - 1);
                    return List.copyOf(ordered);
                }

                log.warn(
                        "LLM recommendation rejected by validation | trigger={} | candidateCount={} | fallback=RULE_BASED",
                        reason,
                        candidates.size());
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "LLM recommendation could not be used; using rule-based fallback: {}",
                    exception.getClass().getSimpleName());
        }

        List<Recommendation> fallbackRecommendations = fallback.recommend(appointment, reason, candidates);
        log.info("Scheduling recommendation selected | trigger={} | source=RULE_BASED | candidateCount={} | recommendations={}",
                reason, candidates.size(), fallbackRecommendations.size());
        return fallbackRecommendations;
    }

    private Optional<Recommendation> validate(
            LLMGateway.Recommendation answer,
            Appointment appointment,
            TriggerReason reason,
            List<Slot> candidates) {
        if (answer.slotId() == null
                || answer.slotId().isBlank()
                || !Double.isFinite(answer.confidence())
                || answer.confidence() < 0.01
                || answer.confidence() > 1
                || answer.reasoning() == null
                || answer.reasoning().isBlank()
                || CLOCK_TIME.matcher(answer.reasoning()).find()
                || TIME_ZONE.matcher(answer.reasoning()).find()
                || answer.reasoning().length() > 1000) {
            return Optional.empty();
        }

        Optional<Slot> selected = candidates.stream()
                .filter(slot -> slot.getId().equals(answer.slotId()))
                .findFirst();
        if (selected.isEmpty()) {
            return Optional.empty();
        }

        Slot slot = selected.get();
        if (slot.getStatus() != SlotStatus.OPEN
                || slot.getStartTime() == null
                || !slot.getStartTime().isAfter(Instant.now())
                || slot.getProvider().getStatus() == ProviderStatus.OFFLINE) {
            return Optional.empty();
        }

        if (reason == TriggerReason.PROVIDER_OFFLINE
                && slot.getProvider().getId().equals(appointment.getProvider().getId())) {
            return Optional.empty();
        }

        if (violatesPreference(slot, appointment, reason, candidates)) {
            return Optional.empty();
        }

        String actualProviderId = slot.getProvider().getId();
        String expectedAlternateProviderId = actualProviderId.equals(appointment.getProvider().getId())
                ? null
                : actualProviderId;
        if (!java.util.Objects.equals(answer.alternateProviderId(), expectedAlternateProviderId)) {
            return Optional.empty();
        }

        return Optional.of(new Recommendation(slot, answer.confidence(), answer.reasoning(), "AI"));
    }

    private boolean violatesPreference(
            Slot selected,
            Appointment appointment,
            TriggerReason reason,
            List<Slot> candidates) {
        List<Slot> eligible = fallback.recommend(appointment, reason, candidates).stream()
                .map(Recommendation::slot)
                .toList();
        if (eligible.isEmpty()) {
            return true;
        }

        int bestPreferenceRank = fallback.preferenceRank(appointment, reason, eligible.get(0));
        int selectedPreferenceRank = fallback.preferenceRank(appointment, reason, selected);
        if (selectedPreferenceRank > bestPreferenceRank) {
            return true;
        }

        // The AI may choose within the best provider/specialty group (for example,
        // to preserve the original appointment duration). Keep equal-time tie-breaking
        // deterministic so repeated evaluations remain stable.
        return eligible.stream()
                .filter(candidate -> fallback.preferenceRank(appointment, reason, candidate)
                        == selectedPreferenceRank)
                .filter(candidate -> candidate.getStartTime().equals(selected.getStartTime()))
                .anyMatch(candidate -> candidate.getId().compareTo(selected.getId()) < 0);
    }
}
