package com.slotmind.controller;

import com.slotmind.dto.AppointmentView;
import com.slotmind.dto.CreateAppointment;
import com.slotmind.dto.SuggestionView;
import com.slotmind.entity.Appointment;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.service.AppointmentService;
import com.slotmind.service.MappingService;
import com.slotmind.service.SchedulingService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final SchedulingService schedulingService;
    private final MappingService mappingService;

    public AppointmentController(
            AppointmentService appointmentService,
            SchedulingService schedulingService,
            MappingService mappingService) {
        this.appointmentService = appointmentService;
        this.schedulingService = schedulingService;
        this.mappingService = mappingService;
    }

    @PostMapping("/appointments")
    public AppointmentView create(@Valid @RequestBody CreateAppointment request) {
        return mappingService.appointment(appointmentService.create(request));
    }

    @GetMapping("/appointments")
    public List<AppointmentView> appointments(
            @RequestParam(required = false) AppointmentStatus status) {
        return appointmentService.list(status).stream()
                .map(mappingService::appointment)
                .toList();
    }

    @PostMapping("/appointments/{id}/suggest")
    public SuggestionView suggest(@PathVariable String id) {
        Appointment appointment = appointmentService.findById(id);
        return schedulingService.generate(appointment, TriggerReason.INITIAL)
                .map(mappingService::suggestion)
                .orElseThrow(() -> new Conflict(
                        "No suitable slot or pending suggestion already exists"));
    }

    /**
     * Streams progress and the final validated recommendation as Server-Sent Events.
     * Recommendations remain pending for human review and do not book a slot on their own.
     */
    @PostMapping(
            value = "/appointments/{id}/suggest/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter suggestStream(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(60_000L);
        CompletableFuture.runAsync(() -> {
            try {
                emitter.send(SseEmitter.event().name("progress").data(Map.of(
                        "stage", "started",
                        "message", "Loading appointment and checking eligible replacement slots")));

                Appointment appointment = appointmentService.findById(id);
                emitter.send(SseEmitter.event().name("progress").data(Map.of(
                        "stage", "recommending",
                        "message", "Ranking eligible slots with the configured scheduling strategy")));

                var result = schedulingService.generate(appointment, TriggerReason.INITIAL);
                if (result.isEmpty()) {
                    emitter.send(SseEmitter.event().name("workflow-error").data(Map.of(
                            "message", "No suitable slot is available, or a suggestion is already pending")));
                    emitter.complete();
                    return;
                }

                SuggestionView suggestion = mappingService.suggestion(result.get());
                emitter.send(SseEmitter.event().name("reasoning").data(Map.of(
                        "reasoning", suggestion.reasoning(),
                        "source", suggestion.source())));
                emitter.send(SseEmitter.event().name("suggestion").data(suggestion));
                emitter.send(SseEmitter.event().name("done").data(Map.of(
                        "complete", true,
                        "message", "Recommendation ready for front-desk review")));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    emitter.send(SseEmitter.event().name("workflow-error").data(Map.of(
                            "message", "Unable to create a recommendation. Refresh the queue and try again.")));
                } catch (Exception ignored) {
                    // The client may have disconnected while the workflow was running.
                }
                emitter.complete();
            }
        });
        return emitter;
    }
}
