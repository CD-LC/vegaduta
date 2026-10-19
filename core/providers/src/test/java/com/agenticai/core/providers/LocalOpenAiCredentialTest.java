package com.agenticai.core.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LocalOpenAiCredentialTest {

    @Test
    void roundTripsThroughTheJsonBlob() {
        LocalOpenAiCredential original = new LocalOpenAiCredential("http://localhost:8000/v1", "sk-1");

        assertThat(LocalOpenAiCredential.decode(original.encode())).isEqualTo(original);
    }

    @Test
    void aMissingKeyIsOmittedFromTheBlobAndReadsBackNull() {
        String json = new LocalOpenAiCredential("http://localhost:8000", null).encode();

        assertThat(json).doesNotContain("apiKey");
        assertThat(LocalOpenAiCredential.decode(json).hasApiKey()).isFalse();
    }

    @Test
    void aBlankKeyIsNotAKey() {
        assertThat(new LocalOpenAiCredential("http://h", "  ").hasApiKey()).isFalse();
        assertThat(new LocalOpenAiCredential("http://h", "k").hasApiKey()).isTrue();
    }

    @Test
    void malformedJsonFailsClosed() {
        assertThatThrownBy(() -> LocalOpenAiCredential.decode("{nope"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void apiRootStripsTheV1SuffixAndTrailingSlashesButKeepsSubPaths() {
        assertThat(LocalOpenAiCredential.apiRoot("http://h:8000/v1")).isEqualTo("http://h:8000");
        assertThat(LocalOpenAiCredential.apiRoot("http://h:8000/v1/")).isEqualTo("http://h:8000");
        assertThat(LocalOpenAiCredential.apiRoot("http://h:8000//")).isEqualTo("http://h:8000");
        assertThat(LocalOpenAiCredential.apiRoot("  http://h:8000/V1  ")).isEqualTo("http://h:8000");
        assertThat(LocalOpenAiCredential.apiRoot("https://gw.example.com/openai/v1")).isEqualTo("https://gw.example.com/openai");
        assertThat(LocalOpenAiCredential.apiRoot("http://h/api/v2")).isEqualTo("http://h/api/v2");
        assertThat(LocalOpenAiCredential.apiRoot(null)).isEmpty();
    }

    @Test
    void allowListEntryIsHostAndExplicitOrDefaultPort() {
        assertThat(new LocalOpenAiCredential("http://Localhost:8000/v1", null).allowListEntry()).isEqualTo("localhost:8000");
        assertThat(new LocalOpenAiCredential("http://vllm.lan/v1", null).allowListEntry()).isEqualTo("vllm.lan:80");
        assertThat(new LocalOpenAiCredential("https://vllm.lan", null).allowListEntry()).isEqualTo("vllm.lan:443");
        assertThat(new LocalOpenAiCredential("::not a url::", null).allowListEntry()).isNull();
    }

    @Test
    void loopbackNamesAreRecognised() {
        assertThat(new LocalOpenAiCredential("http://localhost:1234/v1", null).pointsAtLoopback()).isTrue();
        assertThat(new LocalOpenAiCredential("http://127.0.0.1:1234/v1", null).pointsAtLoopback()).isTrue();
        assertThat(new LocalOpenAiCredential("http://[::1]:1234/v1", null).pointsAtLoopback()).isTrue();
        assertThat(new LocalOpenAiCredential("http://host.docker.internal:1234/v1", null).pointsAtLoopback()).isFalse();
        assertThat(new LocalOpenAiCredential("http://10.0.0.5:1234/v1", null).pointsAtLoopback()).isFalse();
    }
}
