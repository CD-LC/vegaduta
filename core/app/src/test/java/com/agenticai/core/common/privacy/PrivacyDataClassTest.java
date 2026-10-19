package com.agenticai.core.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrivacyDataClassTest {

    @Test
    void transcriptsCanBeSetPerChannelRecordingsForVoiceAndExtractedFieldsOnlyTenantWide() {
        assertThat(PrivacyDataClass.TRANSCRIPT.allows("*")).isTrue();
        assertThat(PrivacyDataClass.TRANSCRIPT.allows("WHATSAPP")).isTrue();
        assertThat(PrivacyDataClass.TRANSCRIPT.allows("NOT_A_CHANNEL")).isFalse();
        assertThat(PrivacyDataClass.TRANSCRIPT.allows("VOICE")).isTrue();
        assertThat(PrivacyDataClass.RECORDING.allows("VOICE")).isTrue();
        assertThat(PrivacyDataClass.RECORDING.allows("WHATSAPP")).isFalse();
        assertThat(PrivacyDataClass.EXTRACTED_FIELDS.allows("*")).isTrue();
        assertThat(PrivacyDataClass.EXTRACTED_FIELDS.allows("WHATSAPP")).isFalse();
    }

    @Test
    void erasurePatternMatchesWhatTheKnowledgeWriteToolStamps() {
        // AddToKnowledgeBaseToolCallback#buildSource writes
        // "Agent memory: <title> (written by agent <id>, session <sid>, at <ts>)".
        String pattern = ContactPrivacyService.derivedChunkPattern("sess-1");
        assertThat(pattern).isEqualTo("Agent memory: %(written by agent %, session sess-1, at %");
        assertThat(ContactPrivacyService.derivedChunkPattern("a%b_c")).contains("a\\%b\\_c");
    }

    @Test
    void maskingKeepsOnlyTheLastFourCharacters() {
        assertThat(ContactPrivacyService.mask("+919876543210")).isEqualTo("***3210");
        assertThat(ContactPrivacyService.mask("ab")).isEqualTo("***");
        assertThat(ContactPrivacyService.phoneDigits("+91 98765 43210")).isEqualTo("919876543210");
        assertThat(ContactPrivacyService.phoneDigits("12345")).isNull();
    }
}
