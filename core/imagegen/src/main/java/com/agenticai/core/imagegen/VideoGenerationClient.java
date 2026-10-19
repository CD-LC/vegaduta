package com.agenticai.core.imagegen;

import com.agenticai.core.providers.ProviderName;
import java.util.Optional;

/**
 * One video-generation provider - the video counterpart of {@link
 * ImageGenerationClient}, same one-implementation-per-provider registry
 * shape. Async by nature (Veo-class models take tens of seconds to
 * minutes), so this interface is a thin submit/poll pair rather than a
 * single blocking call - {@link VideoGenerationJobService} owns turning
 * that into the enqueue/poll job row a caller actually sees.
 */
public interface VideoGenerationClient {

    ProviderName supportedProvider();

    /**
     * @param sourceImage when present, the provider is asked to animate FROM this exact image
     *     (image-to-video / first-frame conditioning) rather than generating a scene from the
     *     prompt alone - the mechanism this platform uses to keep a generated clip close to an
     *     uploaded photo's actual likeness. {@code null} means text-only generation.
     * @return an opaque, provider-specific operation id to pass to {@link #poll} later.
     */
    String submit(String apiKey, String prompt, String model, int durationSeconds, SourceImage sourceImage);

    /** @return empty while still running; the finished bytes once the provider reports completion. @throws ImageGenApiException if the provider reports the generation failed. */
    Optional<GeneratedVideo> poll(String apiKey, String operationId, String model);

    record GeneratedVideo(byte[] bytes, String contentType) {
    }

    /** A caller-supplied starting frame for image-to-video generation. {@code bytesBase64} is the
     * raw image bytes, already base64-encoded (the shape Vertex's Veo API itself expects, so no
     * re-encoding happens between the controller and the provider call). */
    record SourceImage(String bytesBase64, String mimeType) {
    }
}
