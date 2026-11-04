package com.slotmind.controller;

import com.slotmind.dto.AppointmentView;
import com.slotmind.dto.CreateAppointment;
import com.slotmind.dto.SuggestionView;
import com.slotmind.entity.Appointment;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.service.AppointmentService;
import com.slotmind.service.MappingService;
import com.slotmind.service.SchedulingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppointmentControllerContractTest {

    @Test
    void createsAppointmentAndMapsTheResponse() {
        AppointmentService appointments = mock(AppointmentService.class);
        MappingService mapper = mock(MappingService.class);
        Appointment entity = mock(Appointment.class);
        CreateAppointment request =
                new CreateAppointment("Aarav", AppointmentType.URGENT, "P-1", "SL-1");
        AppointmentView view = appointmentView();
        when(appointments.create(request)).thenReturn(entity);
        when(mapper.appointment(entity)).thenReturn(view);

        AppointmentController controller =
                new AppointmentController(appointments, mock(SchedulingService.class), mapper);

        assertEquals(view, controller.create(request));
        verify(appointments).create(request);
        verify(mapper).appointment(entity);
    }

    @Test
    void listsAppointmentsWithTheRequestedStatusFilter() {
        AppointmentService appointments = mock(AppointmentService.class);
        MappingService mapper = mock(MappingService.class);
        Appointment entity = mock(Appointment.class);
        AppointmentView view = appointmentView();
        when(appointments.list(AppointmentStatus.CONFIRMED)).thenReturn(List.of(entity));
        when(mapper.appointment(entity)).thenReturn(view);

        AppointmentController controller =
                new AppointmentController(appointments, mock(SchedulingService.class), mapper);

        assertEquals(List.of(view), controller.appointments(AppointmentStatus.CONFIRMED));
        verify(appointments).list(AppointmentStatus.CONFIRMED);
        verify(mapper).appointment(entity);
    }

    @Test
    void listsAppointmentsWithoutAStatusFilter() {
        AppointmentService appointments = mock(AppointmentService.class);
        MappingService mapper = mock(MappingService.class);
        Appointment entity = mock(Appointment.class);
        AppointmentView view = appointmentView();
        when(appointments.list(null)).thenReturn(List.of(entity));
        when(mapper.appointment(entity)).thenReturn(view);

        AppointmentController controller =
                new AppointmentController(appointments, mock(SchedulingService.class), mapper);

        assertEquals(List.of(view), controller.appointments(null));
        verify(appointments).list(null);
    }

    @Test
    void createsAndMapsAnInitialSuggestion() {
        AppointmentService appointments = mock(AppointmentService.class);
        SchedulingService scheduling = mock(SchedulingService.class);
        MappingService mapper = mock(MappingService.class);
        Appointment appointment = mock(Appointment.class);
        RescheduleSuggestion entity = mock(RescheduleSuggestion.class);
        SuggestionView view = suggestionView();
        when(appointments.findById("APT-1")).thenReturn(appointment);
        when(scheduling.generate(appointment, TriggerReason.INITIAL)).thenReturn(Optional.of(entity));
        when(mapper.suggestion(entity)).thenReturn(view);

        AppointmentController controller = new AppointmentController(appointments, scheduling, mapper);

        assertEquals(view, controller.suggest("APT-1"));
        verify(scheduling).generate(appointment, TriggerReason.INITIAL);
        verify(mapper).suggestion(entity);
    }

    @Test
    void reportsConflictWhenNoInitialSuggestionCanBeCreated() {
        AppointmentService appointments = mock(AppointmentService.class);
        SchedulingService scheduling = mock(SchedulingService.class);
        Appointment appointment = mock(Appointment.class);
        when(appointments.findById("APT-1")).thenReturn(appointment);
        when(scheduling.generate(appointment, TriggerReason.INITIAL)).thenReturn(Optional.empty());

        AppointmentController controller =
                new AppointmentController(appointments, scheduling, mock(MappingService.class));

        assertEquals(
                "No suitable slot or pending suggestion already exists",
                assertThrows(Conflict.class, () -> controller.suggest("APT-1")).getMessage());
    }

    private AppointmentView appointmentView() {
        Instant start = Instant.parse("2099-11-04T09:00:00Z");
        return new AppointmentView(
                "APT-1", "Aarav", AppointmentType.URGENT, "P-1", "Dr. Rao",
                "SL-1", start, AppointmentStatus.CONFIRMED);
    }

    private SuggestionView suggestionView() {
        Instant original = Instant.parse("2099-11-04T09:00:00Z");
        return new SuggestionView(
                "SUG-1", "APT-1", "Aarav", "Dr. Rao", original,
                "SL-2", "Dr. Iyer", original.plusSeconds(3600), 0.9,
                "A matching slot is available.", SuggestionStatus.PENDING,
                TriggerReason.INITIAL, "AI");
    }
}
