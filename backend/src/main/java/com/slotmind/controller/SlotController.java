package com.slotmind.controller;

import com.slotmind.dto.CreateSlot;
import com.slotmind.dto.SlotView;
import com.slotmind.dto.StatusRequest;
import com.slotmind.enums.SlotStatus;
import com.slotmind.service.MappingService;
import com.slotmind.service.SlotService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
public class SlotController {

    private final SlotService slotService;
    private final MappingService mappingService;

    public SlotController(SlotService slotService, MappingService mappingService) {
        this.slotService = slotService;
        this.mappingService = mappingService;
    }

    @GetMapping("/slots")
    public List<SlotView> slots() {
        return slotService.list().stream()
                .map(mappingService::slot)
                .toList();
    }

    @PostMapping("/slots")
    public SlotView create(@Valid @RequestBody CreateSlot request) {
        return mappingService.slot(slotService.create(request));
    }

    @PatchMapping("/slots/{id}/status")
    public SlotView slotStatus(
            @PathVariable String id,
            @Valid @RequestBody StatusRequest request) {
        return mappingService.slot(slotService.status(id, parseSlotStatus(request.status())));
    }

    private SlotStatus parseSlotStatus(String value) {
        try {
            return SlotStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Status must be OPEN, BOOKED, or CANCELLED");
        }
    }
}
