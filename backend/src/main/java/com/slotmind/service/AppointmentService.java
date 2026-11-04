package com.slotmind.service;

import com.slotmind.dto.CreateAppointment;
import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.SlotStatus;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.exception.ApiExceptions.NotFound;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.repository.SlotRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AppointmentService {

    private final AppointmentRepository appointments;
    private final ProviderRepository providers;
    private final SlotRepository slots;
    private final EntityManager entityManager;

    public AppointmentService(
            AppointmentRepository appointments,
            ProviderRepository providers,
            SlotRepository slots,
            EntityManager entityManager) {
        this.appointments = appointments;
        this.providers = providers;
        this.slots = slots;
        this.entityManager = entityManager;
    }

    @Transactional
    public Appointment create(CreateAppointment request) {
        // Lock in slot -> provider order, matching the rescheduling acceptance path.
        // A consistent lock order reduces deadlock risk when both flows race for a slot.
        Slot slot = slots.lockById(request.slotId())
                .orElseThrow(() -> new NotFound("Slot not found"));
        Provider provider = providers.lockById(request.providerId())
                .orElseThrow(() -> new NotFound("Provider not found"));
        entityManager.refresh(provider, LockModeType.PESSIMISTIC_WRITE);

        Instant now = Instant.now();
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw new Conflict("Slot is not open");
        }
        if (slot.getStartTime() == null || !slot.getStartTime().isAfter(now)) {
            throw new Conflict("Slot must be in the future");
        }
        if (!slot.getProvider().getId().equals(provider.getId())) {
            throw new Conflict("Slot does not belong to provider");
        }
        if (provider.getStatus() == ProviderStatus.OFFLINE) {
            throw new Conflict("Provider is offline");
        }

        slot.setStatus(SlotStatus.BOOKED);
        String appointmentId = "APT-" + UUID.randomUUID().toString().substring(0, 8);
        Appointment appointment = new Appointment(
                appointmentId,
                request.patientName().trim(),
                request.type(),
                provider,
                slot,
                AppointmentStatus.CONFIRMED,
                now,
                now,
                0);

        return appointments.save(appointment);
    }

    public Appointment findById(String id) {
        return appointments.findById(id)
                .orElseThrow(() -> new NotFound("Appointment not found"));
    }

    public List<Appointment> list(AppointmentStatus status) {
        return status == null
                ? appointments.findAll()
                : appointments.findByStatus(status);
    }
}
