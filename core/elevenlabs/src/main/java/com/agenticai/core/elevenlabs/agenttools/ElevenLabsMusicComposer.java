package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.agentspi.MusicComposer;
import com.agenticai.core.elevenlabs.ElevenLabsApiException;
import com.agenticai.core.elevenlabs.ElevenLabsClient;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.stereotype.Component;

/**
 * The kernel's {@link MusicComposer} over ElevenLabs' music API; an API rejection keeps its
 * upstream status.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class ElevenLabsMusicComposer implements MusicComposer {

    private final ElevenLabsClient client;

    public ElevenLabsMusicComposer(ElevenLabsClient client) {
        this.client = client;
    }

    @Override
    public byte[] compose(
            String prompt, int lengthMs, String modelId, boolean forceInstrumental, String apiKey) {
        try {
            return client.composeMusic(prompt, lengthMs, modelId, forceInstrumental, apiKey);
        } catch (ElevenLabsApiException e) {
            throw new ComposeFailedException(e.getUpstreamStatus(), e.getMessage(), e);
        }
    }
}
