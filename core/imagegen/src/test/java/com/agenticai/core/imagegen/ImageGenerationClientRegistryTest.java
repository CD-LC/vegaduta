package com.agenticai.core.imagegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ImageGenerationClientRegistry}'s dispatch: clients register by their {@link
 * ImageGenerationClient#supportedProvider}, {@code supports} mirrors registration, {@code generate}
 * forwards verbatim and an unregistered provider fails as a 400 not-configured.
 */
class ImageGenerationClientRegistryTest {

    @Test
    void generateDispatchesToTheClientRegisteredForTheProvider() {
        ImageGenerationClient openai = mock(ImageGenerationClient.class);
        ImageGenerationClient vertex = mock(ImageGenerationClient.class);
        when(openai.supportedProvider()).thenReturn(ProviderName.OPENAI);
        when(vertex.supportedProvider()).thenReturn(ProviderName.GOOGLE_VERTEX);
        ImageGenerationClient.GeneratedImage expected =
                new ImageGenerationClient.GeneratedImage(new byte[] {1, 2}, "image/png", "dall-e-3");
        when(openai.generate("key-1", "a cat", "dall-e-3")).thenReturn(expected);

        ImageGenerationClientRegistry registry =
                new ImageGenerationClientRegistry(List.of(openai, vertex));

        assertThat(registry.generate(ProviderName.OPENAI, "key-1", "a cat", "dall-e-3"))
                .isSameAs(expected);
        verify(openai).generate("key-1", "a cat", "dall-e-3");
    }

    @Test
    void supportsMirrorsWhichProvidersRegistered() {
        ImageGenerationClient openai = mock(ImageGenerationClient.class);
        when(openai.supportedProvider()).thenReturn(ProviderName.OPENAI);

        ImageGenerationClientRegistry registry = new ImageGenerationClientRegistry(List.of(openai));

        assertThat(registry.supports(ProviderName.OPENAI)).isTrue();
        assertThat(registry.supports(ProviderName.GOOGLE_VERTEX)).isFalse();
    }

    @Test
    void anUnregisteredProviderFailsAsNotConfigured() {
        ImageGenerationClientRegistry registry = new ImageGenerationClientRegistry(List.of());

        assertThatThrownBy(() -> registry.generate(ProviderName.GROQ, "k", "p", "m"))
                .isInstanceOf(ImageGenNotConfiguredException.class)
                .hasMessageContaining("GROQ");
    }

    @Test
    void generatedImageRecordCarriesBytesTypeAndModel() {
        byte[] bytes = {9, 8, 7};
        ImageGenerationClient.GeneratedImage g =
                new ImageGenerationClient.GeneratedImage(bytes, "image/webp", "imagen-3");

        assertThat(g.bytes()).isSameAs(bytes);
        assertThat(g.contentType()).isEqualTo("image/webp");
        assertThat(g.model()).isEqualTo("imagen-3");
    }
}
