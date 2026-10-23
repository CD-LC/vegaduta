package com.agenticai.core.agents.skills.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SkillDraftScrubberTest {

    private final SkillDraftScrubber scrubber = SkillDraftScrubber.basic();

    @Test
    void anAddressIsScrubbedWhateverTheLengthOfItsLocalPart() {
        assertThat(scrubber.scrub("ask jane.doe@example.com")).isEqualTo("ask [EMAIL]");
        for (int length : new int[] {63, 64, 65, 70, 300}) {
            // Dots keep the local part from also being read as a 40+ character opaque token.
            String local = "ab.".repeat(length / 3 + 1);
            assertThat(scrubber.scrub("to " + local + "@example.com"))
                    .as("local part of %d characters", local.length())
                    .doesNotContain("ab.ab")
                    .contains("[EMAIL]");
        }
    }

    @Test
    void aPrivateKeyBlockIsScrubbedAndTheTextAroundItKept() {
        String key =
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";
        assertThat(scrubber.scrub("use " + key + " then stop"))
                .isEqualTo("use [REDACTED] then stop");
        assertThat(SkillDraftScrubber.redactions(scrubber.scrub(key))).isEqualTo(1);
    }

    @Test
    void hostileTextIsScrubbedInBoundedTime() {
        for (String hostile :
                new String[] {
                    "a%".repeat(25_000),
                    "a@".repeat(25_000),
                    "-----BEGIN PRIVATE KEY-----".repeat(2_000)
                }) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> scrubber.scrub(hostile));
        }
    }
}
