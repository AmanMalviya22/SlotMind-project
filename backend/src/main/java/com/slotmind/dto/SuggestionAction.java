package com.slotmind.dto;

import jakarta.validation.constraints.NotBlank;

public record SuggestionAction(@NotBlank String action, Boolean patientConfirmed) {

    public SuggestionAction(@NotBlank String action) {
        this(action, null);
    }
}
