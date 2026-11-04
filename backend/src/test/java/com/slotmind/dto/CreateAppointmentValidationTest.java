package com.slotmind.dto;

import com.slotmind.enums.AppointmentType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateAppointmentValidationTest {

    @Test
    void rejectsPatientNamesLongerThan120Characters() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            CreateAppointment request = new CreateAppointment(
                    "A".repeat(121), AppointmentType.ROUTINE, "P1", "S1");

            Set<ConstraintViolation<CreateAppointment>> violations = validator.validate(request);

            assertTrue(violations.stream().anyMatch(
                    violation -> violation.getPropertyPath().toString().equals("patientName")));
        }
    }

    @Test
    void acceptsPatientNamesAtTheMaximumLength() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            CreateAppointment request = new CreateAppointment(
                    "A".repeat(120), AppointmentType.ROUTINE, "P1", "S1");

            assertFalse(validator.validate(request).stream().anyMatch(
                    violation -> violation.getPropertyPath().toString().equals("patientName")));
        }
    }
}
