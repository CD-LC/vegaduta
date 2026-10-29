package com.agenticai.core.agents.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.providers.modality.TextModelProvider;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.providers.modality.VideoModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import org.junit.jupiter.api.Test;

/**
 * {@link ModalityModels} is the embedded value object holding an agent's four per-modality provider
 * overrides. These pin the copy-on-write contract and the "a null provider clears the model" rule
 * each {@code with...} applies - the rule {@link
 * com.agenticai.core.agents.core.AgentConfigColumns}'s modality setters rely on.
 */
class ModalityModelsTest {

    @Test
    void noneHasNoOverrides() {
        ModalityModels m = ModalityModels.none();

        assertNull(m.getVoiceModelProvider());
        assertNull(m.getVoiceModel());
        assertNull(m.getVideoModelProvider());
        assertNull(m.getVideoModel());
        assertNull(m.getTextModelProvider());
        assertNull(m.getTextModel());
        assertNull(m.getTtsProvider());
        assertNull(m.getTtsModel());
    }

    @Test
    void eachWithSetsOnlyItsOwnModalityOnACopy() {
        ModalityModels m = ModalityModels.none().withVoice(VoiceModelProvider.SARVAM, "saaras:v3");

        assertEquals(VoiceModelProvider.SARVAM, m.getVoiceModelProvider());
        assertEquals("saaras:v3", m.getVoiceModel());
        assertNull(m.getTtsProvider());

        ModalityModels more =
                m.withText(TextModelProvider.ANTHROPIC, "claude-opus-4-8")
                        .withVideo(VideoModelProvider.SARVAM_VIDEO, "saaras:v3")
                        .withTts(TtsModelProvider.ELEVENLABS, "eleven_multilingual_v2");

        assertNotSame(m, more);
        assertEquals(VoiceModelProvider.SARVAM, more.getVoiceModelProvider());
        assertEquals(TextModelProvider.ANTHROPIC, more.getTextModelProvider());
        assertEquals("claude-opus-4-8", more.getTextModel());
        assertEquals(VideoModelProvider.SARVAM_VIDEO, more.getVideoModelProvider());
        assertEquals(TtsModelProvider.ELEVENLABS, more.getTtsProvider());
        assertEquals("eleven_multilingual_v2", more.getTtsModel());
        // The original was not mutated.
        assertNull(m.getTextModelProvider());
    }

    @Test
    void aNullProviderClearsTheModel() {
        ModalityModels m =
                ModalityModels.none()
                        .withVoice(VoiceModelProvider.SARVAM, "saaras:v3")
                        .withVoice(null, "still-set");

        assertNull(m.getVoiceModelProvider());
        assertNull(m.getVoiceModel());
    }

    @Test
    void equalityCoversEveryModality() {
        ModalityModels a = ModalityModels.none().withTts(TtsModelProvider.SARVAM, "bulbul:v3");
        ModalityModels b = ModalityModels.none().withTts(TtsModelProvider.SARVAM, "bulbul:v3");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, ModalityModels.none());
        assertNotEquals(a, b.withTts(TtsModelProvider.ELEVENLABS, "bulbul:v3"));
        assertEquals(ModalityModels.none(), ModalityModels.none());
    }
}
