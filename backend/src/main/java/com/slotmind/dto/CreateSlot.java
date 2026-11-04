package com.slotmind.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public record CreateSlot(
        @NotBlank String providerId,
        @NotNull @Future Instant startTime,
        @Positive @Max(480) int durationMinutes) {
}
