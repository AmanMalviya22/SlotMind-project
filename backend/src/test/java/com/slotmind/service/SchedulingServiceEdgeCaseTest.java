package com.slotmind.service;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.exception.ApiExceptions.NotFound;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.repository.SuggestionRepository;
import com.slotmind.strategy.SchedulingStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulingServiceEdgeCaseTest {

    @Mock private SlotRepository slots;
    @Mock private SuggestionRepository suggestions;
    @Mock private SchedulingStrategy strategy;
    @Mock private AppointmentRepository appointments;
    @Mock private EntityManager entityManager;

    private SchedulingService service;
    private Appointment appointment;
    private Provider originalProvider;
    private Provider alternateProvider;
    private Slot originalSlot;
    private Slot candidate;
    private Instant baseTime;

    @BeforeEach
    void setUp() {
        service = new SchedulingService(slots, suggestions, strategy, appointments, entityManager);
        baseTime = Instant.now().plusSeconds(3600);
        originalProvider = provider("P1", ProviderStatus.OFFLINE, Specialty.GENERAL);
        alternateProvider = provider("P2", ProviderStatus.AVAILABLE, Specialty.GENERAL);
        originalSlot = slot("S1", originalProvider, baseTime.minusSeconds(3600), SlotStatus.BOOKED);
        candidate = slot("S2", alternateProvider, baseTime, SlotStatus.OPEN);
        appointment = new Appointment(
                "A1", "Demo Patient", AppointmentType.ROUTINE, originalProvider, originalSlot,
                AppointmentStatus.RESCHEDULE_PENDING, Instant.now(), Instant.now(), 0);
    }

    @Test
    void returnsEmptyForFinalizedAppointmentsBeforeLookingForCandidates() {
        appointment.setStatus(AppointmentStatus.COMPLETED);
        when(appointments.lockById("A1")).thenReturn(Optional.of(appointment));

        assertTrue(service.generate(appointment, TriggerReason.INITIAL).isEmpty());

        verify(strategy, never()).recommend(any(), any(), anyList());
        verify(suggestions, never()).save(any());
    }

    @Test
    void returnsEmptyWhenTheSameTriggerAlreadyHasAPendingSuggestion() {
        when(appointments.lockById("A1")).thenReturn(Optional.of(appointment));
        when(suggestions.existsByAppointmentAndTriggerReasonAndStatus(
                appointment, TriggerReason.PROVIDER_OFFLINE, SuggestionStatus.PENDING))
                .thenReturn(true);

        assertTrue(service.generate(appointment, TriggerReason.PROVIDER_OFFLINE).isEmpty());

        verify(strategy, never()).recommend(any(), any(), anyList());
    }

    @Test
    void excludesReservedOfflineAndOriginalOfflineProviderSlotsBeforeCallingStrategy() {
        Provider offline = provider("P3", ProviderStatus.OFFLINE, Specialty.GENERAL);
        Slot reserved = slot("S3", alternateProvider, baseTime.plusSeconds(600), SlotStatus.OPEN);
        Slot offlineSlot = slot("S4", offline, baseTime.plusSeconds(1200), SlotStatus.OPEN);
        Slot originalProviderSlot = slot("S5", originalProvider, baseTime.plusSeconds(1800), SlotStatus.OPEN);
        RescheduleSuggestion existing = pendingSuggestion("SUG-OLD", appointment, reserved);
        when(appointments.lockById("A1")).thenReturn(Optional.of(appointment));
        when(suggestions.existsByAppointmentAndTriggerReasonAndStatus(
                appointment, TriggerReason.PROVIDER_OFFLINE, SuggestionStatus.PENDING)).thenReturn(false);
        when(suggestions.findByStatusOrderByCreatedAtDesc(SuggestionStatus.PENDING))
                .thenReturn(List.of(existing));
        when(slots.findByStatusAndStartTimeAfterOrderByStartTimeAsc(
                eq(SlotStatus.OPEN), any(Instant.class)))
                .thenReturn(List.of(reserved, offlineSlot, originalProviderSlot, candidate));
        when(strategy.recommend(eq(appointment), eq(TriggerReason.PROVIDER_OFFLINE), anyList()))
                .thenReturn(List.of());

        assertTrue(service.generate(appointment, TriggerReason.PROVIDER_OFFLINE).isEmpty());

        org.mockito.ArgumentCaptor<List<Slot>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(strategy).recommend(eq(appointment), eq(TriggerReason.PROVIDER_OFFLINE), captor.capture());
        assertEquals(List.of(candidate), captor.getValue());
    }

    @Test
    void returnsEmptyWhenStrategyHasNoEligibleRecommendation() {
        stubGeneratePath();
        when(strategy.recommend(eq(appointment), eq(TriggerReason.PROVIDER_OFFLINE), anyList()))
                .thenReturn(List.of());

        assertTrue(service.generate(appointment, TriggerReason.PROVIDER_OFFLINE).isEmpty());

        verify(suggestions, never()).save(any());
    }

    @Test
    void retriesNextRankedCandidateWhenTheFirstCandidateWasBookedMeanwhile() {
        stubGeneratePath();
        Slot second = slot("S3", alternateProvider, baseTime.plusSeconds(600), SlotStatus.OPEN);
        when(strategy.recommend(eq(appointment), eq(TriggerReason.PROVIDER_OFFLINE), anyList()))
                .thenReturn(List.of(
                        new SchedulingStrategy.Recommendation(candidate, 0.9, "First choice", "RULE_BASED"),
                        new SchedulingStrategy.Recommendation(second, 0.8, "Second choice", "RULE_BASED")));
        doAnswer(invocation -> {
            Object entity = invocation.getArgument(0);
            if (entity == candidate) {
                candidate.setStatus(SlotStatus.BOOKED);
            }
            return null;
        }).when(entityManager).refresh(any(), eq(LockModeType.PESSIMISTIC_WRITE));
        when(suggestions.existsByRecommendedSlotAndStatus(second, SuggestionStatus.PENDING)).thenReturn(false);
        when(suggestions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<RescheduleSuggestion> result =
                service.generate(appointment, TriggerReason.PROVIDER_OFFLINE);

        assertTrue(result.isPresent());
        assertEquals("S3", result.get().getRecommendedSlot().getId());
        verify(entityManager).refresh(second, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void skipsCandidateRemovedBeforeRefreshAndUsesNextRankedCandidate() {
        stubGeneratePath();
        Slot second = slot("S3", alternateProvider, baseTime.plusSeconds(600), SlotStatus.OPEN);
        when(strategy.recommend(eq(appointment), eq(TriggerReason.PROVIDER_OFFLINE), anyList()))
                .thenReturn(List.of(
                        new SchedulingStrategy.Recommendation(candidate, 0.9, "First choice", "RULE_BASED"),
                        new SchedulingStrategy.Recommendation(second, 0.8, "Second choice", "RULE_BASED")));
        doThrow(new EntityNotFoundException("candidate removed"))
                .when(entityManager).refresh(candidate, LockModeType.PESSIMISTIC_WRITE);
        when(suggestions.existsByRecommendedSlotAndStatus(second, SuggestionStatus.PENDING)).thenReturn(false);
        when(suggestions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<RescheduleSuggestion> result =
                service.generate(appointment, TriggerReason.PROVIDER_OFFLINE);

        assertTrue(result.isPresent());
        assertEquals("S3", result.get().getRecommendedSlot().getId());
    }

    @Test
    void automaticProcessingMovesConfirmedAppointmentToReviewAndInvokesGeneration() {
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        when(appointments.lockById("A1")).thenReturn(Optional.of(appointment));
        when(suggestions.existsByAppointmentAndTriggerReasonAndStatus(
                appointment, TriggerReason.PROVIDER_OFFLINE, SuggestionStatus.PENDING)).thenReturn(true);

        service.processAutomatic("A1", TriggerReason.PROVIDER_OFFLINE);

        assertEquals(AppointmentStatus.RESCHEDULE_PENDING, appointment.getStatus());
        verify(appointments).save(appointment);
        verify(appointments, org.mockito.Mockito.times(2)).lockById("A1");
    }

    @Test
    void ignoresDelayedProviderOfflineEventWhenProviderIsBackOnline() {
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        originalProvider.setStatus(ProviderStatus.AVAILABLE);
        when(appointments.lockById("A1")).thenReturn(Optional.of(appointment));

        service.processAutomatic("A1", TriggerReason.PROVIDER_OFFLINE);

        assertEquals(AppointmentStatus.CONFIRMED, appointment.getStatus());
        verify(entityManager).refresh(originalProvider, LockModeType.PESSIMISTIC_WRITE);
        verify(appointments, never()).save(appointment);
        verify(appointments).lockById("A1");
        verify(strategy, never()).recommend(any(), any(), anyList());
    }

    @Test
    void automaticProcessingDoesNotResaveAnAppointmentAlreadyPending() {
        appointment.setStatus(AppointmentStatus.RESCHEDULE_PENDING);
        when(appointments.lockById("A1")).thenReturn(Optional.of(appointment));
        service.processAutomatic("A1", TriggerReason.PROVIDER_OFFLINE);

        verify(appointments, never()).save(appointment);
    }

    @Test
    void listsSuggestionsWithoutAStatusFilter() {
        List<RescheduleSuggestion> expected = List.of(pendingSuggestion("SUG-1", appointment, candidate));
        when(suggestions.findAll()).thenReturn(expected);

        assertSame(expected, service.list(null));
        verify(suggestions).findAll();
    }

    @Test
    void listsSuggestionsByStatusWhenFilterIsSupplied() {
        List<RescheduleSuggestion> expected = List.of(pendingSuggestion("SUG-1", appointment, candidate));
        when(suggestions.findByStatusOrderByCreatedAtDesc(SuggestionStatus.PENDING)).thenReturn(expected);

        assertSame(expected, service.list(SuggestionStatus.PENDING));
        verify(suggestions).findByStatusOrderByCreatedAtDesc(SuggestionStatus.PENDING);
    }

    @Test
    void rejectsAnUnsupportedDecisionAction() {
        assertEquals(
                "Action must be ACCEPT or REJECT",
                assertThrows(IllegalArgumentException.class,
                        () -> service.decide("SUG-1", "APPROVE", false)).getMessage());

        verify(suggestions, never()).findById("SUG-1");
    }

    @Test
    void rejectsAcceptanceWhenServiceIsNotGivenPatientConfirmation() {
        assertEquals(
                "Confirm the new appointment time with the patient before accepting",
                assertThrows(Conflict.class,
                        () -> service.decide("SUG-1", "ACCEPT", false)).getMessage());

        verify(suggestions, never()).findById("SUG-1");
    }

    @Test
    void throwsNotFoundWhenSuggestionDoesNotExist() {
        when(suggestions.findById("SUG-1")).thenReturn(Optional.empty());

        assertThrows(NotFound.class, () -> service.decide("SUG-1", "REJECT", false));
    }

    @Test
    void returnsConflictWhenAppointmentDisappearsWhileLockingForDecision() {
        RescheduleSuggestion suggestion = pendingSuggestion("SUG-1", appointment, candidate);
        when(suggestions.findById("SUG-1")).thenReturn(Optional.of(suggestion));
        doThrow(new EntityNotFoundException("appointment changed"))
                .when(entityManager).refresh(appointment, LockModeType.PESSIMISTIC_WRITE);

        assertThrows(Conflict.class, () -> service.decide("SUG-1", "REJECT", false));
    }

    @Test
    void returnsConflictWhenSuggestionDisappearsWhileLockingForDecision() {
        RescheduleSuggestion suggestion = pendingSuggestion("SUG-1", appointment, candidate);
        when(suggestions.findById("SUG-1")).thenReturn(Optional.of(suggestion));
        org.mockito.Mockito.lenient()
                .doThrow(new EntityNotFoundException("suggestion changed"))
                .when(entityManager).refresh(suggestion, LockModeType.PESSIMISTIC_WRITE);

        assertThrows(Conflict.class, () -> service.decide("SUG-1", "REJECT", false));
    }

    @Test
    void preventsDecidingAResolvedSuggestionAgain() {
        RescheduleSuggestion suggestion = pendingSuggestion("SUG-1", appointment, candidate);
        suggestion.setStatus(SuggestionStatus.ACCEPTED);
        when(suggestions.findById("SUG-1")).thenReturn(Optional.of(suggestion));

        assertThrows(Conflict.class, () -> service.decide("SUG-1", "REJECT", false));

        verify(suggestions, never()).save(any());
    }

    @Test
    void rejectsPendingSuggestionWithoutRefreshingDestinationSlot() {
        RescheduleSuggestion suggestion = pendingSuggestion("SUG-1", appointment, candidate);
        when(suggestions.findById("SUG-1")).thenReturn(Optional.of(suggestion));
        when(suggestions.save(suggestion)).thenReturn(suggestion);

        RescheduleSuggestion result = service.decide("SUG-1", "REJECT", false);

        assertSame(suggestion, result);
        assertEquals(SuggestionStatus.REJECTED, suggestion.getStatus());
        verify(entityManager, never()).refresh(candidate, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void preventsAcceptingSuggestionForFinalizedAppointment() {
        appointment.setStatus(AppointmentStatus.RESCHEDULED);
        RescheduleSuggestion suggestion = pendingSuggestion("SUG-1", appointment, candidate);
        when(suggestions.findById("SUG-1")).thenReturn(Optional.of(suggestion));

        assertThrows(Conflict.class, () -> service.decide("SUG-1", "ACCEPT", true));

        verify(entityManager, never()).refresh(candidate, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void reportsNotFoundWhenDestinationSlotIsMissing() {
        RescheduleSuggestion suggestion = pendingSuggestion("SUG-1", appointment, candidate);
        when(suggestions.findById("SUG-1")).thenReturn(Optional.of(suggestion));
        org.mockito.Mockito.lenient()
                .doThrow(new EntityNotFoundException("destination removed"))
                .when(entityManager).refresh(candidate, LockModeType.PESSIMISTIC_WRITE);

        assertThrows(NotFound.class, () -> service.decide("SUG-1", "ACCEPT", true));
    }

    @Test
    void preventsAcceptingWhenDestinationIsNoLongerOpen() {
        candidate.setStatus(SlotStatus.BOOKED);
        assertDestinationUnavailable();
    }

    @Test
    void preventsAcceptingWhenDestinationTimeHasPassed() {
        candidate = slot("S2", alternateProvider, Instant.now().minusSeconds(60), SlotStatus.OPEN);
        assertDestinationUnavailable();
    }

    @Test
    void preventsAcceptingWhenDestinationProviderIsOffline() {
        alternateProvider.setStatus(ProviderStatus.OFFLINE);
        assertDestinationUnavailable();
    }

    @Test
    void reportsNotFoundWhenOriginalSlotDisappearsDuringAcceptance() {
        RescheduleSuggestion suggestion = pendingSuggestion("SUG-1", appointment, candidate);
        when(suggestions.findById("SUG-1")).thenReturn(Optional.of(suggestion));
        org.mockito.Mockito.lenient()
                .doThrow(new EntityNotFoundException("original removed"))
                .when(entityManager).refresh(originalSlot, LockModeType.PESSIMISTIC_WRITE);

        assertThrows(NotFound.class, () -> service.decide("SUG-1", "ACCEPT", true));
    }

    private void assertDestinationUnavailable() {
        RescheduleSuggestion suggestion = pendingSuggestion("SUG-1", appointment, candidate);
        when(suggestions.findById("SUG-1")).thenReturn(Optional.of(suggestion));

        assertThrows(Conflict.class, () -> service.decide("SUG-1", "ACCEPT", true));

        verify(entityManager, never()).refresh(originalSlot, LockModeType.PESSIMISTIC_WRITE);
    }

    private void stubGeneratePath() {
        when(appointments.lockById("A1")).thenReturn(Optional.of(appointment));
        when(suggestions.findByStatusOrderByCreatedAtDesc(SuggestionStatus.PENDING))
                .thenReturn(List.of());
        when(slots.findByStatusAndStartTimeAfterOrderByStartTimeAsc(
                eq(SlotStatus.OPEN), any(Instant.class)))
                .thenReturn(List.of(candidate));
    }

    private Provider provider(String id, ProviderStatus status, Specialty specialty) {
        return new Provider(id, "Provider " + id, specialty, status, 0);
    }

    private Slot slot(String id, Provider provider, Instant start, SlotStatus status) {
        return new Slot(id, provider, start, 30, status, 0);
    }

    private RescheduleSuggestion pendingSuggestion(String id, Appointment appointment, Slot destination) {
        return new RescheduleSuggestion(
                id, appointment, destination, destination.getProvider(),
                appointment.getProvider().getName(), appointment.getSlot().getStartTime(),
                0.9, "A verified alternative slot is available.", "RULE_BASED",
                SuggestionStatus.PENDING, TriggerReason.INITIAL, Instant.now(), 0);
    }
}
