package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A PEM private key must be redacted whole, and the scan must stay linear on text full of BEGIN
 * lines.
 */
class AgentStepEventRedactorPemTest {

    private static final String BODY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC\nKcwggSjAgEAAoIBAQC7\n";

    @Test
    void aWholePrivateKeyBlockIsRedacted() {
        String key = "-----BEGIN PRIVATE KEY-----\n" + BODY + "-----END PRIVATE KEY-----";
        assertThat(AgentStepEventRedactor.redactPayload("before " + key + " after"))
                .isEqualTo("before " + AgentStepEventRedactor.REDACTED + " after");
        String rsa = "-----BEGIN RSA PRIVATE KEY-----\n" + BODY + "-----END RSA PRIVATE KEY-----";
        assertThat(AgentStepEventRedactor.redactPayload(key + " and " + rsa))
                .isEqualTo(
                        AgentStepEventRedactor.REDACTED
                                + " and "
                                + AgentStepEventRedactor.REDACTED);
    }

    @Test
    void aBeginLineRepeatedManyTimesWithNoEndIsScannedInLinearTime() {
        // Each BEGIN used to scan to the end of the text looking for an END: quadratic.
        String hostile = "-----BEGIN PRIVATE KEY-----".repeat(100_000);
        String out =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(3), () -> AgentStepEventRedactor.redactPayload(hostile));
        assertThat(out).isEqualTo(hostile);
    }

    @Test
    void aKeyAfterAStrayBeginLineIsRedactedWithIt() {
        // A block runs from its first BEGIN to the first END, as the original lazy pattern did, so
        // nothing of a key can be left in front of it.
        String text =
                "-----BEGIN PRIVATE KEY-----\nnote\n-----BEGIN PRIVATE KEY-----\n"
                        + BODY
                        + "-----END PRIVATE KEY-----";
        assertThat(AgentStepEventRedactor.redactPayload(text))
                .isEqualTo(AgentStepEventRedactor.REDACTED);
    }

    @Test
    void theStepTraceRedactsAKeyInArgumentsAndPreview() {
        String key = "-----BEGIN PRIVATE KEY-----\n" + BODY + "-----END PRIVATE KEY-----";
        assertThat(
                        AgentStepEventRedactor.redactArguments(
                                "{\"note\":\"" + key.replace("\n", " ") + "\"}"))
                .doesNotContain("MIIEv")
                .contains(AgentStepEventRedactor.REDACTED);
        assertThat(AgentStepEventRedactor.redactPreview("out: " + key))
                .doesNotContain("MIIEv")
                .contains(AgentStepEventRedactor.REDACTED);
    }
}
