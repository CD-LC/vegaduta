package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.agentspi.MediaToolSource;
import com.agenticai.core.sarvam.SarvamCredentialService;
import com.agenticai.core.sarvam.SarvamGeneratedAudioService;
import com.agenticai.core.sarvam.SarvamLanguageIdClient;
import com.agenticai.core.sarvam.SarvamSpeechToTextClient;
import com.agenticai.core.sarvam.SarvamTextToSpeechClient;
import com.agenticai.core.sarvam.SarvamTranslateClient;
import com.agenticai.core.sarvam.SarvamTransliterationClient;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sarvam's language tools ({@code sarvam_translate}, {@code sarvam_transcribe}, and when their
 * beans exist {@code sarvam_transliterate} / {@code sarvam_identify_language}) through the kernel's
 * {@link MediaToolSource} (ADR-0011 rule a). Offered only when the tenant has an ACTIVE Sarvam
 * credential ({@code isActive()} - a cheap boolean, never the throwing {@code
 * resolveActiveApiKey()}); the per-agent opt-in ({@code Agent.sarvamEnabled}) stays a kernel gate
 * in {@code AgentMediaToolResolver}.
 *
 * <p>Speak is not here: it moved to the unified {@code speak} tool (2026-08-02), so the model never
 * sees two differently-named speak tools. The text-to-speech client and generated-audio service are
 * still required, exactly as before, so the overall gate (and a slim test context's null-check) is
 * unchanged. Every ObjectProvider resolves together in the real app; only a slim context lacks
 * them.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamLanguageToolSource implements MediaToolSource {

    private final ObjectProvider<SarvamCredentialService> credentialService;
    private final ObjectProvider<SarvamTranslateClient> translateClient;
    private final ObjectProvider<SarvamSpeechToTextClient> speechToTextClient;
    private final ObjectProvider<SarvamTextToSpeechClient> textToSpeechClient;
    private final ObjectProvider<SarvamGeneratedAudioService> generatedAudioService;
    private final ObjectProvider<SarvamTransliterationClient> transliterationClient;
    private final ObjectProvider<SarvamLanguageIdClient> languageIdClient;

    public SarvamLanguageToolSource(
            ObjectProvider<SarvamCredentialService> credentialService,
            ObjectProvider<SarvamTranslateClient> translateClient,
            ObjectProvider<SarvamSpeechToTextClient> speechToTextClient,
            ObjectProvider<SarvamTextToSpeechClient> textToSpeechClient,
            ObjectProvider<SarvamGeneratedAudioService> generatedAudioService,
            ObjectProvider<SarvamTransliterationClient> transliterationClient,
            ObjectProvider<SarvamLanguageIdClient> languageIdClient) {
        this.credentialService = credentialService;
        this.translateClient = translateClient;
        this.speechToTextClient = speechToTextClient;
        this.textToSpeechClient = textToSpeechClient;
        this.generatedAudioService = generatedAudioService;
        this.transliterationClient = transliterationClient;
        this.languageIdClient = languageIdClient;
    }

    @Override
    public Kind kind() {
        return Kind.LANGUAGE;
    }

    @Override
    public List<ToolCallback> rawTools() {
        SarvamCredentialService credentials = credentialService.getIfAvailable();
        if (credentials == null || !credentials.isActive()) {
            return List.of();
        }
        SarvamTranslateClient translate = translateClient.getIfAvailable();
        SarvamSpeechToTextClient speechToText = speechToTextClient.getIfAvailable();
        if (translate == null
                || speechToText == null
                || textToSpeechClient.getIfAvailable() == null
                || generatedAudioService.getIfAvailable() == null) {
            return List.of();
        }
        List<ToolCallback> tools =
                new ArrayList<>(
                        List.of(
                                new SarvamTranslateToolCallback(translate),
                                new SarvamTranscribeToolCallback(speechToText)));
        // Additive, so a slim context missing these two beans still gets translate/transcribe.
        SarvamTransliterationClient transliteration = transliterationClient.getIfAvailable();
        if (transliteration != null) {
            tools.add(new SarvamTransliterateToolCallback(transliteration));
        }
        SarvamLanguageIdClient languageId = languageIdClient.getIfAvailable();
        if (languageId != null) {
            tools.add(new SarvamIdentifyLanguageToolCallback(languageId));
        }
        return tools;
    }
}
