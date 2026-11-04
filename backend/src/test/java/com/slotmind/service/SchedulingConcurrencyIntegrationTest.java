package com.slotmind.service;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.entity.Slot;
import com.slotmind.enums.SlotStatus;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.SlotRepository;
import com.slotmind.repository.SuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:slotmind-concurrency;DB_CLOSE_DELAY=-1")
class SchedulingConcurrencyIntegrationTest {

    private static final String SHARED_DESTINATION_SLOT = "SLT-005";
    private static final String FIRST_SUGGESTION = "IT-CONCURRENT-SUG-001";
    private static final String SECOND_SUGGESTION = "IT-CONCURRENT-SUG-002";

    @Autowired
    private SchedulingService schedulingService;

    @Autowired
    private AppointmentRepository appointments;

    @Autowired
    private SlotRepository slots;

    @Autowired
    private SuggestionRepository suggestions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void createTwoPendingSuggestionsForTheSameDestination() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            Appointment firstAppointment = appointments.findById("APT-001").orElseThrow();
            Appointment secondAppointment = appointments.findById("APT-002").orElseThrow();
            Slot destination = slots.findById(SHARED_DESTINATION_SLOT).orElseThrow();

            assertEquals(SlotStatus.OPEN, destination.getStatus());

            suggestions.save(pendingSuggestion(FIRST_SUGGESTION, firstAppointment, destination));
            suggestions.save(pendingSuggestion(SECOND_SUGGESTION, secondAppointment, destination));
            suggestions.flush();
        });
    }

    @Test
    void concurrentAcceptancesCannotBookTheSameDestinationTwice() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Decision> first = executor.submit(
                    () -> acceptAfterStartSignal(FIRST_SUGGESTION, ready, start));
            Future<Decision> second = executor.submit(
                    () -> acceptAfterStartSignal(SECOND_SUGGESTION, ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both acceptance tasks should be ready");
            start.countDown();

            Decision firstDecision = first.get(10, TimeUnit.SECONDS);
            Decision secondDecision = second.get(10, TimeUnit.SECONDS);

            assertEquals(1, List.of(firstDecision, secondDecision).stream()
                    .filter(decision -> decision == Decision.ACCEPTED)
                    .count());
            assertEquals(1, List.of(firstDecision, secondDecision).stream()
                    .filter(decision -> decision == Decision.CONFLICTED)
                    .count());

            assertEquals(
                    SlotStatus.BOOKED,
                    slots.findById(SHARED_DESTINATION_SLOT).orElseThrow().getStatus());

            long rescheduledAppointments = List.of("APT-001", "APT-002").stream()
                    .map(id -> appointments.findById(id).orElseThrow())
                    .filter(appointment -> appointment.getStatus()
                            == com.slotmind.enums.AppointmentStatus.RESCHEDULED)
                    .count();
            assertEquals(1, rescheduledAppointments);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                    "Acceptance workers should shut down cleanly");
        }
    }

    private Decision acceptAfterStartSignal(
            String suggestionId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start concurrent acceptance");
        }

        try {
            schedulingService.decide(suggestionId, "ACCEPT", true);
            return Decision.ACCEPTED;
        } catch (Conflict conflict) {
            return Decision.CONFLICTED;
        }
    }

    private RescheduleSuggestion pendingSuggestion(
            String id, Appointment appointment, Slot destination) {
        return new RescheduleSuggestion(
                id,
                appointment,
                destination,
                destination.getProvider(),
                appointment.getProvider().getName(),
                appointment.getSlot().getStartTime(),
                0.9,
                "Concurrency test replacement recommendation.",
                "RULE_BASED",
                SuggestionStatus.PENDING,
                TriggerReason.INITIAL,
                Instant.now(),
                0);
    }

    private enum Decision {
        ACCEPTED,
        CONFLICTED
    }
}
