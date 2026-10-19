package com.agenticai.core.agents.tools.web;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches a PUBLIC web page and reduces it to plain text for an agent turn - the
 * fetch/SSRF/extraction half of the {@code fetch_web_page} built-in tool (see {@link
 * FetchWebPageToolCallback} for the tool-facing half). Exists because agents had NO built-in web
 * capability at all - a "summarize this article" request got "I cannot browse websites" with zero
 * tool calls, since nothing resolvable by {@code AgentToolProvider} could do an HTTP GET.
 *
 * <p><b>SSRF posture</b> - the URL comes straight out of an LLM tool call, which itself is steered
 * by arbitrary tenant/user text. Since security review SEC-027 every check is the shared {@link
 * SsrfGuard}:
 *
 * <ul>
 *   <li>http/https only, no embedded credentials.
 *   <li>Every resolved address must be PUBLIC - the guard's single classification (loopback,
 *       RFC-1918, link-local/metadata, CGNAT, Azure WireServer, reserved, multicast, NAT64, IPv6
 *       forms embedding an internal IPv4) - and internal names (single-label compose names, {@code
 *       *.internal}, {@code localhost}...) are refused by name.
 *   <li><b>Pinned connection.</b> The HTTP client is built by {@link SafeHttpClients}: it resolves
 *       through the guard's resolver and connects only to an address validated in that same lookup,
 *       so a DNS answer that changes between the check and the connect cannot reach an internal
 *       host. No system proxy is used.
 *   <li>Redirects are followed manually (client redirects off + a bounded loop, max {@value
 *       #MAX_REDIRECTS} hops) so EVERY hop re-runs the full check.
 *   <li>The strict guard is used: the operator's {@code
 *       app.security.outbound.allowed-private-hosts} escape hatch (for self-hosted model servers)
 *       does NOT extend to model-chosen URLs.
 *   <li>Raw response read is capped at {@value #MAX_RESPONSE_BYTES} bytes and timed out (10s
 *       connect / 10s response); the extracted text is capped again at {@code maxChars} (default
 *       {@value #DEFAULT_MAX_CHARS}, hard max {@value #HARD_MAX_CHARS}) with a {@code [truncated]}
 *       marker - a tool result feeds straight back into the prompt, so an uncapped page would be an
 *       uncapped per-turn token bill.
 * </ul>
 *
 * <p>Extraction is deliberately crude - drop script/style/comment blocks, strip tags, decode common
 * entities, collapse whitespace. The consumer is an LLM prompt, not a renderer; jsoup is NOT on the
 * core classpath (checked 2026-08-12) and readable-enough does not justify adding a new dependency,
 * the same call SafePublicUrlFetcher's javadoc already made.
 *
 * <p>Plain class, not a Spring bean: zero service dependencies, so {@code AgentToolProvider} holds
 * one shared instance directly instead of adding yet another parameter to its ~40-arg constructor.
 */
public class WebPageFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebPageFetcher.class);

    static final int DEFAULT_MAX_CHARS = 8_000;
    static final int HARD_MAX_CHARS = 20_000;

    /** Floor keeps a nonsensical-but-positive maxChars (e.g. 3) from producing a useless result. */
    static final int MIN_MAX_CHARS = 200;

    static final String TRUNCATION_MARKER = "\n[truncated]";

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_URL_LENGTH = 2_048;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String INTERNAL = "the URL points inside the platform's own network";

    private final SsrfGuard guard;
    private final CloseableHttpClient httpClient;

    public WebPageFetcher() {
        this(SsrfGuard.strict());
    }

    /** Test seam: a guard with a fake resolver / allow-list. */
    WebPageFetcher(SsrfGuard guard) {
        this.guard = guard;
        this.httpClient = SafeHttpClients.create(guard, CONNECT_TIMEOUT, REQUEST_TIMEOUT, false);
    }

    /**
     * Fetches {@code rawUrl} and returns its extracted plain text, clipped to {@code maxChars} (see
     * {@link #clampMaxChars}). Throws {@link WebPageFetchException} with a person-readable reason
     * on every failure path - never a raw {@code IOException} - so {@link FetchWebPageToolCallback}
     * can relay {@code "Could not fetch <url>: <reason>"} without translating anything.
     */
    public String fetch(String rawUrl, Integer requestedMaxChars) {
        int maxChars = clampMaxChars(requestedMaxChars);
        URI current = validatePublicHttpUri(rawUrl);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpGet request = new HttpGet(current);
            request.addHeader(
                    "User-Agent",
                    "VegaDuta-Agent/1.0 (+https://github.com/CD-LC/vegaduta)");
            request.addHeader(
                    "Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
            // executeOpen, not a response handler, and CANCEL before close:
            // closing an unconsumed Apache response drains the rest of the
            // body, so the exchange is cancelled (connection dropped) first -
            // a capped read of a huge or endless page stops at the cap.
            ClassicHttpResponse response = open(request);
            try {
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    // Re-validated inside nextHop - every hop gets the full
                    // scheme/host/address check, never just the first URL.
                    current = nextHop(current, headerValue(response, "Location"));
                    continue;
                }
                if (status != 200) {
                    throw new WebPageFetchException("the page returned HTTP " + status);
                }
                String contentType = headerValue(response, "Content-Type");
                contentType = contentType == null ? "" : contentType;
                if (!isTextual(contentType)) {
                    throw new WebPageFetchException(
                            "the page is not text (Content-Type: " + contentType + ")");
                }
                HttpEntity entity = response.getEntity();
                byte[] raw =
                        entity == null
                                ? new byte[0]
                                : readCapped(entity.getContent(), BODY_DEADLINE, request::cancel);
                String text = extractText(new String(raw, StandardCharsets.UTF_8));
                if (text.isBlank()) {
                    throw new WebPageFetchException("the page contained no readable text");
                }
                return clip(text, maxChars);
            } catch (IOException e) {
                throw new WebPageFetchException("the page stopped responding mid-download");
            } finally {
                request.cancel();
                try {
                    response.close();
                } catch (IOException ignored) {
                    // best effort - the connection is being abandoned either way
                }
            }
        }
        throw new WebPageFetchException(
                "the page redirected more than " + MAX_REDIRECTS + " times");
    }

    // ------------------------------------------------------------------

    private ClassicHttpResponse open(HttpGet request) {
        try {
            return httpClient.executeOpen(null, request, null);
        } catch (SsrfGuard.BlockedAddressException e) {
            // The guard's resolver refused at connect time (e.g. the DNS
            // answer changed since validation).
            log.info("fetch_web_page refused a non-public address at connect time");
            throw new WebPageFetchException(INTERNAL);
        } catch (IOException e) {
            // Connection refused / timeout / TLS failure - all "unreachable"
            // to the person on the other end; the detail goes to the log, not
            // the model (an exception message can carry internal hostnames).
            log.debug(
                    "fetch_web_page could not reach {}: {}", request.getAuthority(), e.toString());
            throw new WebPageFetchException("the page could not be reached");
        }
    }

    private static String headerValue(ClassicHttpResponse response, String name) {
        Header header = response.getFirstHeader(name);
        return header == null ? null : header.getValue();
    }

    /**
     * Resolves a redirect's {@code Location} header against the hop it came from (relative
     * redirects are legal) and re-runs the FULL public-address validation on the result.
     * Package-private as the direct unit-test seam for "a public host redirecting into the private
     * network is refused".
     */
    URI nextHop(URI from, String location) {
        if (location == null || location.isBlank()) {
            throw new WebPageFetchException("the page redirected without saying where to");
        }
        String resolved;
        try {
            resolved = from.resolve(location.strip()).toString();
        } catch (IllegalArgumentException e) {
            throw new WebPageFetchException("the page redirected to an invalid address");
        }
        return validatePublicHttpUri(resolved);
    }

    /**
     * Parses, scheme-checks, resolves and address-checks in one place via the shared {@link
     * SsrfGuard} - throws a reasoned {@link WebPageFetchException} on ANY doubt. Package-private
     * test seam.
     */
    URI validatePublicHttpUri(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new WebPageFetchException("no URL was given");
        }
        if (raw.length() > MAX_URL_LENGTH) {
            throw new WebPageFetchException("the URL is too long");
        }
        String scheme = schemeOf(raw.strip());
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new WebPageFetchException("only http and https URLs can be fetched");
        }
        if (isNonCanonicalNumericHost(hostOf(raw.strip()))) {
            // 0177.0.0.1 (octal), 0x7f.1, 2130706433 (dword), 127.1: glibc reads these as the
            // loopback address while
            // other resolvers read the same text as a PUBLIC address, so the check below could pass
            // on one OS and the
            // connection land on 127.0.0.1 on another. A real site never uses them: refuse
            // outright.
            log.info("fetch_web_page refused a non-canonical numeric host");
            throw new WebPageFetchException(INTERNAL);
        }
        try {
            return guard.validate(raw.strip()).uri();
        } catch (SsrfBlockedException e) {
            String reason = e.getMessage();
            if (reason.contains("private") || reason.contains("internal")) {
                log.info("fetch_web_page refused a non-public address");
                throw new WebPageFetchException(INTERNAL);
            }
            throw new WebPageFetchException(reason);
        }
    }

    private static String schemeOf(String url) {
        int colon = url.indexOf(':');
        return colon < 0 ? "" : url.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /** The URL's host, or "" when it does not parse (the guard then reports the precise reason). */
    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException e) {
            return "";
        }
    }

    private static final java.util.regex.Pattern NUMERIC_HOST =
            java.util.regex.Pattern.compile(
                    "^(0[xX][0-9a-fA-F]+|[0-9]+)([.](0[xX][0-9a-fA-F]+|[0-9]+))*$");
    private static final java.util.regex.Pattern CANONICAL_IPV4 =
            java.util.regex.Pattern.compile(
                    "^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])([.](25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$");

    /**
     * True for an all-numeric host (decimal, octal, hex, shorthand) that is not a plain
     * dotted-decimal IPv4 quad.
     */
    static boolean isNonCanonicalNumericHost(String host) {
        return NUMERIC_HOST.matcher(host).matches() && !CANONICAL_IPV4.matcher(host).matches();
    }

    /**
     * Blank Content-Type is allowed (plenty of small sites omit it and the extractor degrades
     * harmlessly on non-HTML text); declared binary types refuse - decoding a JPEG as UTF-8 wastes
     * the whole 2MB budget on noise.
     */
    private static boolean isTextual(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("text")
                || lower.contains("html")
                || lower.contains("xml")
                || lower.contains("json");
    }

    /**
     * Wall-clock budget for reading one body: the request timeout above only covers the response
     * headers.
     */
    static final Duration BODY_DEADLINE = Duration.ofSeconds(20);

    /**
     * Daemon watchdog that closes a body stream once its deadline passes, so a stalled read cannot
     * hang.
     */
    private static final java.util.concurrent.ScheduledExecutorService BODY_WATCHDOG =
            TenantAwareExecutors.newSingleThreadScheduledExecutor("web-page-body-watchdog", true);

    /**
     * Reads at most {@link #MAX_RESPONSE_BYTES}, giving up after {@code deadline} of wall-clock
     * time. A slow-drip server (a byte every few seconds) otherwise holds the worker for as long as
     * it likes: the deadline is checked between reads AND a watchdog closes the stream so a read
     * blocked on a silent socket is released too. Closes {@code in}. Package-private test seam.
     */
    static byte[] readCapped(InputStream in, Duration deadline) {
        try {
            return readCapped(in, deadline, () -> closeQuietly(in));
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * The bounded read itself. Does NOT close {@code in}: on the fetch path the caller cancels the
     * exchange (closing an Apache entity stream would drain the remainder of the body), and on the
     * deadline {@code abortOnDeadline} runs - the fetch path passes {@code request::cancel}, which
     * drops the connection and releases a read blocked on a silent socket.
     */
    private static byte[] readCapped(InputStream in, Duration deadline, Runnable abortOnDeadline) {
        long deadlineAt = System.nanoTime() + deadline.toNanos();
        java.util.concurrent.ScheduledFuture<?> watchdog =
                BODY_WATCHDOG.schedule(
                        abortOnDeadline,
                        deadline.toNanos(),
                        java.util.concurrent.TimeUnit.NANOSECONDS);
        try {
            byte[] buffer = new byte[8_192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() >= MAX_RESPONSE_BYTES) {
                    // Cap, don't fail - the first 2MB of a huge page is still
                    // useful text, and the char clip below caps it far harder.
                    break;
                }
                if (System.nanoTime() - deadlineAt >= 0) {
                    throw new WebPageFetchException("the page took too long to download");
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            if (System.nanoTime() - deadlineAt >= 0) {
                throw new WebPageFetchException("the page took too long to download");
            }
            throw new WebPageFetchException("the page stopped responding mid-download");
        } finally {
            watchdog.cancel(false);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException | RuntimeException ignored) {
            // Closing is the point; a failure here changes nothing.
        }
    }

    /**
     * Crude, dependency-free HTML -&gt; text - same approach (and same justification) as {@code
     * SafePublicUrlFetcher#extractText}: drop script/style/comment bodies, strip tags, decode the
     * common entities, collapse whitespace. Package-private test seam.
     *
     * <p>2026-08-12 DoS fix: the block-dropping step used to be three reluctant-quantifier regexes
     * ({@code (?is)<script.*?</script>} etc.) run over the raw (up-to-2MB, NOT yet char-clipped)
     * body. On a page full of unclosed {@code <script} tokens each match attempt scans to the end
     * of the input before failing, degrading to quadratic time - a measured 80 seconds for just
     * 300KB, i.e. tens of minutes for a full 2MB body - and nothing interrupts it (the 10s HTTP
     * timeouts only bound the network read). The URL is LLM-supplied and any public host can 302
     * into an attacker-controlled body, so that was a remotely triggerable
     * request-thread-exhaustion vector. {@link #stripBlocks} is the strictly-linear single-pass
     * replacement with the same semantics. The generic tag-stripper had the same quadratic worst
     * case for the same reason ({@code <[^>]+>} over a body full of {@code <} with no {@code >}:
     * every attempt scans-then-backtracks to the end of input), so it is replaced by the
     * equally-linear {@link #stripTags} - anything short of making the WHOLE pass linear leaves the
     * hang reachable.
     */
    static String extractText(String html) {
        String withoutBlocks =
                stripBlocks(
                        stripBlocks(
                                stripBlocks(html, "<script", "</script>"), "<style", "</style>"),
                        "<!--",
                        "-->");
        String withoutTags = stripTags(withoutBlocks);
        return withoutTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Linear-time, case-insensitive replacement for the old {@code (?is)<open.*?close}
     * block-dropping regexes (see {@link #extractText}'s DoS note). Each {@code open}..{@code
     * close} span is replaced with a single space; an {@code open} with no later {@code close} is
     * left untouched, exactly like the old reluctant regex's no-match case (the generic
     * tag-stripper then handles whatever remains). Single forward scan - every index strictly
     * advances, so the worst case is O(n × the tiny fixed marker length) regardless of how
     * adversarial the input is. (Deliberately NOT implemented over a {@code toLowerCase} copy of
     * the whole body: full-string lowercasing can change the string's LENGTH for some code points -
     * e.g. U+0130 - which would silently desync the two strings' indices; {@code
     * regionMatches(true, ...)} compares in place.)
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
     * Linear-time replacement for the old {@code (?s)<[^>]+>} tag-stripper (see {@link
     * #extractText}'s DoS note), with the same semantics: a {@code <}..{@code >} span (at least one
     * char between them, so a bare {@code <>} stays, exactly like the {@code +} in the old pattern)
     * is replaced with a single space; a {@code <} with no later {@code >} matches nothing and is
     * left as text.
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

    /**
     * null / non-positive -&gt; {@link #DEFAULT_MAX_CHARS}; otherwise clamped to [{@link
     * #MIN_MAX_CHARS}, {@link #HARD_MAX_CHARS}]. The hard max is a cost ceiling, not a suggestion -
     * the tool result re-enters the prompt on every subsequent turn of the session. Package-private
     * test seam.
     */
    static int clampMaxChars(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_CHARS;
        }
        return Math.max(MIN_MAX_CHARS, Math.min(requested, HARD_MAX_CHARS));
    }

    /**
     * Package-private test seam - clips to {@code maxChars} and appends {@link #TRUNCATION_MARKER}
     * so the model knows the page continues.
     */
    static String clip(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + TRUNCATION_MARKER;
    }
}
