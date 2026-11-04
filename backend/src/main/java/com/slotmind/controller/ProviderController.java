package com.slotmind.controller;

import com.slotmind.dto.ProviderView;
import com.slotmind.dto.StatusRequest;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.service.MappingService;
import com.slotmind.service.ProviderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
public class ProviderController {

    private final ProviderService providerService;
    private final MappingService mappingService;

    public ProviderController(ProviderService providerService, MappingService mappingService) {
        this.providerService = providerService;
        this.mappingService = mappingService;
    }

    @GetMapping("/providers")
    public List<ProviderView> providers() {
        return providerService.list().stream()
                .map(mappingService::provider)
                .toList();
    }

    @PatchMapping("/providers/{id}/status")
    public ProviderView providerStatus(
            @PathVariable String id,
            @Valid @RequestBody StatusRequest request) {
        return mappingService.provider(
                providerService.status(id, parseProviderStatus(request.status())));
    }

    private ProviderStatus parseProviderStatus(String value) {
        try {
            return ProviderStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Status must be AVAILABLE, BUSY, or OFFLINE");
        }
    }
}
