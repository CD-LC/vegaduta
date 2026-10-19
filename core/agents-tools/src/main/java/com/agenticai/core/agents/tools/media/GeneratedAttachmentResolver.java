package com.agenticai.core.agents.tools.media;

import com.agenticai.core.agentspi.GeneratedArtifactSource;
import com.agenticai.core.agentspi.GeneratedArtifactSource.StoredArtifact;
import com.agenticai.core.storage.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves one of this platform's own generated-artifact references (the same four URL shapes
 * {@code MediaCard.tsx}'s {@code MEDIA_URL_PATTERN} recognizes client-side: {@code
 * /api/imagegen/images/<uuid>}, {@code /api/elevenlabs/audio/<uuid>}, {@code
 * /api/sarvam/audio/<uuid>}, {@code /api/videogen/videos/<uuid>}) down to real bytes, so a tool can
 * forward an attachment a PRIOR turn already generated to a channel, instead of only ever being
 * able to render it inline in this platform's own chat UI.
 *
 * <p><b>Video is deliberately NOT resolved here</b> - generated videos are job-based and can be
 * large enough that this platform's own download route serves them via HTTP range requests (see
 * {@code VideoGenerationController}), so loading one whole into a {@code byte[]} to hand to a
 * channel API is the wrong shape for this class. Forwarding a generated video is a separate,
 * not-yet-built follow-up - {@link #resolve} returns empty for a videogen path rather than
 * pretending to support it.
 *
 * <p>The artifact kinds come from the {@link GeneratedArtifactSource} beans (ADR-0011 rule a: the
 * vendors own their rows, this class never imports one).
 */
@Component
public class GeneratedAttachmentResolver {

    private static final String UUID_REGEX =
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";

    private final Map<String, GeneratedArtifactSource> sourcesBySegment = new LinkedHashMap<>();
    private final Pattern refPattern;
    private final FileStorageService fileStorageService;

    public GeneratedAttachmentResolver(
            List<GeneratedArtifactSource> sources, FileStorageService fileStorageService) {
        for (GeneratedArtifactSource source : sources) {
            sourcesBySegment.putIfAbsent(source.pathSegment(), source);
        }
        this.refPattern =
                sourcesBySegment.isEmpty()
                        ? null
                        : Pattern.compile(
                                "/api/("
                                        + String.join(
                                                "|",
                                                sourcesBySegment.keySet().stream()
                                                        .map(Pattern::quote)
                                                        .toList())
                                        + ")/"
                                        + UUID_REGEX);
        this.fileStorageService = fileStorageService;
    }

    /**
     * @param reference a full or partial URL/path containing one of the three supported artifact
     *     shapes - callers may pass the raw {@code audioUrl}/{@code imageUrl} string a tool
     *     returned verbatim.
     * @return the resolved bytes, or empty if the reference doesn't match a known shape, or matches
     *     one but no row exists for that id (already deleted, or never real - see {@code
     *     MediaCard.tsx}'s hallucinated- artifact guard for the client-side half of that same
     *     problem).
     */
    public Optional<ResolvedAttachment> resolve(String reference) {
        if (reference == null || reference.isBlank() || refPattern == null) {
            return Optional.empty();
        }
        Matcher m = refPattern.matcher(reference);
        if (!m.find()) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(m.group(2));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        GeneratedArtifactSource source = sourcesBySegment.get(m.group(1));
        return source == null ? Optional.empty() : source.find(id).map(this::readBytes);
    }

    private ResolvedAttachment readBytes(StoredArtifact artifact) {
        try (InputStream in = fileStorageService.get(artifact.objectKey())) {
            return new ResolvedAttachment(
                    in.readAllBytes(), artifact.contentType(), artifact.filename());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read stored attachment '" + artifact.objectKey() + "'", e);
        }
    }

    public record ResolvedAttachment(byte[] bytes, String contentType, String filename) {}
}
