package com.agenticai.core.agents.demo.magic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link SafePublicUrlFetcher}'s HTML-&gt;text extraction, through the
 * package-private {@code extractText} seam - deliberately no network I/O, so
 * the suite is deterministic on an offline CI runner. The SSRF/redirect
 * checks are structurally identical to {@code WebPageFetcher}'s and covered
 * by that class's suite; what THIS suite pins down is the 2026-08-12
 * catastrophic-backtracking fix ported from there (see
 * {@code extractText}'s javadoc).
 */
class SafePublicUrlFetcherTest {

    @Test
    void extractsReadableTextAndDropsScriptStyleAndTags() {
        String html = """
            <html><head><style>body { color: red; }</style>
            <script>alert('nope');</script></head>
            <body><h1>Title</h1><!-- hidden --><p>Hello &amp; welcome&nbsp;here.</p></body></html>
            """;
        String text = SafePublicUrlFetcher.extractText(html);
        assertEquals("Title Hello & welcome here.", text);
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("color"));
        assertFalse(text.contains("hidden"));
    }

    @Test
    void extractionIsCaseInsensitiveForBlockMarkers() {
        // The old regexes carried (?i) - the linear scanners must keep it.
        String html = "<HTML><STYLE>body{}</STYLE><SCRIPT>alert(1)</SCRIPT><P>Visible</P></HTML>";
        assertEquals("Visible", SafePublicUrlFetcher.extractText(html));
    }

    @Test
    void unclosedScriptBlockDegradesLikeTheOldRegex() {
        // No </script> anywhere: the block pass leaves it alone (the old
        // reluctant regex's no-match case, kept bit-for-bit), the tag pass
        // strips the <script> tag itself, and the script BODY surfaces as
        // text for the downstream char clip to bound.
        assertEquals("hi var x = 1;", SafePublicUrlFetcher.extractText("<p>hi</p><script>var x = 1;"));
    }

    @Test
    void bareAngleBracketPairStaysAsText() {
        // The old <[^>]+> pattern required at least one char inside the tag.
        assertEquals("a <> b", SafePublicUrlFetcher.extractText("a <> b"));
    }

    @Test
    void adversarialUnclosedTagSoupExtractsInLinearTime() {
        // 2026-08-12 DoS fix regression guard, ported with the fix from
        // WebPageFetcherTest. Both shapes degraded the old extraction to
        // quadratic time (a measured ~80s for just 300KB of unclosed
        // "<script" through the reluctant-quantifier block regexes; the
        // '>'-free soup hit the same wall in the old <[^>]+> tag pass).
        // This fetcher caps the body at 256KB, but the URL comes from an
        // anonymous visitor's form field, so even that was a remotely
        // triggerable request-thread hang. The linear scanners chew through
        // both in well under a second; the generous preemptive bound exists
        // so a regression FAILS the suite instead of hanging it.
        String scriptSoup = "<script".repeat(45_000); // ~315KB, never closed
        String bracketSoup = "<a ".repeat(105_000);   // ~315KB, no '>' at all
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            SafePublicUrlFetcher.extractText(scriptSoup);
            SafePublicUrlFetcher.extractText(bracketSoup);
        });
    }
}
