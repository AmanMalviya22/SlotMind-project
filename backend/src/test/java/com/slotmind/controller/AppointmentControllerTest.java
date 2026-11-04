package com.slotmind.controller;

import com.slotmind.dto.SuggestionView;
import com.slotmind.entity.Appointment;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.service.AppointmentService;
import com.slotmind.service.MappingService;
import com.slotmind.service.SchedulingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

class AppointmentControllerTest {

    @Test
    void suggestionStreamIncludesProgressExplanationAndValidatedSuggestion() throws Exception {
        AppointmentService appointmentService = mock(AppointmentService.class);
        SchedulingService schedulingService = mock(SchedulingService.class);
        MappingService mappingService = mock(MappingService.class);
        Appointment appointment = mock(Appointment.class);
        RescheduleSuggestion suggestion = mock(RescheduleSuggestion.class);
        SuggestionView view = new SuggestionView(
                "SUG-1", "APT-1", "Sample Patient", "Dr. Chen", Instant.parse("2026-10-28T10:00:00Z"),
                "SLT-2", "Dr. Verma", Instant.parse("2026-10-28T11:00:00Z"), .9,
                "A matching provider is available.", SuggestionStatus.PENDING,
                TriggerReason.INITIAL, "AI");
        when(appointmentService.findById("APT-1")).thenReturn(appointment);
        when(schedulingService.generate(appointment, TriggerReason.INITIAL))
                .thenReturn(Optional.of(suggestion));
        when(mappingService.suggestion(suggestion)).thenReturn(view);
        MockMvc mockMvc = standaloneSetup(new AppointmentController(
                appointmentService, schedulingService, mappingService)).build();

        MvcResult started = mockMvc.perform(post("/api/appointments/APT-1/suggest/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("event:progress"),
                        containsString("event:reasoning"),
                        containsString("A matching provider is available."),
                        containsString("event:suggestion"),
                        containsString("event:done"))));
    }
}
