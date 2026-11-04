package com.slotmind.repository;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, String> {

    List<Appointment> findByStatus(AppointmentStatus status);

    List<Appointment> findByProviderAndStatus(Provider provider, AppointmentStatus status);

    List<Appointment> findByProvider_IdAndStatus(String providerId, AppointmentStatus status);

    Optional<Appointment> findBySlot(Slot slot);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> lockById(String id);
}
