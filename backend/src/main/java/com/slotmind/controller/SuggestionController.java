package com.slotmind.controller;

import com.slotmind.dto.SuggestionAction;
import com.slotmind.dto.SuggestionView;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.service.MappingService;
import com.slotmind.service.SchedulingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
public class SuggestionController {

    private final SchedulingService schedulingService;
    private final MappingService mappingService;

    public SuggestionController(
            SchedulingService schedulingService,
            MappingService mappingService) {
        this.schedulingService = schedulingService;
        this.mappingService = mappingService;
    }

    @GetMapping("/suggestions")
    public List<SuggestionView> suggestions(
            @RequestParam(required = false) SuggestionStatus status) {
        return schedulingService.list(status).stream()
                .map(mappingService::suggestion)
                .toList();
    }

    @PatchMapping("/suggestions/{id}")
    public SuggestionView decide(
            @PathVariable String id,
            @Valid @RequestBody SuggestionAction request) {
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        if ("ACCEPT".equals(action) && !Boolean.TRUE.equals(request.patientConfirmed())) {
            throw new Conflict("Confirm the new appointment time with the patient before accepting");
        }
        return mappingService.suggestion(schedulingService.decide(id, action, request.patientConfirmed()));
    }
}
