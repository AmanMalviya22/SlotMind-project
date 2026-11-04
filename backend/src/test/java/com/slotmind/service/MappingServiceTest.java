package com.slotmind.service;

import com.slotmind.dto.AppointmentView;
import com.slotmind.dto.ProviderView;
import com.slotmind.dto.SlotView;
import com.slotmind.dto.SuggestionView;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MappingServiceTest {

    private final MappingService mapper = new MappingService();

    @Test
    void mapsProviderFields() {
        Provider provider = provider();

        assertEquals(
                new ProviderView("P-1", "Dr. Rao", Specialty.GENERAL, ProviderStatus.AVAILABLE),
                mapper.provider(provider));
    }

    @Test
    void mapsSlotFieldsAndProviderDetails() {
        Instant start = Instant.parse("2026-11-04T09:30:00Z");
        Slot slot = new Slot("SL-1", provider(), start, 45, SlotStatus.OPEN, 0);

        assertEquals(
                new SlotView("SL-1", "P-1", "Dr. Rao", start, 45, SlotStatus.OPEN),
                mapper.slot(slot));
    }

    @Test
    void mapsAppointmentFieldsAndRelatedEntities() {
        Instant start = Instant.parse("2026-11-04T09:30:00Z");
        Provider provider = provider();
        Slot slot = new Slot("SL-1", provider, start, 45, SlotStatus.BOOKED, 0);
        Appointment appointment = new Appointment(
                "APT-1", "Aarav", AppointmentType.ROUTINE, provider, slot,
                AppointmentStatus.CONFIRMED, start.minusSeconds(600), start.minusSeconds(600), 0);

        assertEquals(
                new AppointmentView(
                        "APT-1", "Aarav", AppointmentType.ROUTINE, "P-1", "Dr. Rao",
                        "SL-1", start, AppointmentStatus.CONFIRMED),
                mapper.appointment(appointment));
    }

    @Test
    void mapsSuggestionFieldsAndRecommendedProviderDetails() {
        Instant original = Instant.parse("2026-11-04T09:30:00Z");
        Instant recommended = Instant.parse("2026-11-04T10:30:00Z");
        Provider originalProvider = provider();
        Provider replacementProvider = new Provider(
                "P-2", "Dr. Iyer", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
        Slot originalSlot = new Slot(
                "SL-1", originalProvider, original, 45, SlotStatus.CANCELLED, 0);
        Slot replacementSlot = new Slot(
                "SL-2", replacementProvider, recommended, 45, SlotStatus.OPEN, 0);
        Appointment appointment = new Appointment(
                "APT-1", "Aarav", AppointmentType.ROUTINE, originalProvider, originalSlot,
                AppointmentStatus.RESCHEDULE_PENDING, original.minusSeconds(600),
                original.minusSeconds(600), 0);
        RescheduleSuggestion suggestion = new RescheduleSuggestion(
                "SUG-1", appointment, replacementSlot, replacementProvider,
                "Dr. Rao", original, 0.91, "Matching specialty and earliest available time.",
                "AI", SuggestionStatus.PENDING, TriggerReason.SLOT_CANCELLED, original, 0);

        assertEquals(
                new SuggestionView(
                        "SUG-1", "APT-1", "Aarav", "Dr. Rao", original,
                        "SL-2", "Dr. Iyer", recommended, 0.91,
                        "Matching specialty and earliest available time.",
                        SuggestionStatus.PENDING, TriggerReason.SLOT_CANCELLED, "AI"),
                mapper.suggestion(suggestion));
    }

    private Provider provider() {
        return new Provider("P-1", "Dr. Rao", Specialty.GENERAL, ProviderStatus.AVAILABLE, 0);
    }
}
