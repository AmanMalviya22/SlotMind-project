package com.slotmind.controller;

import com.slotmind.dto.SuggestionAction;
import com.slotmind.dto.SuggestionView;
import com.slotmind.entity.RescheduleSuggestion;
import com.slotmind.enums.SuggestionStatus;
import com.slotmind.enums.TriggerReason;
import com.slotmind.exception.ApiExceptions.Conflict;
import com.slotmind.service.MappingService;
import com.slotmind.service.SchedulingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuggestionControllerContractTest {

    @Test
    void listsPendingSuggestionsThroughMappingService() {
        SchedulingService scheduling = mock(SchedulingService.class);
        MappingService mapper = mock(MappingService.class);
        RescheduleSuggestion entity = mock(RescheduleSuggestion.class);
        SuggestionView view = suggestionView();
        when(scheduling.list(SuggestionStatus.PENDING)).thenReturn(List.of(entity));
        when(mapper.suggestion(entity)).thenReturn(view);

        SuggestionController controller = new SuggestionController(scheduling, mapper);

        assertEquals(List.of(view), controller.suggestions(SuggestionStatus.PENDING));
        verify(scheduling).list(SuggestionStatus.PENDING);
        verify(mapper).suggestion(entity);
    }

    @Test
    void acceptsOnlyAfterExplicitPatientConfirmationAndDelegatesDecision() {
        SchedulingService scheduling = mock(SchedulingService.class);
        MappingService mapper = mock(MappingService.class);
        RescheduleSuggestion entity = mock(RescheduleSuggestion.class);
        SuggestionView view = suggestionView();
        when(scheduling.decide("SUG-1", "ACCEPT", true)).thenReturn(entity);
        when(mapper.suggestion(entity)).thenReturn(view);

        SuggestionController controller = new SuggestionController(scheduling, mapper);

        assertEquals(view, controller.decide(
                "SUG-1", new SuggestionAction(" accept ", Boolean.TRUE)));
        verify(scheduling).decide("SUG-1", "ACCEPT", true);
        verify(mapper).suggestion(entity);
    }

    @Test
    void rejectsRecommendationWithoutPatientConfirmation() {
        SchedulingService scheduling = mock(SchedulingService.class);
        SuggestionController controller =
                new SuggestionController(scheduling, mock(MappingService.class));

        assertEquals(
                "Confirm the new appointment time with the patient before accepting",
                assertThrows(
                        Conflict.class,
                        () -> controller.decide("SUG-1", new SuggestionAction("ACCEPT", false)))
                        .getMessage());
    }

    @Test
    void normalizesRejectActionAndDelegatesDecision() {
        SchedulingService scheduling = mock(SchedulingService.class);
        MappingService mapper = mock(MappingService.class);
        RescheduleSuggestion entity = mock(RescheduleSuggestion.class);
        SuggestionView view = suggestionView();
        when(scheduling.decide("SUG-1", "REJECT", false)).thenReturn(entity);
        when(mapper.suggestion(entity)).thenReturn(view);

        SuggestionController controller = new SuggestionController(scheduling, mapper);

        assertEquals(view, controller.decide(
                "SUG-1", new SuggestionAction(" reject ", false)));
        verify(scheduling).decide("SUG-1", "REJECT", false);
    }

    private SuggestionView suggestionView() {
        Instant original = Instant.parse("2099-11-04T09:00:00Z");
        return new SuggestionView(
                "SUG-1", "APT-1", "Aarav", "Dr. Rao", original,
                "SL-2", "Dr. Iyer", original.plusSeconds(3600), 0.9,
                "A matching slot is available.", SuggestionStatus.PENDING,
                TriggerReason.INITIAL, "AI");
    }
}
