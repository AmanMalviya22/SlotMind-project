package com.slotmind.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.Specialty;
import com.slotmind.enums.TriggerReason;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("live-llm")
@EnabledIfEnvironmentVariable(named = "SLOTMIND_LLM_LIVE_TESTS", matches = "true")
class LiteLLMGatewayLiveTest {

    @Test
    void liveModelFollowsTheDocumentedPriorityAcrossRepresentativeScenarios() {
        StandardEnvironment environment = new StandardEnvironment();
        String apiKey = environment.getProperty("SLOTMIND_LLM_API_KEY", "");
        assertFalse(apiKey.isBlank(), "Set SLOTMIND_LLM_API_KEY before enabling the live test.");

        AISchedulingStrategy strategy = new AISchedulingStrategy(
                new LiteLLMGateway(RestClient.builder(), new ObjectMapper(), environment),
                new RuleBasedSchedulingStrategy());

        Instant base = Instant.now().plusSeconds(2 * 24 * 60 * 60L);

        // An earlier slot from another specialty must not outrank the matching specialty.
        Provider offline = provider("LIVE-P1", Specialty.GENERAL, ProviderStatus.OFFLINE);
        Provider specialist = provider("LIVE-P2", Specialty.SPECIALIST, ProviderStatus.AVAILABLE);
        Provider matching = provider("LIVE-P3", Specialty.GENERAL, ProviderStatus.AVAILABLE);
        Appointment offlineAppointment = appointment("LIVE-A1", offline, base);
        assertAiChoice(
                strategy.recommend(offlineAppointment, TriggerReason.PROVIDER_OFFLINE, List.of(
                        openSlot("LIVE-S1", specialist, base),
                        openSlot("LIVE-S2", matching, base.plusSeconds(3600)))),
                "LIVE-S2",
                "provider-offline prefers the original specialty over an earlier different specialty");

        // INITIAL must prefer an eligible opening with the original provider.
        Provider available = provider("LIVE-P4", Specialty.GENERAL, ProviderStatus.AVAILABLE);
        Provider other = provider("LIVE-P5", Specialty.GENERAL, ProviderStatus.AVAILABLE);
        Appointment initialAppointment = appointment("LIVE-A2", available, base);
        assertAiChoice(
                strategy.recommend(initialAppointment, TriggerReason.INITIAL, List.of(
                        openSlot("LIVE-S3", other, base.plusSeconds(1800)),
                        openSlot("LIVE-S4", available, base.plusSeconds(3600)))),
                "LIVE-S4",
                "initial suggestions prefer the original provider when an eligible slot exists");

        // SLOT_CANCELLED follows the same original-provider preference where possible.
        Slot cancelledOriginal = new Slot(
                "LIVE-S5", available, base.minusSeconds(3600), 30, SlotStatus.CANCELLED, 0);
        Appointment cancelledAppointment = new Appointment(
                "LIVE-A3", "Demo Patient", AppointmentType.ROUTINE, available, cancelledOriginal,
                AppointmentStatus.RESCHEDULE_PENDING, Instant.now(), Instant.now(), 0);
        assertAiChoice(
                strategy.recommend(cancelledAppointment, TriggerReason.SLOT_CANCELLED, List.of(
                        openSlot("LIVE-S6", other, base.plusSeconds(900)),
                        openSlot("LIVE-S7", available, base.plusSeconds(1800)))),
                "LIVE-S7",
                "slot-cancelled prefers another eligible slot from the original provider");

        // Within the preferred specialty group, choose the earliest available opening.
        Provider matchingLater = provider("LIVE-P6", Specialty.GENERAL, ProviderStatus.AVAILABLE);
        Provider matchingEarlier = provider("LIVE-P7", Specialty.GENERAL, ProviderStatus.AVAILABLE);
        Provider differentEarlier = provider("LIVE-P8", Specialty.PEDIATRIC, ProviderStatus.AVAILABLE);
        Appointment priorityAppointment = appointment("LIVE-A4", offline, base);
        assertAiChoice(
                strategy.recommend(priorityAppointment, TriggerReason.PROVIDER_OFFLINE, List.of(
                        openSlot("LIVE-S8", differentEarlier, base),
                        openSlot("LIVE-S9", matchingLater, base.plusSeconds(7200)),
                        openSlot("LIVE-S10", matchingEarlier, base.plusSeconds(3600)))),
                "LIVE-S10",
                "earliest matching-specialty slot wins inside the preferred group");

        // Equal start times use the stable lexicographically smaller slot ID.
        Appointment tieAppointment = appointment("LIVE-A5", available, base);
        assertAiChoice(
                strategy.recommend(tieAppointment, TriggerReason.INITIAL, List.of(
                        openSlot("LIVE-S-Z", available, base.plusSeconds(3600)),
                        openSlot("LIVE-S-A", available, base.plusSeconds(3600)))),
                "LIVE-S-A",
                "equal-time candidates use the stable slot-ID tie-break");
    }

    private void assertAiChoice(
            List<SchedulingStrategy.Recommendation> recommendations,
            String expectedSlotId,
            String scenario) {
        assertFalse(recommendations.isEmpty(), scenario + ": no recommendation was returned");
        assertEquals("AI", recommendations.get(0).source(),
                scenario + ": the model response did not pass backend validation");
        assertEquals(expectedSlotId, recommendations.get(0).slot().getId(), scenario);
    }

    private static Appointment appointment(String id, Provider provider, Instant base) {
        Slot original = new Slot(
                id + "-ORIGINAL", provider, base.minusSeconds(3600), 30, SlotStatus.BOOKED, 0);
        return new Appointment(
                id, "Demo Patient", AppointmentType.ROUTINE, provider, original,
                AppointmentStatus.RESCHEDULE_PENDING, Instant.now(), Instant.now(), 0);
    }

    private static Provider provider(String id, Specialty specialty, ProviderStatus status) {
        return new Provider(id, "Demo Provider " + id, specialty, status, 0);
    }

    private static Slot openSlot(String id, Provider provider, Instant start) {
        return new Slot(id, provider, start, 30, SlotStatus.OPEN, 0);
    }
}
