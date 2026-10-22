package com.agenticai.core.guardrails.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Phase P6. Deterministic coverage of the pattern-rule layer per pack. */
class PolicyPackPatternMatcherTest {

    private final PolicyPackPatternMatcher matcher = new PolicyPackPatternMatcher();

    @Test
    void detectsEmailAndPhoneAndIdsForPiiPack() {
        assertThat(matcher.firstMatch(ModerationPolicyPack.PII, "reach me at jane.doe@example.com"))
                .contains("email");
        assertThat(matcher.firstMatch(ModerationPolicyPack.PII, "my SSN is 123-45-6789"))
                .contains("ssn");
        assertThat(matcher.firstMatch(ModerationPolicyPack.PII, "PAN ABCDE1234F on file"))
                .contains("pan");
    }

    @Test
    void detectsProfanityForFoulLanguagePack() {
        assertThat(matcher.firstMatch(ModerationPolicyPack.FOUL_LANGUAGE, "this is sh*t"))
                .isPresent();
    }

    @Test
    void detectsSelfHarmIntent() {
        assertThat(matcher.firstMatch(ModerationPolicyPack.SELF_HARM, "I want to die")).isPresent();
    }

    @Test
    void cleanContentMatchesNothing() {
        assertThat(matcher.firstMatch(ModerationPolicyPack.PII, "The weather is lovely today."))
                .isEmpty();
        assertThat(matcher.firstMatch(ModerationPolicyPack.FOUL_LANGUAGE, "Have a wonderful day!"))
                .isEmpty();
    }

    @Test
    void blankContentMatchesNothing() {
        assertThat(matcher.firstMatch(ModerationPolicyPack.PII, "")).isEmpty();
        assertThat(matcher.firstMatch(ModerationPolicyPack.PII, null)).isEmpty();
    }

    @Test
    void longRunsOfEmailCharactersWithoutAnAtSignAreScannedInLinearTime() {
        for (String unit : new String[] {"a", "%", "a."}) {
            String hostile = unit.repeat(50_000);
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> matcher.firstMatch(ModerationPolicyPack.PII, hostile));
        }
        assertThat(matcher.firstMatch(ModerationPolicyPack.PII, "x".repeat(40) + "@example.com"))
                .contains("email");
    }

    @Test
    void anAddressWithAnOverLongLocalPartIsStillDetected() {
        for (int length : new int[] {64, 65, 70, 300, 5_000}) {
            assertThat(
                            matcher.firstMatch(
                                    ModerationPolicyPack.PII,
                                    "mail " + "a".repeat(length) + "@example.com"))
                    .as("local part of %d characters", length)
                    .contains("email");
        }
    }
}
