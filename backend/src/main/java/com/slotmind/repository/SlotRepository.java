package com.slotmind.repository;

import com.slotmind.entity.Provider;
import com.slotmind.entity.Slot;
import com.slotmind.enums.SlotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SlotRepository extends JpaRepository<Slot, String> {

    List<Slot> findByStatusAndStartTimeAfterOrderByStartTimeAsc(SlotStatus status, Instant time);

    boolean existsByProviderAndStartTime(Provider provider, Instant startTime);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.id = :id")
    Optional<Slot> lockById(String id);
}
