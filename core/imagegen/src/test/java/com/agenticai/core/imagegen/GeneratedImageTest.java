package com.agenticai.core.imagegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.imagegen.dto.GeneratedImageResponse;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GeneratedImage}'s stored-image row and {@link GeneratedImageResponse#from}'s
 * listing mapping - {@code downloadUrl} points back at this app's own authenticated endpoint.
 */
class GeneratedImageTest {

    private static GeneratedImage image() {
        return GeneratedImage.create(
                "sunset.png",
                "image/png",
                "generated-images/abc.png",
                "a sunset over the ghats",
                "dall-e-3",
                "1024x1024",
                "openai");
    }

    @Test
    void createCarriesEveryColumn() {
        GeneratedImage img = image();

        assertThat(img.getFilename()).isEqualTo("sunset.png");
        assertThat(img.getContentType()).isEqualTo("image/png");
        assertThat(img.getObjectKey()).isEqualTo("generated-images/abc.png");
        assertThat(img.getPrompt()).isEqualTo("a sunset over the ghats");
        assertThat(img.getModel()).isEqualTo("dall-e-3");
        assertThat(img.getSize()).isEqualTo("1024x1024");
        assertThat(img.getProvider()).isEqualTo("openai");
        assertThat(img.getCreatedAt()).isNotNull();
        assertThat(img.getId()).isNull(); // assigned on persist
    }

    @Test
    void responseFromBuildsTheAppDownloadUrl() {
        GeneratedImage img = image();

        GeneratedImageResponse r = GeneratedImageResponse.from(img);

        assertThat(r.filename()).isEqualTo("sunset.png");
        assertThat(r.contentType()).isEqualTo("image/png");
        assertThat(r.prompt()).isEqualTo("a sunset over the ghats");
        assertThat(r.model()).isEqualTo("dall-e-3");
        assertThat(r.size()).isEqualTo("1024x1024");
        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.downloadUrl()).isEqualTo("/api/imagegen/images/" + img.getId());
    }
}
