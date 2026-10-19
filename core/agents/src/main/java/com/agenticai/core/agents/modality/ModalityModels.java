package com.agenticai.core.agents.modality;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.providers.modality.TextModelProvider;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.providers.modality.VideoModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;

/**
 * An agent's per-modality provider overrides (voice, video, text, text-to-speech), each a provider
 * plus a model id. Embedded in {@link Agent}; the columns keep their names. Every column is
 * nullable, so Hibernate hands the owner {@code null} when all are null: {@link Agent} treats a
 * null reference as "no overrides" and never exposes it.
 */
@Embeddable
public class ModalityModels {

    // Multi-provider voice/video/text architecture foundation (migration V148).
    // Nullable - when null, VoiceCallController falls back to the hardcoded
    // Sidecar → Sarvam chain for backwards compatibility. When set explicitly,
    // this agent's voice turns route through the selected provider.
    //
    // For agents created via web UI after this migration, both voice_model_provider
    // and voice_model will be set together (never partially). The web console's
    // agent creation UI validates that the selected provider is available on this
    // deployment before accepting the form.
    @Enumerated(EnumType.STRING)
    @Column(name = "voice_model_provider", length = 32)
    private VoiceModelProvider voiceModelProvider;

    @Column(name = "voice_model", length = 128)
    private String voiceModel;

    // Multi-provider video/video-to-text/transcription foundation (migration V149+).
    // Nullable - when null, video processing falls back to the hardcoded
    // default chain for backwards compatibility. When set explicitly,
    // this agent's video turns route through the selected provider.
    //
    // For agents created via web UI after this migration, both video_model_provider
    // and video_model will be set together (never partially). The web console's
    // agent creation UI validates that the selected provider is available on this
    // deployment before accepting the form.
    @Enumerated(EnumType.STRING)
    @Column(name = "video_model_provider", length = 32)
    private VideoModelProvider videoModelProvider;

    @Column(name = "video_model", length = 128)
    private String videoModel;

    // Multi-provider text routing (Agent Intelligence research doc, July 2026).
    // Nullable - when null, TextModelProvider uses its default fallback chain.
    // When set explicitly, this agent's text turns route through the selected provider.
    //
    // For agents created via web UI after this migration, both text_model_provider
    // and text_model will be set together (never partially). The web console's
    // agent creation UI validates that the selected provider is available on this
    // deployment before accepting the form.
    @Enumerated(EnumType.STRING)
    @Column(name = "text_model_provider", length = 32)
    private TextModelProvider textModelProvider;

    @Column(name = "text_model", length = 128)
    private String textModel;

    // Text-to-speech (TTS) provider selection - Stage 2 of the multi-provider
    // modality architecture (migration V196), the output-side twin of
    // voiceModelProvider/voiceModel above (which selects the INPUT-side,
    // speech-to-text provider). Nullable - when null, VoiceCallController
    // falls back to the existing hardcoded ElevenLabs-then-Sarvam chain for
    // backwards compatibility. When set explicitly, this agent's voice
    // replies are synthesized through the selected provider first.
    //
    // For agents created via web UI after this migration, both tts_provider
    // and tts_model will be set together (never partially). The web
    // console's agent settings validate that the selected provider is
    // available on this deployment before accepting the form - see
    // TextToSpeechProviderValidator.
    @Enumerated(EnumType.STRING)
    @Column(name = "tts_provider", length = 32)
    private TtsModelProvider ttsProvider;

    @Column(name = "tts_model", length = 128)
    private String ttsModel;

    /** JPA, and the "no overrides" starting state. */
    protected ModalityModels() {}

    public static ModalityModels none() {
        return new ModalityModels();
    }

    private ModalityModels copy() {
        ModalityModels m = new ModalityModels();
        m.voiceModelProvider = voiceModelProvider;
        m.voiceModel = voiceModel;
        m.videoModelProvider = videoModelProvider;
        m.videoModel = videoModel;
        m.textModelProvider = textModelProvider;
        m.textModel = textModel;
        m.ttsProvider = ttsProvider;
        m.ttsModel = ttsModel;
        return m;
    }

    /** A null provider clears the model. */
    public ModalityModels withVoice(VoiceModelProvider provider, String model) {
        ModalityModels m = copy();
        m.voiceModelProvider = provider;
        m.voiceModel = provider == null ? null : model;
        return m;
    }

    public ModalityModels withVideo(VideoModelProvider provider, String model) {
        ModalityModels m = copy();
        m.videoModelProvider = provider;
        m.videoModel = provider == null ? null : model;
        return m;
    }

    public ModalityModels withText(TextModelProvider provider, String model) {
        ModalityModels m = copy();
        m.textModelProvider = provider;
        m.textModel = provider == null ? null : model;
        return m;
    }

    public ModalityModels withTts(TtsModelProvider provider, String model) {
        ModalityModels m = copy();
        m.ttsProvider = provider;
        m.ttsModel = provider == null ? null : model;
        return m;
    }

    public VoiceModelProvider getVoiceModelProvider() {
        return voiceModelProvider;
    }

    public String getVoiceModel() {
        return voiceModel;
    }

    public VideoModelProvider getVideoModelProvider() {
        return videoModelProvider;
    }

    public String getVideoModel() {
        return videoModel;
    }

    public TextModelProvider getTextModelProvider() {
        return textModelProvider;
    }

    public String getTextModel() {
        return textModel;
    }

    public TtsModelProvider getTtsProvider() {
        return ttsProvider;
    }

    public String getTtsModel() {
        return ttsModel;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ModalityModels m
                && voiceModelProvider == m.voiceModelProvider
                && Objects.equals(voiceModel, m.voiceModel)
                && videoModelProvider == m.videoModelProvider
                && Objects.equals(videoModel, m.videoModel)
                && textModelProvider == m.textModelProvider
                && Objects.equals(textModel, m.textModel)
                && ttsProvider == m.ttsProvider
                && Objects.equals(ttsModel, m.ttsModel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(voiceModelProvider, voiceModel, videoModelProvider, videoModel,
                textModelProvider, textModel, ttsProvider, ttsModel);
    }
}
