package com.slotmind.repository;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.entity.Slot;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SuggestionRepository extends JpaRepository<RescheduleSuggestion, String> {

    List<RescheduleSuggestion> findByStatusOrderByCreatedAtDesc(SuggestionStatus status);

    boolean existsByAppointmentAndTriggerReasonAndStatus(
            Appointment appointment, TriggerReason reason, SuggestionStatus status);

    boolean existsByRecommendedSlotAndStatus(Slot slot, SuggestionStatus status);

    List<RescheduleSuggestion> findByAppointmentAndStatus(
            Appointment appointment, SuggestionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RescheduleSuggestion s where s.id = :id")
    Optional<RescheduleSuggestion> lockById(String id);
}
