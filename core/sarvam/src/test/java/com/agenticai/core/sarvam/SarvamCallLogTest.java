package com.agenticai.core.sarvam;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SarvamCallLog}'s metadata-only row (capability, nullable model, outcome, latency,
 * start time - never the payload) and the {@link SarvamCallCapability} enum values the five
 * clients write.
 */
class SarvamCallLogTest {

    @Test
    void aRowCarriesMetadataNotPayload() {
        Instant started = Instant.parse("2026-09-01T10:15:30Z");
        SarvamCallLog log =
                new SarvamCallLog(
                        SarvamCallCapability.TRANSLATE, "sarvam-translate:v1", true, null, 420L, started);

        assertThat(log.getCapability()).isEqualTo(SarvamCallCapability.TRANSLATE);
        assertThat(log.getModel()).isEqualTo("sarvam-translate:v1");
        assertThat(log.isSuccess()).isTrue();
        assertThat(log.getErrorMessage()).isNull();
        assertThat(log.getLatencyMs()).isEqualTo(420L);
        assertThat(log.getStartedAt()).isEqualTo(started);
    }

    @Test
    void failedCallsRecordTheErrorAndAModelLessCapability() {
        Instant started = Instant.parse("2026-09-01T10:15:30Z");
        SarvamCallLog log =
                new SarvamCallLog(
                        SarvamCallCapability.LANGUAGE_ID, null, false, "timeout", 5_000L, started);

        assertThat(log.getModel()).isNull();
        assertThat(log.isSuccess()).isFalse();
        assertThat(log.getErrorMessage()).isEqualTo("timeout");
    }

    @Test
    void everyStageThreeCapabilityHasAnEnumValue() {
        assertThat(SarvamCallCapability.values())
                .containsExactly(
                        SarvamCallCapability.TRANSLATE,
                        SarvamCallCapability.SPEECH_TO_TEXT,
                        SarvamCallCapability.TEXT_TO_SPEECH,
                        SarvamCallCapability.TRANSLITERATION,
                        SarvamCallCapability.LANGUAGE_ID);
    }
}
