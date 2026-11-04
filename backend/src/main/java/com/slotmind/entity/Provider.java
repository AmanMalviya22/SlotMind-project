package com.slotmind.entity;

import com.slotmind.enums.ProviderStatus;
import com.slotmind.enums.Specialty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

@Entity
public class Provider {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private Specialty specialty;

    @Enumerated(EnumType.STRING)
    private ProviderStatus status;

    @Version
    private long version;

    public Provider() {
    }

    public Provider(
            String id,
            String name,
            Specialty specialty,
            ProviderStatus status,
            long version) {
        this.id = id;
        this.name = name;
        this.specialty = specialty;
        this.status = status;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Specialty getSpecialty() {
        return specialty;
    }

    public ProviderStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public void setStatus(ProviderStatus status) {
        this.status = status;
    }
}
