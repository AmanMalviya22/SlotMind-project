package com.slotmind.entity;

import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
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
        name = "uk_pending_suggestion",
        columnNames = {"pending_key"}))
public class RescheduleSuggestion {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Slot recommendedSlot;

    @ManyToOne(fetch = FetchType.EAGER)
    private Provider alternateProvider;

    private String originalProviderName;

    private Instant originalTime;

    private double confidence;

    @Column(length = 1000)
    private String reasoning;

    private String source;

    @Enumerated(EnumType.STRING)
    private SuggestionStatus status;

    @Enumerated(EnumType.STRING)
    private TriggerReason triggerReason;

    @Column(name = "pending_key")
    private String pendingKey;

    private Instant createdAt;

    @Version
    private long version;

    public RescheduleSuggestion() {
    }

    public RescheduleSuggestion(
            String id,
            Appointment appointment,
            Slot recommendedSlot,
            Provider alternateProvider,
            String originalProviderName,
            Instant originalTime,
            double confidence,
            String reasoning,
            String source,
            SuggestionStatus status,
            TriggerReason triggerReason,
            Instant createdAt,
            long version) {
        this.id = id;
        this.appointment = appointment;
        this.recommendedSlot = recommendedSlot;
        this.alternateProvider = alternateProvider;
        this.originalProviderName = originalProviderName;
        this.originalTime = originalTime;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.source = source;
        this.status = status;
        this.triggerReason = triggerReason;
        this.createdAt = createdAt;
        this.version = version;
        refreshPendingKey();
    }

    public String getId() {
        return id;
    }

    public Appointment getAppointment() {
        return appointment;
    }

    public Slot getRecommendedSlot() {
        return recommendedSlot;
    }

    public Provider getAlternateProvider() {
        return alternateProvider;
    }

    public String getOriginalProviderName() {
        return originalProviderName;
    }

    public Instant getOriginalTime() {
        return originalTime;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReasoning() {
        return reasoning;
    }

    public String getSource() {
        return source;
    }

    public SuggestionStatus getStatus() {
        return status;
    }

    public TriggerReason getTriggerReason() {
        return triggerReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setStatus(SuggestionStatus status) {
        this.status = status;
        refreshPendingKey();
    }

    private void refreshPendingKey() {
        pendingKey = status == SuggestionStatus.PENDING
                ? appointment.getId() + ":" + triggerReason.name()
                : null;
    }
}
