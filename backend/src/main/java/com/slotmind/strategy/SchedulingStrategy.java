package com.slotmind.strategy;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Slot;
import com.slotmind.enums.TriggerReason;

import java.util.List;

public interface SchedulingStrategy {

    /**
     * Returns eligible recommendations in preference order, best candidate first.
     * Callers must revalidate the selected slot inside the booking transaction.
     */
    List<Recommendation> recommend(
            Appointment appointment,
            TriggerReason reason,
            List<Slot> candidates);

    record Recommendation(
            Slot slot,
            double confidence,
            String reasoning,
            String source) {
    }
}
