package com.agenticai.core.tenancy.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Regression for the bug-bounty "application-level DoS on signup" report:
 * POST /api/signup accepted an unbounded password and passed it to Keycloak's
 * key-stretching hash. Bean validation must reject it (-> HTTP 400 via
 * GlobalExceptionHandler) before any provisioning work starts.
 */
class SignupRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static SignupRequest withCredentials(String email, String password) {
        return new SignupRequest("Acme", email, password, "Ada", "Lovelace", null, null, null, null);
    }

    private Set<String> violatedFields(SignupRequest request) {
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void acceptsPasswordAtTheMaximumLength() {
        String password = "a".repeat(SignupRequest.MAX_PASSWORD_LENGTH);
        assertTrue(violatedFields(withCredentials("ada@example.com", password)).isEmpty());
    }

    @Test
    void rejectsPasswordOneOverTheMaximum() {
        String password = "a".repeat(SignupRequest.MAX_PASSWORD_LENGTH + 1);
        assertEquals(Set.of("adminPassword"), violatedFields(withCredentials("ada@example.com", password)));
    }

    @Test
    void rejectsTheReportedMegabytePassword() {
        String password = "123456789 ".repeat(100_000);
        assertEquals(Set.of("adminPassword"), violatedFields(withCredentials("ada@example.com", password)));
    }

    @Test
    void rejectsOverlongEmail() {
        String email = "a".repeat(250) + "@example.com";
        assertTrue(violatedFields(withCredentials(email, "correct-horse-battery")).contains("adminEmail"));
    }

    @Test
    void rejectsElevenCharactersAndAcceptsTwelve() {
        // 12 matches Keycloak's realm policy (2026-10-09); 8-11 used to pass here and fail in Keycloak.
        assertEquals(Set.of("adminPassword"), violatedFields(withCredentials("ada@example.com", "a".repeat(11))));
        assertTrue(violatedFields(withCredentials("ada@example.com", "a".repeat(12))).isEmpty());
    }

    @Test
    void stillRejectsShortPassword() {
        assertEquals(Set.of("adminPassword"), violatedFields(withCredentials("ada@example.com", "short")));
    }
}
