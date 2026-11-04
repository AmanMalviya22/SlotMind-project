package com.slotmind.dto;

import jakarta.validation.constraints.NotBlank;

public record StatusRequest(@NotBlank String status) {
}
