package com.agenticai.core.imagegen.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.GeneratedVideoReader;
import com.agenticai.core.imagegen.ImageGenNotConfiguredException;
import com.agenticai.core.imagegen.VideoGenerationJobService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** imagegen's generated-video reader: a not-ready job becomes the kernel's NotReadyException. */
class ImageGenGeneratedVideoReaderTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<VideoGenerationJobService> provider(
            VideoGenerationJobService service) {
        ObjectProvider<VideoGenerationJobService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(service);
        return p;
    }

    @Test
    void noVideoBackendMeansUnavailable() {
        assertFalse(new ImageGenGeneratedVideoReader(provider(null)).isAvailable());
    }

    @Test
    void aCompletedJobIsHandedOverAsIs() {
        VideoGenerationJobService service = mock(VideoGenerationJobService.class);
        UUID job = UUID.randomUUID();
        InputStream stream = new ByteArrayInputStream(new byte[] {1, 2});
        when(service.openResultStream(job))
                .thenReturn(new VideoGenerationJobService.ResultStream(stream, "video/mp4", 2));
        GeneratedVideoReader reader = new ImageGenGeneratedVideoReader(provider(service));

        GeneratedVideoReader.VideoFile file = reader.open(job);

        assertTrue(reader.isAvailable());
        assertSame(stream, file.stream());
        assertEquals("video/mp4", file.contentType());
        assertEquals(2, file.contentLength());
    }

    @Test
    void anUnfinishedJobIsNotReady() {
        VideoGenerationJobService service = mock(VideoGenerationJobService.class);
        UUID job = UUID.randomUUID();
        when(service.openResultStream(job))
                .thenThrow(new ImageGenNotConfiguredException("not complete yet"));

        GeneratedVideoReader.NotReadyException e =
                assertThrows(
                        GeneratedVideoReader.NotReadyException.class,
                        () -> new ImageGenGeneratedVideoReader(provider(service)).open(job));
        assertEquals("not complete yet", e.getMessage());
    }
}
