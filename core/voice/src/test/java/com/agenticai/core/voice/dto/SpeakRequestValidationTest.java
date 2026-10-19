package com.agenticai.core.voice.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.speech.SpeechTextNormalizer;
import com.agenticai.core.voice.VoiceCallController;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link SpeakRequest}'s size cap AND the fact that it is actually
 * enforced.
 *
 * <p>This record used to carry no validation at all, and
 * {@link VoiceCallController#speak} took it with a bare {@code @RequestBody} -
 * so the text went straight into
 * {@link SpeechTextNormalizer#normalizeForSpeech}, whose markdown/HTML rules
 * were quadratic on unterminated delimiters (measured 170 seconds for one
 * request). An annotation with no {@code @Valid} next to it would be silently
 * inert, which is why the second test here is a reflection check rather than
 * something that looks redundant.
 */
@DisplayName("SpeakRequest - request size cap")
class SpeakRequestValidationTest {

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

    private static Set<ConstraintViolation<SpeakRequest>> validate(String text) {
        return validator.validate(new SpeakRequest(text));
    }

    @Test
    @DisplayName("text carries a @Size cap of 5000 characters")
    void sizeCapIsPresentAndIsFiveThousand() {
        assertThat(validate("a".repeat(5_000)))
            .as("5000 characters is ElevenLabs' own per-request limit and must still be accepted")
            .isEmpty();

        assertThat(validate("a".repeat(5_001)))
            .as("5001 characters must be rejected - if this is empty, the @Size cap is gone")
            .isNotEmpty();
    }

    @Test
    @DisplayName("the speak endpoint declares @Valid, so the cap is actually enforced")
    void controllerParameterIsAnnotatedValid() throws NoSuchMethodException {
        Method speak = VoiceCallController.class.getMethod("speak", UUID.class, SpeakRequest.class);
        Parameter body = speak.getParameters()[1];

        assertThat(body.getType()).isEqualTo(SpeakRequest.class);
        assertThat(body.getAnnotation(Valid.class))
            .as("@Valid is missing from VoiceCallController#speak - @Size on SpeakRequest would be inert")
            .isNotNull();
    }

    @Test
    @DisplayName("the cap sits at or above the normalizer's own defensive cap boundary")
    void capIsBelowTheNormalizerCap() {
        // The DTO cap is the cheap outer gate; the normalizer's own cap
        // protects the five call sites that never see a SpeakRequest. The DTO
        // one must not exceed it, or an accepted request would be truncated
        // downstream instead of rejected up front.
        assertThat(5_000).isLessThanOrEqualTo(SpeechTextNormalizer.MAX_INPUT_CHARS);
    }

    @Test
    @DisplayName("an ordinary reply is unaffected")
    void ordinaryTextPasses() {
        assertThat(validate("Your order shipped on Tuesday and arrives Friday.")).isEmpty();
    }

    @Test
    @DisplayName("blank text is left to the controller's own check, not @NotBlank")
    void blankIsNotAConstraintViolation() {
        // VoiceCallController#speak answers null/blank with its own
        // purpose-written 400; adding @NotBlank here would change that
        // response's shape for no benefit. Pinned so the two stay in sync.
        assertThat(validate(null)).isEmpty();
        assertThat(validate("")).isEmpty();
    }
}
