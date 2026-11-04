package com.slotmind.service;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.event.WorkflowEvent;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {
    @Mock
    private AppointmentRepository appointments;

    @Mock
    private SchedulingService scheduling;

    @Mock
    private SlotRepository slots;

    @Test
    void providerOfflineProcessesEveryConfirmedAppointment() {
        Appointment first = appointment("APT-001");
        Appointment second = appointment("APT-002");
        when(appointments.findByProvider_IdAndStatus(
                "PRV-001", AppointmentStatus.CONFIRMED))
                .thenReturn(List.of(first, second));

        service().onWorkflowEvent(new WorkflowEvent(
                TriggerReason.PROVIDER_OFFLINE, "PRV-001"));

        verify(scheduling).processAutomatic("APT-001", TriggerReason.PROVIDER_OFFLINE);
        verify(scheduling).processAutomatic("APT-002", TriggerReason.PROVIDER_OFFLINE);
    }

    @Test
    void oneAppointmentFailureDoesNotStopProviderOfflineBatch() {
        Appointment first = appointment("APT-001");
        Appointment second = appointment("APT-002");
        when(appointments.findByProvider_IdAndStatus(
                "PRV-001", AppointmentStatus.CONFIRMED))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("simulated failure"))
                .when(scheduling).processAutomatic("APT-001", TriggerReason.PROVIDER_OFFLINE);

        service().onWorkflowEvent(new WorkflowEvent(
                TriggerReason.PROVIDER_OFFLINE, "PRV-001"));

        verify(scheduling).processAutomatic("APT-001", TriggerReason.PROVIDER_OFFLINE);
        verify(scheduling).processAutomatic("APT-002", TriggerReason.PROVIDER_OFFLINE);
    }

    @Test
    void cancelledSlotReschedulesItsConfirmedAppointment() {
        Slot cancelled = mock(Slot.class);
        Appointment appointment = appointment("APT-003");
        when(appointment.getStatus()).thenReturn(AppointmentStatus.CONFIRMED);
        when(slots.findById("SLT-003")).thenReturn(Optional.of(cancelled));
        when(appointments.findBySlot(cancelled)).thenReturn(Optional.of(appointment));

        service().onWorkflowEvent(new WorkflowEvent(
                TriggerReason.SLOT_CANCELLED, "SLT-003"));

        verify(scheduling).processAutomatic("APT-003", TriggerReason.SLOT_CANCELLED);
    }

    @Test
    void cancelledSlotDoesNotRescheduleAnAppointmentThatIsNoLongerConfirmed() {
        Slot cancelled = mock(Slot.class);
        Appointment appointment = mock(Appointment.class);
        when(appointment.getStatus()).thenReturn(AppointmentStatus.RESCHEDULED);
        when(slots.findById("SLT-003")).thenReturn(Optional.of(cancelled));
        when(appointments.findBySlot(cancelled)).thenReturn(Optional.of(appointment));

        service().onWorkflowEvent(new WorkflowEvent(
                TriggerReason.SLOT_CANCELLED, "SLT-003"));

        verify(scheduling, never()).processAutomatic("APT-003", TriggerReason.SLOT_CANCELLED);
    }

    private WorkflowService service() {
        return new WorkflowService(appointments, scheduling, slots);
    }

    private Appointment appointment(String id) {
        Appointment appointment = mock(Appointment.class);
        when(appointment.getId()).thenReturn(id);
        return appointment;
    }
}
