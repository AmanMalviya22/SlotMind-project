package com.slotmind.dto;

import com.slotmind.enums.AppointmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAppointment(
        @NotBlank @Size(max = 120, message = "Patient name must be at most 120 characters") String patientName,
        @NotNull AppointmentType type,
        @NotBlank String providerId,
        @NotBlank String slotId) {
}
