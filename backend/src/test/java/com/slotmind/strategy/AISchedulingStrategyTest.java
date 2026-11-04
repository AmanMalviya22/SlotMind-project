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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AISchedulingStrategyTest {
    @Test
    void usesValidatedAiRecommendationFirstAndIncludesTriggerSpecificPromptData() {
        Fixture fixture = fixture();
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S2", "P2", .88, "Same specialty is available."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(fixture.appointment(), TriggerReason.PROVIDER_OFFLINE,
                                List.of(fixture.candidate()));

        assertFalse(recommendations.isEmpty());
        assertEquals("S2", recommendations.get(0).slot().getId());
        assertEquals("AI", recommendations.get(0).source());
        assertTrue(gateway.prompt.contains("PROVIDER_OFFLINE"));
        assertTrue(gateway.prompt.contains("\"originalProvider\""));
        assertTrue(gateway.prompt.contains("\"slotId\":\"S2\""));
        assertTrue(gateway.prompt.contains("\"urgency\":\"NORMAL\""));
        assertTrue(gateway.prompt.contains("\"providerId\":\"P2\""));
        assertTrue(gateway.prompt.contains("\"eligibleProviders\":"));
    }

    @Test
    void fallsBackWhenAiSelectsAnUnknownSlot() {
        Fixture fixture = fixture();
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("UNKNOWN", "P2", .88, "Invented."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(fixture.appointment(), TriggerReason.SLOT_CANCELLED,
                                List.of(fixture.candidate()));

        assertFalse(recommendations.isEmpty());
        assertEquals("S2", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
    }

    @Test
    void fallsBackWhenAiReturnsProviderThatDoesNotOwnSelectedSlot() {
        Fixture fixture = fixture();
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S2", "WRONG_PROVIDER", .88, "Invalid provider."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(fixture.appointment(), TriggerReason.SLOT_CANCELLED,
                                List.of(fixture.candidate()));

        assertFalse(recommendations.isEmpty());
        assertEquals("S2", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
    }

    @Test
    void fallsBackWhenAiReturnsZeroConfidence() {
        Fixture fixture = fixture();
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S2", "P2", 0.0, "Possible replacement."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(fixture.appointment(), TriggerReason.SLOT_CANCELLED,
                                List.of(fixture.candidate()));

        assertFalse(recommendations.isEmpty());
        assertEquals("S2", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
        assertEquals(0.92, recommendations.get(0).confidence());
    }

    @Test
    void fallsBackWhenAiExplanationContainsAnUnverifiedClockTime() {
        Fixture fixture = fixture();
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S2", "P2", 0.88, "The new time is 10:05 AM."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(fixture.appointment(), TriggerReason.SLOT_CANCELLED,
                                List.of(fixture.candidate()));

        assertFalse(recommendations.isEmpty());
        assertEquals("RULE_BASED", recommendations.get(0).source());
        assertEquals(0.92, recommendations.get(0).confidence());
    }

    @Test
    void placesRuleBasedAlternativesAfterValidatedAiChoice() {
        Fixture fixture = fixture();
        Instant laterTime = fixture.candidate().getStartTime().plusSeconds(1800);
        Slot laterCandidate = new Slot("S3", fixture.candidate().getProvider(),
                laterTime, 30, SlotStatus.OPEN, 0);
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S2", "P2", .88, "Selected by AI."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(fixture.appointment(), TriggerReason.SLOT_CANCELLED,
                                List.of(fixture.candidate(), laterCandidate));

        assertEquals(2, recommendations.size());
        assertEquals("S2", recommendations.get(0).slot().getId());
        assertEquals("AI", recommendations.get(0).source());
        assertEquals("S3", recommendations.get(1).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(1).source());
    }

    @Test
    void fallsBackWhenAiSkipsAnAvailableSameProviderSlot() {
        Instant time = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Provider alternate = new Provider("P2", "Alternate", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot originalSlot = new Slot("S1", original, time, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, originalSlot, AppointmentStatus.CONFIRMED, Instant.now(), Instant.now(), 0);
        Slot sameProvider = new Slot("S3", original, time.plusSeconds(600), 30, SlotStatus.OPEN, 0);
        Slot alternateProvider = new Slot("S2", alternate, time.plusSeconds(300), 30, SlotStatus.OPEN, 0);
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S2", "P2", .88, "An alternate is available."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(appointment, TriggerReason.INITIAL,
                                List.of(alternateProvider, sameProvider));

        assertEquals("S3", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
    }

    @Test
    void fallsBackWhenAiSkipsAnAvailableMatchingSpecialty() {
        Instant time = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.OFFLINE, 0);
        Provider matching = new Provider("P2", "Matching", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Provider different = new Provider("P3", "Different", Specialty.SPECIALIST, ProviderStatus.AVAILABLE, 0);
        Slot originalSlot = new Slot("S1", original, time, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, originalSlot, AppointmentStatus.RESCHEDULE_PENDING, Instant.now(), Instant.now(), 0);
        Slot matchingSlot = new Slot("S2", matching, time.plusSeconds(600), 30, SlotStatus.OPEN, 0);
        Slot differentSlot = new Slot("S3", different, time.plusSeconds(300), 30, SlotStatus.OPEN, 0);
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S3", "P3", .88, "A different specialty is available."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(appointment, TriggerReason.PROVIDER_OFFLINE,
                                List.of(differentSlot, matchingSlot));

        assertEquals("S2", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
    }

    @Test
    void acceptsSameProviderRecommendationOnlyWhenAlternateProviderIdIsNull() {
        Instant time = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot booked = new Slot("S1", original, time, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, booked, AppointmentStatus.CONFIRMED, Instant.now(), Instant.now(), 0);
        Slot sameProvider = new Slot("S3", original, time.plusSeconds(600), 30, SlotStatus.OPEN, 0);
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S3", null, .88, "The original provider has an open slot."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(appointment, TriggerReason.INITIAL, List.of(sameProvider));

        assertEquals("S3", recommendations.get(0).slot().getId());
        assertEquals("AI", recommendations.get(0).source());
    }

    @Test
    void fallsBackWhenAlternateProviderIdIsMissingForAnAlternateSlot() {
        Fixture fixture = fixture();
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S2", null, .88, "An alternate provider is available."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(fixture.appointment(), TriggerReason.PROVIDER_OFFLINE,
                                List.of(fixture.candidate()));

        assertEquals("S2", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
    }

    @Test
    void fallsBackWhenSameProviderSlotIsReportedAsAnAlternateProvider() {
        Instant time = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot booked = new Slot("S1", original, time, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, booked, AppointmentStatus.CONFIRMED, Instant.now(), Instant.now(), 0);
        Slot sameProvider = new Slot("S3", original, time.plusSeconds(600), 30, SlotStatus.OPEN, 0);
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S3", "P1", .88, "The original provider has an open slot."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(appointment, TriggerReason.INITIAL, List.of(sameProvider));

        assertEquals("S3", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
    }

    @Test
    void fallsBackWhenConfidenceIsBelowTheDocumentedMinimum() {
        Fixture fixture = fixture();
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S2", "P2", .005, "A possible replacement is available."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(fixture.appointment(), TriggerReason.PROVIDER_OFFLINE,
                                List.of(fixture.candidate()));

        assertEquals("RULE_BASED", recommendations.get(0).source());
    }

    @Test
    void acceptsAiChoiceWithinThePreferredSameProviderGroupWhenDurationMatches() {
        Instant time = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot booked = new Slot("S1", original, time, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, booked, AppointmentStatus.CONFIRMED, Instant.now(), Instant.now(), 0);
        Slot later = new Slot("S3", original, time.plusSeconds(1800), 30, SlotStatus.OPEN, 0);
        Slot earlier = new Slot("S2", original, time.plusSeconds(600), 45, SlotStatus.OPEN, 0);
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S3", null, .88, "The original provider has availability."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(appointment, TriggerReason.INITIAL, List.of(later, earlier));

        assertEquals("S3", recommendations.get(0).slot().getId());
        assertEquals("AI", recommendations.get(0).source());
    }

    @Test
    void acceptsAiChoiceWithinThePreferredSpecialtyGroupWhenDurationMatches() {
        Instant time = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.OFFLINE, 0);
        Provider alternate = new Provider("P2", "Alternate", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot booked = new Slot("S1", original, time, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, booked, AppointmentStatus.RESCHEDULE_PENDING, Instant.now(), Instant.now(), 0);
        Slot later = new Slot("S3", alternate, time.plusSeconds(1800), 30, SlotStatus.OPEN, 0);
        Slot earlier = new Slot("S2", alternate, time.plusSeconds(600), 45, SlotStatus.OPEN, 0);
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S3", "P2", .88, "A matching specialty is available."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(appointment, TriggerReason.PROVIDER_OFFLINE, List.of(later, earlier));

        assertEquals("S3", recommendations.get(0).slot().getId());
        assertEquals("AI", recommendations.get(0).source());
    }

    @Test
    void fallsBackToStableSlotIdWhenCandidateTimesAreEqual() {
        Instant time = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot booked = new Slot("S1", original, time, 30, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, booked, AppointmentStatus.CONFIRMED, Instant.now(), Instant.now(), 0);
        Slot lexicallyLater = new Slot("S-2", original, time.plusSeconds(600), 30, SlotStatus.OPEN, 0);
        Slot lexicallyEarlier = new Slot("S-1", original, time.plusSeconds(600), 30, SlotStatus.OPEN, 0);
        FakeGateway gateway = new FakeGateway(
                new LLMGateway.Recommendation("S-2", null, .88, "The original provider has availability."));

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(gateway, new RuleBasedSchedulingStrategy())
                        .recommend(appointment, TriggerReason.INITIAL,
                                List.of(lexicallyLater, lexicallyEarlier));

        assertEquals("S-1", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
    }

    @Test
    void fallsBackWhenGatewayThrows() {
        Fixture fixture = fixture();
        LLMGateway unavailableGateway = (appointment, reason, candidates) -> {
            throw new IllegalStateException("Simulated gateway outage");
        };

        List<SchedulingStrategy.Recommendation> recommendations =
                new AISchedulingStrategy(unavailableGateway, new RuleBasedSchedulingStrategy())
                        .recommend(fixture.appointment(), TriggerReason.PROVIDER_OFFLINE,
                                List.of(fixture.candidate()));

        assertEquals("S2", recommendations.get(0).slot().getId());
        assertEquals("RULE_BASED", recommendations.get(0).source());
    }

    private static Fixture fixture() {
        Instant time = Instant.now().plusSeconds(3600);
        Provider original = new Provider("P1", "Original", Specialty.GENERAL, ProviderStatus.OFFLINE, 0);
        Provider alternate = new Provider("P2", "Alternate", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot originalSlot = new Slot("S1", original, time, 30, SlotStatus.BOOKED, 0);
        Slot candidate = new Slot("S2", alternate, time.plusSeconds(600), 30, SlotStatus.OPEN, 0);
        Appointment appointment = new Appointment("A1", "Patient", AppointmentType.ROUTINE,
                original, originalSlot, AppointmentStatus.RESCHEDULE_PENDING, Instant.now(), Instant.now(), 0);
        return new Fixture(appointment, candidate);
    }

    private record Fixture(Appointment appointment, Slot candidate) {
    }

    private static class FakeGateway implements LLMGateway {
        private final Recommendation response;
        private String prompt = "";

        private FakeGateway(Recommendation response) {
            this.response = response;
        }

        @Override
        public Optional<Recommendation> recommend(
                Appointment appointment, TriggerReason reason, List<Slot> candidates) {
            prompt = LiteLLMGateway.prompt(appointment, reason, candidates);
            return Optional.of(response);
        }
    }
}
