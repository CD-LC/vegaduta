package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * {@link WebPageFetcher}'s SSRF validation, redirect re-validation, extraction and truncation - all
 * through package-private seams, deliberately without any live network I/O: every rejection below
 * either fails before address resolution (scheme/userinfo/no-host) or uses an IP literal, which
 * {@code InetAddress.getAllByName} classifies without a DNS round trip, so the suite is
 * deterministic on an offline CI runner. The one "accepted" fixture uses {@code 203.0.113.x}
 * (TEST-NET-3, RFC 5737) - a documentation range that classifies as PUBLIC, again with no DNS
 * involved.
 *
 * <p>The redirect defense is tested via {@link WebPageFetcher#nextHop} rather than end-to-end - see
 * that method's javadoc: every real hop passes through it by construction, and an end-to-end
 * version would need a live public web server willing to 302 into a private range.
 */
class WebPageFetcherTest {

    private final WebPageFetcher fetcher = new WebPageFetcher();

    // ------------------------------------------------------------------
    // SSRF: direct-URL rejections
    // ------------------------------------------------------------------

    @Test
    void rejectsLoopbackIpv4() {
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () -> fetcher.validatePublicHttpUri("http://127.0.0.1/admin"));
        assertTrue(e.getMessage().contains("platform's own network"));
    }

    @Test
    void rejectsRfc1918Private() {
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.validatePublicHttpUri("http://10.0.0.8/"));
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.validatePublicHttpUri("http://192.168.1.1/"));
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.validatePublicHttpUri("http://172.16.5.5/"));
    }

    @Test
    void rejectsLinkLocalMetadataAddress() {
        // The classic cloud-metadata SSRF target.
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.validatePublicHttpUri("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void rejectsIpv6Loopback() {
        assertThrows(
                WebPageFetchException.class, () -> fetcher.validatePublicHttpUri("http://[::1]/"));
    }

    @Test
    void rejectsIpv6UniqueLocal() {
        // fc00::/7 - not covered by isSiteLocalAddress, needs the explicit
        // byte check (see isNonPublic).
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.validatePublicHttpUri("http://[fd12:3456:789a::1]/"));
    }

    @Test
    void rejectsUnspecifiedAddress() {
        // 0.0.0.0 routes to "this host" on many stacks - an IP-literal
        // shortcut around the loopback check.
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.validatePublicHttpUri("http://0.0.0.0/"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () -> fetcher.validatePublicHttpUri("ftp://203.0.113.7/file"));
        assertTrue(e.getMessage().contains("http"));
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.validatePublicHttpUri("file:///etc/passwd"));
    }

    @Test
    void rejectsEmbeddedCredentials() {
        // "http://public@internal" userinfo confusion - same defense
        // McpRemoteUrlGuard applies to admin-registered MCP URLs.
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.validatePublicHttpUri("http://trusted.example@203.0.113.7/"));
    }

    @Test
    void rejectsBlankAndOversizedUrls() {
        assertThrows(WebPageFetchException.class, () -> fetcher.validatePublicHttpUri(null));
        assertThrows(WebPageFetchException.class, () -> fetcher.validatePublicHttpUri("  "));
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.validatePublicHttpUri("http://203.0.113.7/" + "a".repeat(3000)));
    }

    @Test
    void acceptsPublicAddress() {
        URI uri = fetcher.validatePublicHttpUri("https://203.0.113.7/page?q=1");
        assertEquals("203.0.113.7", uri.getHost());
    }

    // ------------------------------------------------------------------
    // SSRF: redirect hops re-validate
    // ------------------------------------------------------------------

    @Test
    void redirectToPrivateAddressIsRejected() {
        // A public host 302ing the fetcher into the internal network - the
        // attack manual redirect handling exists to stop.
        WebPageFetchException e =
                assertThrows(
                        WebPageFetchException.class,
                        () ->
                                fetcher.nextHop(
                                        URI.create("https://203.0.113.7/start"),
                                        "http://127.0.0.1:8080/actuator"));
        assertTrue(e.getMessage().contains("platform's own network"));
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.nextHop(URI.create("https://203.0.113.7/start"), "http://10.0.0.5/"));
    }

    @Test
    void redirectToNonHttpSchemeIsRejected() {
        assertThrows(
                WebPageFetchException.class,
                () ->
                        fetcher.nextHop(
                                URI.create("https://203.0.113.7/start"), "file:///etc/passwd"));
    }

    @Test
    void relativeRedirectResolvesAgainstCurrentHop() {
        URI next = fetcher.nextHop(URI.create("https://203.0.113.7/a/b"), "/moved");
        assertEquals("https://203.0.113.7/moved", next.toString());
    }

    @Test
    void redirectWithoutLocationIsRejected() {
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.nextHop(URI.create("https://203.0.113.7/start"), null));
        assertThrows(
                WebPageFetchException.class,
                () -> fetcher.nextHop(URI.create("https://203.0.113.7/start"), "  "));
    }

    // ------------------------------------------------------------------
    // Extraction + truncation
    // ------------------------------------------------------------------

    @Test
    void extractsReadableTextAndDropsScriptStyleAndTags() {
        String html =
                """
            <html><head><style>body { color: red; }</style>
            <script>alert('nope');</script></head>
            <body><h1>Title</h1><!-- hidden --><p>Hello &amp; welcome&nbsp;here.</p></body></html>
            """;
        String text = WebPageFetcher.extractText(html);
        assertEquals("Title Hello & welcome here.", text);
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("color"));
        assertFalse(text.contains("hidden"));
    }

    @Test
    void extractionIsCaseInsensitiveForBlockMarkers() {
        // The old regexes carried (?i) - the linear scanners must keep it.
        String html = "<HTML><STYLE>body{}</STYLE><SCRIPT>alert(1)</SCRIPT><P>Visible</P></HTML>";
        assertEquals("Visible", WebPageFetcher.extractText(html));
    }

    @Test
    void unclosedScriptBlockDegradesLikeTheOldRegex() {
        // No </script> anywhere: the block pass leaves it alone (the old
        // reluctant regex's no-match case, kept bit-for-bit), the tag pass
        // strips the <script> tag itself, and the script BODY surfaces as
        // text for the char clip to bound.
        assertEquals("hi var x = 1;", WebPageFetcher.extractText("<p>hi</p><script>var x = 1;"));
    }

    @Test
    void adversarialUnclosedTagSoupExtractsInLinearTime() {
        // 2026-08-12 DoS fix regression guard. Both shapes degraded the old
        // extraction to quadratic time (a measured ~80s for just 300KB of
        // unclosed "<script" through the reluctant-quantifier block regexes;
        // the '>'-free soup hit the same wall in the old <[^>]+> tag pass) -
        // at the fetcher's full 2MB response cap that was an effectively-hung
        // request thread, remotely steerable since the URL is LLM-supplied
        // and any public page can 302 into an attacker-controlled body. The
        // linear scanners chew through both in well under a second; the
        // generous preemptive bound exists so a regression FAILS the suite
        // instead of hanging it.
        String scriptSoup = "<script".repeat(300_000); // ~2.1MB, never closed
        String bracketSoup = "<a ".repeat(700_000); // ~2.1MB, no '>' at all
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10),
                () -> {
                    WebPageFetcher.extractText(scriptSoup);
                    WebPageFetcher.extractText(bracketSoup);
                });
    }

    @Test
    void clipTruncatesWithMarker() {
        String clipped = WebPageFetcher.clip("a".repeat(500), 300);
        assertEquals(300 + WebPageFetcher.TRUNCATION_MARKER.length(), clipped.length());
        assertTrue(clipped.endsWith(WebPageFetcher.TRUNCATION_MARKER));
    }

    @Test
    void clipLeavesShortTextAlone() {
        assertEquals("short", WebPageFetcher.clip("short", 300));
    }

    @Test
    void maxCharsDefaultsAndClamps() {
        assertEquals(WebPageFetcher.DEFAULT_MAX_CHARS, WebPageFetcher.clampMaxChars(null));
        assertEquals(WebPageFetcher.DEFAULT_MAX_CHARS, WebPageFetcher.clampMaxChars(0));
        assertEquals(WebPageFetcher.DEFAULT_MAX_CHARS, WebPageFetcher.clampMaxChars(-5));
        // Hard max is a cost ceiling - the tool result re-enters the prompt.
        assertEquals(WebPageFetcher.HARD_MAX_CHARS, WebPageFetcher.clampMaxChars(1_000_000));
        assertEquals(WebPageFetcher.MIN_MAX_CHARS, WebPageFetcher.clampMaxChars(3));
        assertEquals(5_000, WebPageFetcher.clampMaxChars(5_000));
    }
}
