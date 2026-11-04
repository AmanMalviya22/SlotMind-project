package com.slotmind.service;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.repository.SuggestionRepository;
import com.slotmind.strategy.AISchedulingStrategy;
import com.slotmind.strategy.SchedulingStrategy;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class SchedulingAcceptanceIntegrationTest {
    @Autowired
    private SchedulingService schedulingService;

    @Autowired
    private AppointmentRepository appointments;

    @Autowired
    private SlotRepository slots;

    @Autowired
    private SuggestionRepository suggestions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SchedulingStrategy selectedStrategy;

    @Test
    void alwaysUsesAiStrategyAsThePrimaryRecommendationStrategy() {
        assertInstanceOf(AISchedulingStrategy.class, selectedStrategy);
    }

    @Test
    void acceptsSuggestionAndUpdatesAppointmentAndBothSlotsAtomically() {
        Appointment appointment = appointments.findById("APT-001").orElseThrow();
        Slot originalSlot = appointment.getSlot();
        Slot replacementSlot = slots.findById("SLT-005").orElseThrow();

        assertEquals(SlotStatus.BOOKED, originalSlot.getStatus());
        assertEquals(SlotStatus.OPEN, replacementSlot.getStatus());

        RescheduleSuggestion suggestion = pendingSuggestion(
                "IT-SUG-001", appointment, replacementSlot);
        suggestions.saveAndFlush(suggestion);

        schedulingService.decide(suggestion.getId(), "ACCEPT", true);

        entityManager.flush();
        entityManager.clear();

        Appointment updatedAppointment = appointments.findById("APT-001").orElseThrow();
        Slot updatedOriginalSlot = slots.findById(originalSlot.getId()).orElseThrow();
        Slot updatedReplacementSlot = slots.findById(replacementSlot.getId()).orElseThrow();
        RescheduleSuggestion updatedSuggestion =
                suggestions.findById("IT-SUG-001").orElseThrow();

        assertEquals(AppointmentStatus.RESCHEDULED, updatedAppointment.getStatus());
        assertEquals(replacementSlot.getId(), updatedAppointment.getSlot().getId());
        assertEquals(SlotStatus.OPEN, updatedOriginalSlot.getStatus());
        assertEquals(SlotStatus.BOOKED, updatedReplacementSlot.getStatus());
        assertEquals(SuggestionStatus.ACCEPTED, updatedSuggestion.getStatus());
    }

    @Test
    void acceptingSuggestionPreservesCancelledOriginalSlot() {
        Appointment appointment = appointments.findById("APT-001").orElseThrow();
        Slot originalSlot = appointment.getSlot();
        Slot replacementSlot = slots.findById("SLT-005").orElseThrow();

        originalSlot.setStatus(SlotStatus.CANCELLED);
        slots.saveAndFlush(originalSlot);

        RescheduleSuggestion suggestion = pendingSuggestion(
                "IT-SUG-CANCELLED-ORIGINAL", appointment, replacementSlot);
        suggestions.saveAndFlush(suggestion);

        schedulingService.decide(suggestion.getId(), "ACCEPT", true);

        entityManager.flush();
        entityManager.clear();

        Slot persistedOriginal = slots.findById(originalSlot.getId()).orElseThrow();
        Appointment persistedAppointment = appointments.findById("APT-001").orElseThrow();

        assertEquals(SlotStatus.CANCELLED, persistedOriginal.getStatus());
        assertEquals(AppointmentStatus.RESCHEDULED, persistedAppointment.getStatus());
        assertEquals(replacementSlot.getId(), persistedAppointment.getSlot().getId());
    }

    @Test
    void rejectsAcceptanceWhenDestinationIsNoLongerOpenWithoutMovingAppointment() {
        Appointment appointment = appointments.findById("APT-001").orElseThrow();
        Slot originalSlot = appointment.getSlot();
        Slot replacementSlot = slots.findById("SLT-005").orElseThrow();

        replacementSlot.setStatus(SlotStatus.BOOKED);
        slots.saveAndFlush(replacementSlot);

        RescheduleSuggestion suggestion = pendingSuggestion(
                "IT-SUG-STALE-DESTINATION", appointment, replacementSlot);
        suggestions.saveAndFlush(suggestion);

        assertThrows(Conflict.class,
                () -> schedulingService.decide(suggestion.getId(), "ACCEPT", true));

        entityManager.clear();

        Appointment persistedAppointment = appointments.findById("APT-001").orElseThrow();
        Slot persistedOriginal = slots.findById(originalSlot.getId()).orElseThrow();
        RescheduleSuggestion persistedSuggestion =
                suggestions.findById("IT-SUG-STALE-DESTINATION").orElseThrow();

        assertEquals(AppointmentStatus.CONFIRMED, persistedAppointment.getStatus());
        assertEquals(originalSlot.getId(), persistedAppointment.getSlot().getId());
        assertEquals(SlotStatus.BOOKED, persistedOriginal.getStatus());
        assertEquals(SlotStatus.BOOKED,
                slots.findById(replacementSlot.getId()).orElseThrow().getStatus());
        assertEquals(SuggestionStatus.PENDING, persistedSuggestion.getStatus());
    }

    @Test
    void rejectingSuggestionDoesNotBookReplacementOrMoveAppointment() {
        Appointment appointment = appointments.findById("APT-001").orElseThrow();
        Slot originalSlot = appointment.getSlot();
        Slot replacementSlot = slots.findById("SLT-005").orElseThrow();

        RescheduleSuggestion suggestion = pendingSuggestion(
                "IT-SUG-REJECT", appointment, replacementSlot);
        suggestions.saveAndFlush(suggestion);

        schedulingService.decide(suggestion.getId(), "REJECT", false);

        entityManager.flush();
        entityManager.clear();

        Appointment persistedAppointment = appointments.findById("APT-001").orElseThrow();
        assertEquals(AppointmentStatus.CONFIRMED, persistedAppointment.getStatus());
        assertEquals(originalSlot.getId(), persistedAppointment.getSlot().getId());
        assertEquals(SlotStatus.BOOKED,
                slots.findById(originalSlot.getId()).orElseThrow().getStatus());
        assertEquals(SlotStatus.OPEN,
                slots.findById(replacementSlot.getId()).orElseThrow().getStatus());
        assertEquals(SuggestionStatus.REJECTED,
                suggestions.findById("IT-SUG-REJECT").orElseThrow().getStatus());
    }

    private RescheduleSuggestion pendingSuggestion(
            String id, Appointment appointment, Slot replacementSlot) {
        return new RescheduleSuggestion(
                id,
                appointment,
                replacementSlot,
                replacementSlot.getProvider(),
                appointment.getProvider().getName(),
                appointment.getSlot().getStartTime(),
                0.92,
                "The replacement slot is available and appropriate for this appointment.",
                "RULE_BASED",
                SuggestionStatus.PENDING,
                TriggerReason.INITIAL,
                Instant.now(),
                0);
    }
}
