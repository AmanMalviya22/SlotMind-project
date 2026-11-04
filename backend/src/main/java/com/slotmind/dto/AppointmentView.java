package com.slotmind.dto;

import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;

import java.time.Instant;

public record AppointmentView(
        String id,
        String patientName,
        AppointmentType type,
        String providerId,
        String providerName,
        String slotId,
        Instant startTime,
        AppointmentStatus status) {
}
