package com.agenticai.core.agents.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebSearchHttpTest {

    @Test
    void aResponseLargerThanTheCapIsTruncatedNotBuffered() throws IOException {
        byte[] huge = new byte[WebSearchHttp.MAX_BODY_BYTES + 50_000];
        java.util.Arrays.fill(huge, (byte) 'a');
        String read = WebSearchHttp.readCapped(new ByteArrayInputStream(huge));
        assertThat(read).hasSize(WebSearchHttp.MAX_BODY_BYTES);
    }

    @Test
    void aSmallResponseIsReadWhole() throws IOException {
        String body = "{\"results\":[]}";
        assertThat(
                        WebSearchHttp.readCapped(
                                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(body);
    }

    @Test
    void onlyHttpAndHttpsUrlsMayBecomeResults() {
        assertThat(WebSearchHttp.isWebUrl("https://example.com/a")).isTrue();
        assertThat(WebSearchHttp.isWebUrl("HTTP://example.com/a")).isTrue();
        assertThat(WebSearchHttp.isWebUrl("javascript:alert(1)")).isFalse();
        assertThat(WebSearchHttp.isWebUrl("data:text/html,x")).isFalse();
        assertThat(WebSearchHttp.isWebUrl("file:///etc/passwd")).isFalse();
        assertThat(WebSearchHttp.isWebUrl("//example.com/a")).isFalse();
        assertThat(WebSearchHttp.isWebUrl(null)).isFalse();
        assertThat(WebSearchHttp.isWebUrl("https://example.com/" + "a".repeat(3000))).isFalse();
    }

    @Test
    void queriesAreTrimmedToTheSharedLimit() {
        assertThat(WebSearchHttp.requireQuery("  hello  ")).isEqualTo("hello");
        assertThat(WebSearchHttp.requireQuery("x".repeat(1000)))
                .hasSize(WebSearchHttp.MAX_QUERY_CHARS);
    }

    @Test
    void datesAreShortened() {
        assertThat(WebSearchHttp.shortDate("2026-01-15T00:00:00Z")).isEqualTo("2026-01-15");
        assertThat(WebSearchHttp.shortDate("  ")).isNull();
        assertThat(WebSearchHttp.shortDate(null)).isNull();
        assertThat(WebSearchHttp.shortDate("3 days ago")).isEqualTo("3 days ago");
    }
}
