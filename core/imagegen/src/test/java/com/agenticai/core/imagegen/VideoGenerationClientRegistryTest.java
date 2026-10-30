package com.agenticai.core.imagegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link VideoGenerationClientRegistry}'s lookup: a registered provider hands back its
 * client, an unregistered one fails as a 400 not-configured, and the {@link VideoGenerationClient}
 * value records round-trip.
 */
class VideoGenerationClientRegistryTest {

    @Test
    void getReturnsTheClientRegisteredForTheProvider() {
        VideoGenerationClient veo = mock(VideoGenerationClient.class);
        when(veo.supportedProvider()).thenReturn(ProviderName.GOOGLE_VERTEX);

        VideoGenerationClientRegistry registry = new VideoGenerationClientRegistry(List.of(veo));

        assertThat(registry.get(ProviderName.GOOGLE_VERTEX)).isSameAs(veo);
    }

    @Test
    void anUnregisteredProviderFailsAsNotConfigured() {
        VideoGenerationClientRegistry registry = new VideoGenerationClientRegistry(List.of());

        assertThatThrownBy(() -> registry.get(ProviderName.OPENAI))
                .isInstanceOf(ImageGenNotConfiguredException.class)
                .hasMessageContaining("OPENAI");
    }

    @Test
    void generatedVideoAndSourceImageRecordsArePlainCarriers() {
        byte[] bytes = {1};
        VideoGenerationClient.GeneratedVideo v =
                new VideoGenerationClient.GeneratedVideo(bytes, "video/mp4");
        assertThat(v.bytes()).isSameAs(bytes);
        assertThat(v.contentType()).isEqualTo("video/mp4");

        VideoGenerationClient.SourceImage img =
                new VideoGenerationClient.SourceImage("QUJD", "image/png");
        assertThat(img.bytesBase64()).isEqualTo("QUJD");
        assertThat(img.mimeType()).isEqualTo("image/png");
    }
}
