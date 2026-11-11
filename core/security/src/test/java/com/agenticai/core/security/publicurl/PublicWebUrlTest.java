package com.agenticai.core.security.publicurl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * AGENTS.md rule 4 in code form: a browser-facing URL never falls back to {@code localhost} - a
 * missing {@code app.web.public-url} is either refused at boot or throws a clear {@link
 * IllegalStateException} naming the variable to set.
 */
class PublicWebUrlTest {

    @Test
    void isBlankCoversNullEmptyAndWhitespace() {
        assertThat(PublicWebUrl.isBlank(null)).isTrue();
        assertThat(PublicWebUrl.isBlank("")).isTrue();
        assertThat(PublicWebUrl.isBlank("   ")).isTrue();
        assertThat(PublicWebUrl.isBlank("https://app.example.com")).isFalse();
    }

    @Test
    void normalizeTrimsAndStripsEveryTrailingSlash() {
        assertThat(PublicWebUrl.normalize("  https://app.example.com///  "))
                .isEqualTo("https://app.example.com");
        assertThat(PublicWebUrl.normalize("https://app.example.com"))
                .isEqualTo("https://app.example.com");
        assertThat(PublicWebUrl.normalize(null)).isEqualTo("");
        assertThat(PublicWebUrl.normalize("   ")).isEqualTo("");
    }

    @Test
    void consoleRedirectPrefersTheExplicitUri() {
        assertThat(
                        PublicWebUrl.consoleRedirect(
                                " https://oauth.example.com/cb ",
                                "https://app.example.com",
                                "/settings"))
                .isEqualTo("https://oauth.example.com/cb");
    }

    @Test
    void consoleRedirectDerivesFromThePublicUrlOrStaysEmpty() {
        assertThat(
                        PublicWebUrl.consoleRedirect(
                                null, "https://app.example.com/", "/settings/integrations"))
                .isEqualTo("https://app.example.com/settings/integrations");
        assertThat(PublicWebUrl.consoleRedirect(" ", null, "/settings")).isEqualTo("");
        assertThat(PublicWebUrl.consoleRedirect(null, "", "/settings")).isEqualTo("");
    }

    @Test
    void requireReturnsTheValueOrThrowsNamingBothProperties() {
        assertThat(PublicWebUrl.require("https://app.example.com/x", "app.custom.uri"))
                .isEqualTo("https://app.example.com/x");

        assertThatThrownBy(() -> PublicWebUrl.require("", "app.custom.uri"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PublicWebUrl.PROPERTY)
                .hasMessageContaining("app.custom.uri")
                .hasMessageContaining("no localhost");
    }
}
