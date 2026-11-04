package com.slotmind.service;

import com.slotmind.entity.Appointment;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.entity.Slot;
import com.slotmind.enums.AppointmentStatus;
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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:slotmind-sibling-concurrency;DB_CLOSE_DELAY=-1")
class SchedulingSiblingConcurrencyIntegrationTest {

    private static final String APPOINTMENT_ID = "APT-001";
    private static final String FIRST_DESTINATION_ID = "SLT-005";
    private static final String SECOND_DESTINATION_ID = "IT-SLT-SIBLING-002";
    private static final String FIRST_SUGGESTION_ID = "IT-SIBLING-SUG-001";
    private static final String SECOND_SUGGESTION_ID = "IT-SIBLING-SUG-002";

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
    void createSiblingSuggestionsForOneAppointment() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            Appointment appointment = appointments.findById(APPOINTMENT_ID).orElseThrow();
            Slot firstDestination = slots.findById(FIRST_DESTINATION_ID).orElseThrow();
            Slot secondDestination = new Slot(
                    SECOND_DESTINATION_ID,
                    firstDestination.getProvider(),
                    firstDestination.getStartTime().plusSeconds(7_200),
                    30,
                    SlotStatus.OPEN,
                    0);
            slots.saveAndFlush(secondDestination);

            suggestions.save(pendingSuggestion(
                    FIRST_SUGGESTION_ID,
                    appointment,
                    firstDestination,
                    TriggerReason.INITIAL));
            suggestions.save(pendingSuggestion(
                    SECOND_SUGGESTION_ID,
                    appointment,
                    secondDestination,
                    TriggerReason.SLOT_CANCELLED));
            suggestions.flush();
        });
    }

    @Test
    void concurrentAcceptanceOfSiblingSuggestionsFinalizesAppointmentOnlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Decision> first = executor.submit(
                    () -> acceptAfterStartSignal(FIRST_SUGGESTION_ID, ready, start));
            Future<Decision> second = executor.submit(
                    () -> acceptAfterStartSignal(SECOND_SUGGESTION_ID, ready, start));

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

            Appointment updatedAppointment =
                    appointments.findById(APPOINTMENT_ID).orElseThrow();
            Slot firstDestination = slots.findById(FIRST_DESTINATION_ID).orElseThrow();
            Slot secondDestination = slots.findById(SECOND_DESTINATION_ID).orElseThrow();
            RescheduleSuggestion firstSuggestion =
                    suggestions.findById(FIRST_SUGGESTION_ID).orElseThrow();
            RescheduleSuggestion secondSuggestion =
                    suggestions.findById(SECOND_SUGGESTION_ID).orElseThrow();

            assertEquals(AppointmentStatus.RESCHEDULED, updatedAppointment.getStatus());
            assertTrue(
                    (firstDestination.getStatus() == SlotStatus.BOOKED
                            && secondDestination.getStatus() == SlotStatus.OPEN)
                            || (firstDestination.getStatus() == SlotStatus.OPEN
                            && secondDestination.getStatus() == SlotStatus.BOOKED));
            assertTrue(
                    (firstSuggestion.getStatus() == SuggestionStatus.ACCEPTED
                            && secondSuggestion.getStatus() == SuggestionStatus.REJECTED)
                            || (firstSuggestion.getStatus() == SuggestionStatus.REJECTED
                            && secondSuggestion.getStatus() == SuggestionStatus.ACCEPTED));
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
            String id,
            Appointment appointment,
            Slot destination,
            TriggerReason reason) {
        return new RescheduleSuggestion(
                id,
                appointment,
                destination,
                destination.getProvider(),
                appointment.getProvider().getName(),
                appointment.getSlot().getStartTime(),
                0.9,
                "Concurrent sibling recommendation for an available slot.",
                "RULE_BASED",
                SuggestionStatus.PENDING,
                reason,
                Instant.now(),
                0);
    }

    private enum Decision {
        ACCEPTED,
        CONFLICTED
    }
}
