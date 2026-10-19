package com.agenticai.core.imagegen.agenttools;

import com.agenticai.core.agentspi.HostedImageModel;
import com.agenticai.core.imagegen.GeminiImageGenerationClient;
import org.springframework.stereotype.Component;

/** The kernel's {@link HostedImageModel} over the platform-hosted Google (Gemini) image client. */
@Component
public class GeminiHostedImageModel implements HostedImageModel {

    private final GeminiImageGenerationClient client;

    public GeminiHostedImageModel(GeminiImageGenerationClient client) {
        this.client = client;
    }

    @Override
    public boolean isAvailable() {
        return client.isAvailable();
    }

    @Override
    public Image generate(String prompt, String model) {
        GeminiImageGenerationClient.GeneratedImageBytes generated = client.generate(prompt, model);
        return new Image(generated.bytes(), generated.mimeType(), generated.model());
    }
}
