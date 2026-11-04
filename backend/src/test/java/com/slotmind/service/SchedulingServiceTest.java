package com.slotmind.service;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.repository.SuggestionRepository;
import com.slotmind.strategy.SchedulingStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulingServiceTest {
    @Mock
    private SlotRepository slots;

    @Mock
    private SuggestionRepository suggestions;

    @Mock
    private SchedulingStrategy strategy;

    @Mock
    private AppointmentRepository appointments;

    @Mock
    private EntityManager entityManager;

    private SchedulingService schedulingService;
    private Appointment appointment;
    private Slot candidate;

    @BeforeEach
    void setUp() {
        schedulingService = new SchedulingService(
                slots, suggestions, strategy, appointments, entityManager);

        Instant start = Instant.now().plusSeconds(3_600);
        Provider originalProvider = new Provider(
                "P1", "Original Provider", Specialty.GENERAL, ProviderStatus.OFFLINE, 0);
        Provider alternateProvider = new Provider(
                "P2", "Alternate Provider", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);

        Slot originalSlot = new Slot("S1", originalProvider, start, 30, SlotStatus.BOOKED, 0);
        candidate = new Slot(
                "S2", alternateProvider, start.plusSeconds(3_600), 30, SlotStatus.OPEN, 0);
        appointment = new Appointment(
                "A1", "Patient", AppointmentType.ROUTINE, originalProvider, originalSlot,
                AppointmentStatus.RESCHEDULE_PENDING, Instant.now(), Instant.now(), 0);

        when(appointments.lockById("A1")).thenReturn(Optional.of(appointment));
        when(suggestions.findByStatusOrderByCreatedAtDesc(SuggestionStatus.PENDING))
                .thenReturn(List.of());
        when(slots.findByStatusAndStartTimeAfterOrderByStartTimeAsc(
                eq(SlotStatus.OPEN), any(Instant.class)))
                .thenReturn(List.of(candidate));
    }

    @Test
    void doesNotCreateDuplicatePendingSuggestionForSameSlot() {
        when(suggestions.existsByAppointmentAndTriggerReasonAndStatus(
                appointment, TriggerReason.PROVIDER_OFFLINE, SuggestionStatus.PENDING))
                .thenReturn(false);
        when(strategy.recommend(eq(appointment), eq(TriggerReason.PROVIDER_OFFLINE), anyList()))
                .thenReturn(List.of(new SchedulingStrategy.Recommendation(
                        candidate, 0.9, "Matching specialty", "RULE_BASED")));
        when(suggestions.existsByRecommendedSlotAndStatus(
                candidate, SuggestionStatus.PENDING)).thenReturn(true);

        Optional<?> result = schedulingService.generate(appointment, TriggerReason.PROVIDER_OFFLINE);

        assertFalse(result.isPresent());
        verify(suggestions, never()).save(any());
    }

    @Test
    void savesSuggestionWhenSelectedSlotIsStillUnclaimed() {
        when(suggestions.existsByAppointmentAndTriggerReasonAndStatus(
                appointment, TriggerReason.PROVIDER_OFFLINE, SuggestionStatus.PENDING))
                .thenReturn(false);
        when(strategy.recommend(eq(appointment), eq(TriggerReason.PROVIDER_OFFLINE), anyList()))
                .thenReturn(List.of(new SchedulingStrategy.Recommendation(
                        candidate, 0.9, "Matching specialty", "RULE_BASED")));
        when(suggestions.existsByRecommendedSlotAndStatus(
                candidate, SuggestionStatus.PENDING)).thenReturn(false);
        when(suggestions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<?> result = schedulingService.generate(appointment, TriggerReason.PROVIDER_OFFLINE);

        assertTrue(result.isPresent());
        verify(suggestions).save(any());
    }

    @Test
    void movesConfirmedAppointmentToReviewStateWhenManualSuggestionIsCreated() {
        when(suggestions.existsByAppointmentAndTriggerReasonAndStatus(
                appointment, TriggerReason.INITIAL, SuggestionStatus.PENDING)).thenReturn(false);
        when(strategy.recommend(eq(appointment), eq(TriggerReason.INITIAL), anyList()))
                .thenReturn(List.of(new SchedulingStrategy.Recommendation(
                        candidate, 0.9, "Matching specialty", "RULE_BASED")));
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        when(suggestions.existsByRecommendedSlotAndStatus(
                candidate, SuggestionStatus.PENDING)).thenReturn(false);
        when(suggestions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<?> result = schedulingService.generate(appointment, TriggerReason.INITIAL);

        assertTrue(result.isPresent());
        org.junit.jupiter.api.Assertions.assertEquals(
                AppointmentStatus.RESCHEDULE_PENDING, appointment.getStatus());
        verify(appointments).save(appointment);
    }

    @Test
    void triesNextRankedCandidateWhenFirstSlotIsAlreadyPending() {
        when(suggestions.existsByAppointmentAndTriggerReasonAndStatus(
                appointment, TriggerReason.PROVIDER_OFFLINE, SuggestionStatus.PENDING))
                .thenReturn(false);
        Slot secondCandidate = new Slot(
                "S3", candidate.getProvider(), candidate.getStartTime().plusSeconds(600),
                30, SlotStatus.OPEN, 0);
        when(strategy.recommend(eq(appointment), eq(TriggerReason.PROVIDER_OFFLINE), anyList()))
                .thenReturn(List.of(
                        new SchedulingStrategy.Recommendation(
                                candidate, 0.9, "First choice", "RULE_BASED"),
                        new SchedulingStrategy.Recommendation(
                                secondCandidate, 0.8, "Second choice", "RULE_BASED")));
        when(suggestions.existsByRecommendedSlotAndStatus(candidate, SuggestionStatus.PENDING))
                .thenReturn(true);
        when(suggestions.existsByRecommendedSlotAndStatus(secondCandidate, SuggestionStatus.PENDING))
                .thenReturn(false);
        when(suggestions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<?> result = schedulingService.generate(appointment, TriggerReason.PROVIDER_OFFLINE);

        assertTrue(result.isPresent());
        verify(entityManager).refresh(secondCandidate, LockModeType.PESSIMISTIC_WRITE);
        verify(suggestions).save(any());
    }
}
