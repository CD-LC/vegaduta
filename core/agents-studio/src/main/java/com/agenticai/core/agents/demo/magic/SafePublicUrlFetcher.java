package com.agenticai.core.agents.demo.magic;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fetches PUBLIC web pages for the Magic Studio knowledge step ("build a
 * knowledge base by scraping publicly available web data") - with the SSRF
 * posture an unauthenticated-input fetcher must have, since the URL comes
 * straight from an anonymous visitor's form field:
 *
 * <ul>
 *   <li>http/https only - no file:, ftp:, gopher:, jar: or anything else.</li>
 *   <li>Classification is the shared {@link SsrfGuard} (security review
 *       SEC-027): every resolved address must be public (loopback, RFC-1918,
 *       link-local/metadata, CGNAT, Azure WireServer, reserved, multicast,
 *       NAT64 and IPv6 forms embedding an internal IPv4 all refuse) and
 *       internal names are refused by name. The STRICT guard is used - the
 *       operator allow-list for self-hosted model servers never applies to
 *       an anonymous visitor's URL.</li>
 *   <li>The connection is pinned: the client ({@link SafeHttpClients})
 *       connects only to an address the guard validated in the same DNS
 *       lookup, so a rebinding DNS answer cannot reach an internal host.</li>
 *   <li>Redirects are followed manually (max 3) so EVERY hop re-runs the
 *       full check.</li>
 *   <li>Read is capped (256 KB) and timed out (5s connect / 8s read); the
 *       extracted text is capped again downstream. A slow or huge page
 *       degrades the knowledge step, never the request thread pool.</li>
 * </ul>
 *
 * <p>Extraction is deliberately crude - strip scripts/styles/tags, collapse
 * whitespace. The consumer is an LLM prompt, not a renderer; readable-enough
 * beats a new HTML-parsing dependency in the core.
 *
 * <p>Every failure path returns {@link Optional#empty()} - the knowledge step
 * is best-effort garnish on the workflow, and the demo surface's contract is
 * that nothing optional ever breaks the visible flow.
 */
@Component
public class SafePublicUrlFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafePublicUrlFetcher.class);

    private static final int MAX_REDIRECTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_BYTES = 256 * 1024;
    /** What the extracted text is clipped to before prompt injection. */
    private static final int MAX_TEXT_CHARS = 4_000;

    private final SsrfGuard guard;
    private final CloseableHttpClient httpClient;

    public SafePublicUrlFetcher() {
        this(SsrfGuard.strict());
    }

    /** Test seam: a guard with a fake resolver / allow-list. */
    SafePublicUrlFetcher(SsrfGuard guard) {
        this.guard = guard;
        this.httpClient = SafeHttpClients.create(guard, CONNECT_TIMEOUT, READ_TIMEOUT, false);
    }

    public record FetchedPage(String url, String textExcerpt) {
    }

    public Optional<FetchedPage> fetchText(String rawUrl) {
        String current = rawUrl == null ? "" : rawUrl.strip();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = parsePublicHttpUri(current).orElse(null);
            if (uri == null) {
                return Optional.empty();
            }
            HttpGet request = new HttpGet(uri);
            request.addHeader("User-Agent", "VegaDuta-MagicStudio/1.0 (+https://github.com/CD-LC/vegaduta)");
            request.addHeader("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
            ClassicHttpResponse response = null;
            try {
                response = httpClient.executeOpen(null, request, null);
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    Header location = response.getFirstHeader("Location");
                    if (location == null || location.getValue() == null || location.getValue().isBlank()) {
                        return Optional.empty();
                    }
                    // Relative redirects resolve against the current hop;
                    // the next loop iteration re-runs the full address check.
                    current = uri.resolve(location.getValue().strip()).toString();
                    continue;
                }
                if (status != 200) {
                    return Optional.empty();
                }
                Header contentTypeHeader = response.getFirstHeader("Content-Type");
                String contentType = contentTypeHeader == null ? null : contentTypeHeader.getValue();
                if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("text")
                    && !contentType.toLowerCase(Locale.ROOT).contains("json")
                    && !contentType.toLowerCase(Locale.ROOT).contains("xml")) {
                    return Optional.empty();
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    return Optional.empty();
                }
                // Non-closing view: readCapped closes its stream, and closing an
                // Apache entity stream drains the rest - the finally cancels instead.
                byte[] raw = readCapped(new java.io.FilterInputStream(entity.getContent()) {
                    @Override
                    public void close() {
                    }
                });
                String text = extractText(new String(raw, StandardCharsets.UTF_8));
                if (text.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new FetchedPage(
                    uri.toString(),
                    text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text
                ));
            } catch (IOException | RuntimeException e) {
                log.debug("Magic knowledge fetch failed for {}: {}", current, e.toString());
                return Optional.empty();
            } finally {
                // Cancel first: closing an unconsumed response would otherwise
                // drain the rest of the body instead of dropping the connection.
                request.cancel();
                if (response != null) {
                    try {
                        response.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------

    /** Parses, scheme-checks, resolves and address-checks via the shared guard - empty on ANY doubt. */
    private Optional<URI> parsePublicHttpUri(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) {
            return Optional.empty();
        }
        try {
            return Optional.of(guard.validate(raw).uri());
        } catch (SsrfBlockedException e) {
            log.info("Magic knowledge fetch refused a URL: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        try (in) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() >= MAX_BYTES) {
                    break;
                }
            }
            return out.toByteArray();
        }
    }

    /**
     * Crude, dependency-free HTML -> text: drop script/style/comment bodies,
     * strip tags, collapse whitespace. Package-private test seam.
     *
     * <p>2026-08-12 DoS fix (ported from {@code WebPageFetcher#extractText},
     * which mirrors this class): the block-dropping step used to be three
     * reluctant-quantifier regexes ({@code (?is)<script.*?</script>} etc.)
     * run over the raw (up-to-256KB, NOT yet char-clipped) body. On a page
     * full of unclosed {@code <script} tokens each match attempt scans to the
     * end of the input before failing, degrading to quadratic time - a
     * measured ~80 seconds for 300KB of that shape - and nothing interrupts
     * it (the connect/read timeouts only bound the network I/O). The URL
     * comes straight from an anonymous visitor's form field and any public
     * host can 302 into an attacker-controlled body, so that was a remotely
     * triggerable request-thread-exhaustion vector. {@link #stripBlocks} is
     * the strictly-linear single-pass replacement with the same semantics.
     * The generic tag-stripper had the same quadratic worst case for the
     * same reason ({@code <[^>]+>} over a body full of {@code <} with no
     * {@code >}: every attempt scans-then-backtracks to the end of input),
     * so it is replaced by the equally-linear {@link #stripTags}.
     */
    static String extractText(String html) {
        String withoutBlocks = stripBlocks(stripBlocks(stripBlocks(
            html, "<script", "</script>"), "<style", "</style>"), "<!--", "-->");
        String withoutTags = stripTags(withoutBlocks);
        return withoutTags
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replaceAll("\\s+", " ")
            .strip();
    }

    /**
     * Linear-time, case-insensitive replacement for the old {@code
     * (?is)<open.*?close} block-dropping regexes (see {@link #extractText}'s
     * DoS note). Each {@code open}..{@code close} span is replaced with a
     * single space; an {@code open} with no later {@code close} is left
     * untouched, exactly like the old reluctant regex's no-match case (the
     * generic tag-stripper then handles whatever remains). Single forward
     * scan - every index strictly advances, so the worst case is O(n × the
     * tiny fixed marker length) regardless of how adversarial the input is.
     * (Deliberately NOT implemented over a {@code toLowerCase} copy of the
     * whole body: full-string lowercasing can change the string's LENGTH for
     * some code points - e.g. U+0130 - which would silently desync the two
     * strings' indices; {@code regionMatches(true, ...)} compares in place.)
     */
    private static String stripBlocks(String html, String open, String close) {
        StringBuilder out = null;
        int from = 0;
        while (true) {
            int start = indexOfIgnoreCase(html, open, from);
            int end = start < 0 ? -1 : indexOfIgnoreCase(html, close, start + open.length());
            if (end < 0) {
                // No (further) complete block - common enough (most pages
                // after the first pass) to skip allocating anything.
                return out == null ? html : out.append(html, from, html.length()).toString();
            }
            if (out == null) {
                out = new StringBuilder(html.length());
            }
            out.append(html, from, start).append(' ');
            from = end + close.length();
        }
    }

    /**
     * Linear-time replacement for the old {@code (?s)<[^>]+>} tag-stripper
     * (see {@link #extractText}'s DoS note), with the same semantics: a
     * {@code <}..{@code >} span (at least one char between them, so a bare
     * {@code <>} stays, exactly like the {@code +} in the old pattern) is
     * replaced with a single space; a {@code <} with no later {@code >}
     * matches nothing and is left as text.
     */
    private static String stripTags(String html) {
        StringBuilder out = null;
        // Two cursors: `from` trails at the start of the not-yet-copied text,
        // `search` leads to where the next '<' hunt resumes - they only
        // diverge across the "<>" case below, where the skipped-over text
        // must still be copied by a LATER real match's append.
        int from = 0;
        int search = 0;
        while (true) {
            int start = html.indexOf('<', search);
            int end = start < 0 ? -1 : html.indexOf('>', start + 1);
            if (end < 0) {
                return out == null ? html : out.append(html, from, html.length()).toString();
            }
            if (end == start + 1) {
                // "<>" - the old regex required at least one char inside.
                // Not a tag; leave it as text and keep hunting after the '<'.
                search = start + 1;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(html.length());
            }
            out.append(html, from, start).append(' ');
            from = end + 1;
            search = from;
        }
    }

    /** ASCII-marker case-insensitive indexOf, comparing in place (see {@link #stripBlocks}). */
    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        for (int i = Math.max(from, 0), max = haystack.length() - needle.length(); i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}
