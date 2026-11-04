package com.slotmind.entity;

import com.slotmind.enums.SlotStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_slot_provider_start_time",
        columnNames = {"provider_id", "start_time"}))
public class Slot {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Provider provider;

    @Column(nullable = false)
    private Instant startTime;

    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    private SlotStatus status;

    @Version
    private long version;

    public Slot() {
    }

    public Slot(
            String id,
            Provider provider,
            Instant startTime,
            int durationMinutes,
            SlotStatus status,
            long version) {
        this.id = id;
        this.provider = provider;
        this.startTime = startTime;
        this.durationMinutes = durationMinutes;
        this.status = status;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public Provider getProvider() {
        return provider;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public SlotStatus getStatus() {
        return status;
    }

    public void setStatus(SlotStatus status) {
        this.status = status;
    }
}
