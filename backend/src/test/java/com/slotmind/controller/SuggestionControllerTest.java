package com.slotmind.controller;

import com.slotmind.dto.SuggestionAction;
import com.slotmind.exception.ApiExceptions.Conflict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SuggestionControllerTest {

    @Test
    void acceptingSuggestionRequiresPatientConfirmation() {
        SuggestionController controller = new SuggestionController(null, null);

        assertThrows(
                Conflict.class,
                () -> controller.decide("SUG-1", new SuggestionAction("ACCEPT")));
    }
}
