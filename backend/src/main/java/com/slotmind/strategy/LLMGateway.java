package com.slotmind.strategy;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Slot;
import com.slotmind.enums.TriggerReason;

import java.util.List;
import java.util.Optional;

public interface LLMGateway {

    Optional<Recommendation> recommend(
            Appointment appointment,
            TriggerReason reason,
            List<Slot> candidates);

    record Recommendation(
            String slotId,
            String alternateProviderId,
            double confidence,
            String reasoning) {
    }
}
