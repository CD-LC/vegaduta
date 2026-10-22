package com.agenticai.core.agents.turnjournal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TurnRedactionTest {

    @Test
    void anAddressIsMaskedWhateverTheLengthOfItsLocalPart() {
        assertThat(TurnRedaction.summary("sent to jane.doe@example.com ok"))
                .isEqualTo("sent to [email] ok");
        for (int length : new int[] {63, 64, 65, 70, 300}) {
            // A run of 32+ letters is also a "long token", so use dots to keep this about e-mail.
            String local = "ab.".repeat(length / 3 + 1);
            assertThat(TurnRedaction.summary("to " + local + "@example.com"))
                    .as("local part of %d characters", local.length())
                    .doesNotContain("ab.ab")
                    .contains("[email]");
        }
    }

    @Test
    void hostileTextIsSummarisedInBoundedTime() {
        for (String hostile :
                new String[] {"a.".repeat(25_000), "a@".repeat(25_000), "a".repeat(50_000)}) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> TurnRedaction.summary(hostile));
        }
    }
}
