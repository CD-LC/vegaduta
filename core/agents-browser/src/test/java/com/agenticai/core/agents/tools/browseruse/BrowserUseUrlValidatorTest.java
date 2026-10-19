package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Domain-safety coverage for {@link BrowserUseUrlValidator} - the egress denylist {@code
 * browser_navigate} and every subsequent request on a browser-use session run through (see {@link
 * BrowserUseSessionManager}'s "Domain safety" javadoc section). Mirrors the shape of {@code
 * WebPageFetcher}'s own SSRF tests since the classification logic is the same by design - see the
 * class javadoc's "duplicated with a comment pointing at its twin" note.
 */
class BrowserUseUrlValidatorTest {

    @Test
    void refusesLoopbackByName() {
        assertThatThrownBy(
                        () ->
                                BrowserUseUrlValidator.validatePublicHttpUrl(
                                        "http://localhost:8080/"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("platform's own network");
    }

    @Test
    void refusesLoopbackByLiteralIp() {
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("http://127.0.0.1/"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("platform's own network");
    }

    @Test
    void refusesRfc1918PrivateRange() {
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("http://10.0.0.5/"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("platform's own network");
        assertThatThrownBy(
                        () -> BrowserUseUrlValidator.validatePublicHttpUrl("http://192.168.1.1/"))
                .isInstanceOf(BrowserUseException.class);
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("http://172.16.0.1/"))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void refusesLinkLocalAndCloudMetadataRange() {
        // 169.254.169.254 - the AWS/Azure/GCP instance-metadata address, the
        // single highest-value SSRF target of all.
        assertThatThrownBy(
                        () ->
                                BrowserUseUrlValidator.validatePublicHttpUrl(
                                        "http://169.254.169.254/"))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void refusesCgnatRange() {
        // 100.64.0.0/10 - Azure Container Apps / k8s overlays, per
        // ReservedAddressRanges' own javadoc.
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("http://100.64.0.1/"))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void refusesIpv6Loopback() {
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("http://[::1]/"))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void refusesIpv6UniqueLocal() {
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("http://[fc00::1]/"))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void allowsAPublicIpLiteral() {
        // 8.8.8.8 - resolves purely from the dotted-decimal literal, no real
        // DNS/network I/O involved, so this stays a fast, deterministic test.
        assertThat(BrowserUseUrlValidator.validatePublicHttpUrl("http://8.8.8.8/").getHost())
                .isEqualTo("8.8.8.8");
    }

    @Test
    void onlyHttpAndHttpsSchemesAreAllowed() {
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("file:///etc/passwd"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("http and https");
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("ftp://8.8.8.8/"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("http and https");
    }

    @Test
    void refusesEmbeddedCredentials() {
        assertThatThrownBy(
                        () ->
                                BrowserUseUrlValidator.validatePublicHttpUrl(
                                        "http://user:pass@8.8.8.8/"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("embedded credentials");
    }

    @Test
    void refusesBlankOrMissingUrl() {
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl(null))
                .isInstanceOf(BrowserUseException.class);
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("   "))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void refusesUrlWithNoHost() {
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl("http:///path"))
                .isInstanceOf(BrowserUseException.class);
    }

    @Test
    void refusesOverlongUrl() {
        String longUrl = "http://8.8.8.8/" + "a".repeat(3000);
        assertThatThrownBy(() -> BrowserUseUrlValidator.validatePublicHttpUrl(longUrl))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("too long");
    }
}
