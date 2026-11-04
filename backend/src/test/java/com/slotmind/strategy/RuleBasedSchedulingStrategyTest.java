package com.slotmind.strategy;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.enums.TriggerReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedSchedulingStrategyTest {
    private final RuleBasedSchedulingStrategy strategy = new RuleBasedSchedulingStrategy();

    @Test
    void prefersMatchingSpecialtyAndExcludesOfflineOrCancelledCandidates() {
        Instant originalTime = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.OFFLINE, 0);
        Provider matching = new Provider("P2", "Matching", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Provider offline = new Provider("P3", "Offline", Specialty.GENERAL, ProviderStatus.OFFLINE, 0);
        Slot originalSlot = new Slot("S1", original, originalTime, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, originalSlot, AppointmentStatus.RESCHEDULE_PENDING, Instant.now(), Instant.now(), 0);
        Slot cancelled = new Slot("S2", matching, originalTime.plusSeconds(300), 30, SlotStatus.CANCELLED, 0);
        Slot offlineSlot = new Slot("S3", offline, originalTime.plusSeconds(600), 30, SlotStatus.OPEN, 0);
        Slot valid = new Slot("S4", matching, originalTime.plusSeconds(900), 30, SlotStatus.OPEN, 0);

        List<SchedulingStrategy.Recommendation> recommendations = strategy.recommend(
                appointment, TriggerReason.PROVIDER_OFFLINE, List.of(cancelled, offlineSlot, valid));

        assertFalse(recommendations.isEmpty());
        assertEquals("S4", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
        assertEquals(1, recommendations.size());
    }

    @Test
    void prioritizesEarliestMatchingSpecialtyForUrgentAppointments() {
        Instant originalTime = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Provider otherSpecialty = new Provider("P2", "Other", Specialty.PEDIATRIC, ProviderStatus.AVAILABLE, 0);
        Provider matchingSpecialty = new Provider("P3", "Matching", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot originalSlot = new Slot("S1", original, originalTime, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.URGENT,
                original, originalSlot, AppointmentStatus.CONFIRMED, Instant.now(), Instant.now(), 0);
        Slot earlierOtherSpecialty = new Slot(
                "S2", otherSpecialty, originalTime.plusSeconds(300), 30, SlotStatus.OPEN, 0);
        Slot laterMatchingSpecialty = new Slot(
                "S3", matchingSpecialty, originalTime.plusSeconds(900), 30, SlotStatus.OPEN, 0);

        List<SchedulingStrategy.Recommendation> recommendations = strategy.recommend(
                appointment, TriggerReason.INITIAL,
                List.of(earlierOtherSpecialty, laterMatchingSpecialty));

        assertEquals("S3", recommendations.get(0).slot().getId());
        assertTrue(recommendations.get(0).reasoning().contains("matching specialty"));
    }

    @Test
    void ranksNearestSameProviderSlotBeforeEarlierAlternativeForManualRequest() {
        Instant originalTime = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.BUSY, 0);
        Provider alternative = new Provider("P2", "Alternative", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot originalSlot = new Slot("S1", original, originalTime, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, originalSlot, AppointmentStatus.CONFIRMED, Instant.now(), Instant.now(), 0);
        Slot earlierAlternative = new Slot(
                "S2", alternative, originalTime.plusSeconds(300), 30, SlotStatus.OPEN, 0);
        Slot laterSameProvider = new Slot(
                "S3", original, originalTime.plusSeconds(600), 30, SlotStatus.OPEN, 0);

        List<SchedulingStrategy.Recommendation> recommendations = strategy.recommend(
                appointment, TriggerReason.INITIAL, List.of(earlierAlternative, laterSameProvider));

        assertEquals(2, recommendations.size());
        assertEquals("S3", recommendations.get(0).slot().getId());
        assertTrue(recommendations.get(0).reasoning().contains("same provider"));
        assertEquals("S2", recommendations.get(1).slot().getId());
    }

    @Test
    void prefersSameProviderWhenCancelledSlotHasAnotherAvailableSameSpecialtyProvider() {
        Instant originalTime = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.BUSY, 0);
        Provider alternative = new Provider("P2", "Alternative", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot originalSlot = new Slot("S1", original, originalTime, 30, SlotStatus.CANCELLED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, originalSlot, AppointmentStatus.RESCHEDULE_PENDING, Instant.now(), Instant.now(), 0);
        Slot earlierAlternative = new Slot(
                "S2", alternative, originalTime.plusSeconds(300), 30, SlotStatus.OPEN, 0);
        Slot laterSameProvider = new Slot(
                "S3", original, originalTime.plusSeconds(600), 30, SlotStatus.OPEN, 0);

        List<SchedulingStrategy.Recommendation> recommendations = strategy.recommend(
                appointment, TriggerReason.SLOT_CANCELLED, List.of(earlierAlternative, laterSameProvider));

        assertFalse(recommendations.isEmpty());
        assertEquals("S3", recommendations.get(0).slot().getId());
    }
}
