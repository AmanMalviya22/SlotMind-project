package com.slotmind.entity;

import com.slotmind.enums.AppointmentStatus;
import com.slotmind.enums.AppointmentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
public class Appointment {

    @Id
    private String id;

    @Column(nullable = false)
    private String patientName;

    @Enumerated(EnumType.STRING)
    private AppointmentType type;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Provider provider;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Slot slot;

    @Enumerated(EnumType.STRING)
    private AppointmentStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private long version;

    public Appointment() {
    }

    public Appointment(
            String id,
            String patientName,
            AppointmentType type,
            Provider provider,
            Slot slot,
            AppointmentStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = id;
        this.patientName = patientName;
        this.type = type;
        this.provider = provider;
        this.slot = slot;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public String getPatientName() {
        return patientName;
    }

    public AppointmentType getType() {
        return type;
    }

    public Provider getProvider() {
        return provider;
    }

    public Slot getSlot() {
        return slot;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public void setStatus(AppointmentStatus status) {
        this.status = status;
        touch();
    }

    public void setSlot(Slot slot) {
        this.slot = slot;
        touch();
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
