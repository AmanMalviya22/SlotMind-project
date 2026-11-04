package com.slotmind.controller;

import com.slotmind.entity.Appointment;
import com.slotmind.enums.TriggerReason;
import com.slotmind.service.AppointmentService;
import com.slotmind.service.MappingService;
import com.slotmind.service.SchedulingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AppointmentControllerStreamFailureTest {

    @Test
    void streamsWorkflowErrorWhenNoRecommendationCanBeCreated() throws Exception {
        AppointmentService appointments = mock(AppointmentService.class);
        SchedulingService scheduling = mock(SchedulingService.class);
        Appointment appointment = mock(Appointment.class);
        when(appointments.findById("APT-1")).thenReturn(appointment);
        when(scheduling.generate(appointment, TriggerReason.INITIAL)).thenReturn(Optional.empty());
        MockMvc mockMvc = standaloneSetup(new AppointmentController(
                appointments, scheduling, mock(MappingService.class))).build();

        MvcResult started = mockMvc.perform(post("/api/appointments/APT-1/suggest/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:workflow-error")))
                .andExpect(content().string(not(containsString("event:suggestion"))));
    }

    @Test
    void returnsSafeWorkflowErrorWhenUnderlyingServiceThrows() throws Exception {
        AppointmentService appointments = mock(AppointmentService.class);
        SchedulingService scheduling = mock(SchedulingService.class);
        when(appointments.findById("APT-1"))
                .thenThrow(new IllegalStateException("internal database detail"));
        MockMvc mockMvc = standaloneSetup(new AppointmentController(
                appointments, scheduling, mock(MappingService.class))).build();

        MvcResult started = mockMvc.perform(post("/api/appointments/APT-1/suggest/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:workflow-error")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Unable to create a recommendation"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("internal database detail"));
    }
}
