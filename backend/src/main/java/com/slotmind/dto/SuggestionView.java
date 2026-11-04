package com.slotmind.dto;

import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;

import java.time.Instant;

public record SuggestionView(
        String id,
        String appointmentId,
        String patientName,
        String originalProviderName,
        Instant originalTime,
        String slotId,
        String providerName,
        Instant recommendedTime,
        double confidence,
        String reasoning,
        SuggestionStatus status,
        TriggerReason triggerReason,
        String source) {
}
