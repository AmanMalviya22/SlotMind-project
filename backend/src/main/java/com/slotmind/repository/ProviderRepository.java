package com.slotmind.repository;

import com.slotmind.entity.Provider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ProviderRepository extends JpaRepository<Provider, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Provider p where p.id = :id")
    Optional<Provider> lockById(String id);
}
