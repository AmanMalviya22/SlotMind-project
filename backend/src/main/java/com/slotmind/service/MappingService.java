package com.slotmind.service;

import com.slotmind.dto.AppointmentView;
import com.slotmind.dto.ProviderView;
import com.slotmind.dto.SlotView;
import com.slotmind.dto.SuggestionView;
import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.entity.Slot;
import org.springframework.stereotype.Component;

@Component
public class MappingService {

    public ProviderView provider(Provider provider) {
        return new ProviderView(
                provider.getId(),
                provider.getName(),
                provider.getSpecialty(),
                provider.getStatus());
    }

    public SlotView slot(Slot slot) {
        return new SlotView(
                slot.getId(),
                slot.getProvider().getId(),
                slot.getProvider().getName(),
                slot.getStartTime(),
                slot.getDurationMinutes(),
                slot.getStatus());
    }

    public AppointmentView appointment(Appointment appointment) {
        return new AppointmentView(
                appointment.getId(),
                appointment.getPatientName(),
                appointment.getType(),
                appointment.getProvider().getId(),
                appointment.getProvider().getName(),
                appointment.getSlot().getId(),
                appointment.getSlot().getStartTime(),
                appointment.getStatus());
    }

    public SuggestionView suggestion(RescheduleSuggestion suggestion) {
        return new SuggestionView(
                suggestion.getId(),
                suggestion.getAppointment().getId(),
                suggestion.getAppointment().getPatientName(),
                suggestion.getOriginalProviderName(),
                suggestion.getOriginalTime(),
                suggestion.getRecommendedSlot().getId(),
                suggestion.getRecommendedSlot().getProvider().getName(),
                suggestion.getRecommendedSlot().getStartTime(),
                suggestion.getConfidence(),
                suggestion.getReasoning(),
                suggestion.getStatus(),
                suggestion.getTriggerReason(),
                suggestion.getSource());
    }
}
