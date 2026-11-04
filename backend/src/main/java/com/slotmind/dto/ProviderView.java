package com.slotmind.dto;

import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.Specialty;

public record ProviderView(
        String id,
        String name,
        Specialty specialty,
        ProviderStatus status) {
}
