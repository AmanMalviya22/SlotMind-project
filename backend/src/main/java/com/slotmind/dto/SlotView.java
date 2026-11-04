package com.slotmind.dto;

import com.slotmind.enums.SlotStatus;

import java.time.Instant;

public record SlotView(
        String id,
        String providerId,
        String providerName,
        Instant startTime,
        int durationMinutes,
        SlotStatus status) {
}
