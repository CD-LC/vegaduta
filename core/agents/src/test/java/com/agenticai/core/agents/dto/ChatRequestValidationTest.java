package com.agenticai.core.agents.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ChatRequest}'s size cap.
 *
 * <p>{@code message} is the first attacker-controlled string of a turn and it
 * reaches {@code JailbreakDetectorGuardrail} before the response cache and
 * before the budget check. With that guardrail's old unbounded {@code .*}
 * patterns, an unvalidated 1.6 KB message measured 41 seconds of pinned CPU
 * (see {@code JailbreakDetectorGuardrailReDoSTest}). The regexes are linear
 * now, but the cap is the outer layer that keeps any future pattern's cost
 * bounded - so if someone deletes the annotation, this test is what says so.
 */
@DisplayName("ChatRequest - request size cap")
class ChatRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static ChatRequest withMessage(String message) {
        // Trailing booleans, in canonical order: forceFastDepth, stepEvents.
        // Both default to false for every real caller (Jackson leaves a record's
        // primitive boolean false when the field is absent from the body), and
        // neither participates in validation - they are spelled out here only
        // because a record's canonical constructor has no optional arguments.
        return new ChatRequest(null, message, null, null, null, null, null, false, false);
    }

    private static Set<ConstraintViolation<ChatRequest>> validate(String message) {
        return validator.validate(withMessage(message));
    }

    @Test
    @DisplayName("message carries a @Size cap of 8000 characters")
    void sizeCapIsPresentAndIsEightThousand() {
        assertThat(validate("a".repeat(8_000)))
            .as("8000 characters must still be accepted")
            .isEmpty();

        assertThat(validate("a".repeat(8_001)))
            .as("8001 characters must be rejected - if this is empty, the @Size cap is gone")
            .isNotEmpty();
    }

    @Test
    @DisplayName("the rejection names the message field, so the 400 is actionable")
    void violationPointsAtMessage() {
        Set<ConstraintViolation<ChatRequest>> violations = validate("a".repeat(20_000));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath()).hasToString("message");
        assertThat(violations.iterator().next().getMessage()).contains("8000");
    }

    @Test
    @DisplayName("an ordinary message is unaffected")
    void ordinaryMessagePasses() {
        assertThat(validate("Summarise last month's invoices, please.")).isEmpty();
    }

    @Test
    @DisplayName("@NotBlank still fires - adding @Size did not displace it")
    void blankIsStillRejected() {
        assertThat(validate(null)).isNotEmpty();
        assertThat(validate("   ")).isNotEmpty();
    }
}
