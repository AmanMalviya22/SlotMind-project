package com.slotmind.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void returnsNotFoundMessage() {
        var response = handler.notFound(new ApiExceptions.NotFound("Appointment not found"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(Map.of("error", "Appointment not found"), response.getBody());
    }

    @Test
    void returnsConflictMessage() {
        var response = handler.conflict(new ApiExceptions.Conflict("Slot is already booked"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(Map.of("error", "Slot is already booked"), response.getBody());
    }

    @Test
    void hidesDatabaseConstraintDetails() {
        var response = handler.integrity();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(
                Map.of("error", "The operation conflicts with an existing record"),
                response.getBody());
    }

    @Test
    void returnsFieldLevelValidationErrors() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "patientName", "must not be blank"));
        Method method = ApiExceptionHandlerTest.class.getDeclaredMethod("sampleMethod", String.class);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new MethodParameter(method, 0), binding);

        var response = handler.invalid(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid request", response.getBody().get("error"));
        assertEquals(List.of("patientName: must not be blank"), response.getBody().get("details"));
    }

    @Test
    void returnsSafeMessageForMalformedRequestBody() {
        var exception = new HttpMessageNotReadableException(
                "invalid JSON", new MockHttpInputMessage(new byte[0]));

        var response = handler.unreadable();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", "Malformed request body"), response.getBody());
    }

    @Test
    void returnsIllegalArgumentMessage() {
        var response = handler.illegal(new IllegalArgumentException("Status is invalid"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", "Status is invalid"), response.getBody());
    }

    @Test
    void hidesUnexpectedServerExceptionDetails() {
        var response = handler.unexpected(new IllegalStateException("database password leaked"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(
                Map.of("error", "An unexpected error occurred"),
                response.getBody());
    }

    private void sampleMethod(String value) {
        // Used only to create a MethodParameter for validation error testing.
    }
}
