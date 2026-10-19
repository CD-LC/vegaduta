package com.agenticai.core.agents.tools.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.GeneratedArtifactSource;
import com.agenticai.core.agentspi.GeneratedArtifactSource.StoredArtifact;
import com.agenticai.core.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A generated-artifact URL resolves through the source that owns its path segment; videos and
 * unknown shapes do not.
 */
class GeneratedAttachmentResolverTest {

    private final FileStorageService storage = mock(FileStorageService.class);

    private static GeneratedArtifactSource source(
            String segment, UUID id, StoredArtifact artifact) {
        GeneratedArtifactSource s = mock(GeneratedArtifactSource.class);
        when(s.pathSegment()).thenReturn(segment);
        when(s.find(id)).thenReturn(Optional.of(artifact));
        return s;
    }

    @Test
    void aKnownShapeReadsTheOwningSourcesBytes() throws Exception {
        UUID id = UUID.randomUUID();
        GeneratedArtifactSource images = mock(GeneratedArtifactSource.class);
        when(images.pathSegment()).thenReturn("imagegen/images");
        GeneratedArtifactSource sarvam =
                source("sarvam/audio", id, new StoredArtifact("k/1", "audio/wav", "speech.wav"));
        when(storage.get("k/1")).thenReturn(new ByteArrayInputStream(new byte[] {7}));
        GeneratedAttachmentResolver resolver =
                new GeneratedAttachmentResolver(List.of(images, sarvam), storage);

        GeneratedAttachmentResolver.ResolvedAttachment a =
                resolver.resolve("Here it is: https://x.example/api/sarvam/audio/" + id + " enjoy")
                        .orElseThrow();

        assertArrayEquals(new byte[] {7}, a.bytes());
        assertEquals("audio/wav", a.contentType());
        assertEquals("speech.wav", a.filename());
    }

    @Test
    void videosUnknownShapesAndMissingRowsResolveToNothing() {
        UUID id = UUID.randomUUID();
        GeneratedArtifactSource images = mock(GeneratedArtifactSource.class);
        when(images.pathSegment()).thenReturn("imagegen/images");
        when(images.find(id)).thenReturn(Optional.empty());
        GeneratedAttachmentResolver resolver =
                new GeneratedAttachmentResolver(List.of(images), storage);

        assertTrue(resolver.resolve("/api/videogen/videos/" + id).isEmpty());
        assertTrue(resolver.resolve("/api/imagegen/images/not-a-uuid").isEmpty());
        assertTrue(resolver.resolve("/api/imagegen/images/" + id).isEmpty());
        assertTrue(resolver.resolve(null).isEmpty());
        assertTrue(
                new GeneratedAttachmentResolver(List.of(), storage)
                        .resolve("/api/imagegen/images/" + id)
                        .isEmpty());
        verifyNoInteractions(storage);
    }
}
