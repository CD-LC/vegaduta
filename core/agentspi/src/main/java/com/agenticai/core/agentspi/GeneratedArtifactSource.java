package com.agenticai.core.agentspi;

import java.util.Optional;
import java.util.UUID;

/**
 * One kind of generated artifact the platform serves under {@code /api/<pathSegment>/<uuid>} (an
 * image, a voice clip), looked up by id so {@code tools.media.GeneratedAttachmentResolver} can
 * forward a previously generated artifact's bytes to a channel. Kernel-owned (ADR-0011 rule a);
 * implementations live with their vendor: {@code imagegen.agenttools.GeneratedImageArtifactSource},
 * {@code elevenlabs.agenttools.ElevenLabsAudioArtifactSource}, {@code
 * sarvam.agenttools.SarvamAudioArtifactSource}.
 */
public interface GeneratedArtifactSource {

    /** Where the artifact's bytes live in file storage, and how to label them. */
    record StoredArtifact(String objectKey, String contentType, String filename) {}

    /** The URL path between {@code /api/} and the id, e.g. {@code imagegen/images}. */
    String pathSegment();

    /** Empty when no artifact with this id exists in the current tenant. */
    Optional<StoredArtifact> find(UUID id);
}
