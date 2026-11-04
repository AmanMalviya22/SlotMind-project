package com.slotmind.strategy;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.TriggerReason;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
public class RuleBasedSchedulingStrategy implements SchedulingStrategy {

    @Override
    public List<Recommendation> recommend(
            Appointment appointment,
            TriggerReason reason,
            List<Slot> candidates) {
        Instant now = Instant.now();

        return candidates.stream()
                .filter(slot -> slot.getStatus() == SlotStatus.OPEN)
                .filter(slot -> slot.getStartTime() != null && slot.getStartTime().isAfter(now))
                .filter(slot -> slot.getProvider().getStatus() != ProviderStatus.OFFLINE)
                .filter(slot -> reason != TriggerReason.PROVIDER_OFFLINE
                        || !slot.getProvider().getId().equals(appointment.getProvider().getId()))
                .sorted(Comparator
                        .comparingInt((Slot slot) -> preferenceRank(appointment, reason, slot))
                        .thenComparing(Slot::getStartTime)
                        .thenComparing(Slot::getId))
                .map(slot -> result(appointment, reason, slot))
                .toList();
    }

    int preferenceRank(
            Appointment appointment,
            TriggerReason reason,
            Slot slot) {
        // Urgent appointments are prioritized by time within the original specialty.
        // This is an operational scheduling rule, not a clinical urgency assessment.
        if (appointment.getType() == AppointmentType.URGENT) {
            return slot.getProvider().getSpecialty() == appointment.getProvider().getSpecialty()
                    ? 0 : 1;
        }

        if (reason != TriggerReason.PROVIDER_OFFLINE
                && slot.getProvider().getId().equals(appointment.getProvider().getId())) {
            return 0;
        }

        if (slot.getProvider().getSpecialty() == appointment.getProvider().getSpecialty()) {
            return 1;
        }

        return 2;
    }

    private Recommendation result(
            Appointment appointment,
            TriggerReason reason,
            Slot slot) {
        String reasonText;
        if (reason != TriggerReason.PROVIDER_OFFLINE
                && slot.getProvider().getId().equals(appointment.getProvider().getId())) {
            reasonText = "same provider";
        } else if (slot.getProvider().getSpecialty() == appointment.getProvider().getSpecialty()) {
            reasonText = "matching specialty";
        } else {
            reasonText = "earliest available slot";
        }

        String explanation = "We found the " + reasonText
                + " next available appointment with " + slot.getProvider().getName() + ".";

        return new Recommendation(slot, 0.92, explanation, "RULE_BASED");
    }
}
