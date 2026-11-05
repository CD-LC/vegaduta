package com.agenticai.core.agents.a2a;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Inbound message-level protection for A2A POSTs (OWASP ASI07): clock-skew window, single-use
 * nonce, hop limit and an optional detached HMAC signature, all carried in OPTIONAL extension
 * headers so third-party A2A peers (which send none of them) are unaffected unless the operator
 * sets {@code app.a2a.require-replay-protection=true}.
 *
 * <ul>
 *   <li>{@code X-A2A-Timestamp} - epoch seconds (ISO-8601 instant also accepted)
 *   <li>{@code X-A2A-Nonce} - 8..128 chars of {@code [A-Za-z0-9._:=+/-]}, single use per tenant
 *   <li>{@code X-A2A-Hop} - non-negative integer; the originator sends 0, each forwarder sends
 *       received+1; refused above {@code app.a2a.max-hops}
 *   <li>{@code X-A2A-Signature} - {@code v1=<base64url HMAC-SHA256>} see {@link A2aMessageSigner}
 * </ul>
 *
 * Runs as a servlet filter AFTER Spring Security (default security chain order is far earlier), so
 * only authenticated callers reach it, {@code TenantContext} is bound (the nonce cache is per
 * tenant) and an anonymous flood cannot fill the cache. Rejections use the dialect of the route: a
 * JSON-RPC envelope (HTTP 200) on {@code /api/a2a} and {@code /api/agents/{id}/a2a}, a {@code
 * google.rpc.Status} body on the {@code /rest} routes. Only POST is checked: GET and the card
 * endpoints are read-only.
 *
 * <p>Check order: required headers, hop, timestamp, signature, THEN the nonce - so a forged or
 * stale request never burns a nonce.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@Component
@ConditionalOnProperty(
        prefix = "app.a2a",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class A2aMessageSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(A2aMessageSecurityFilter.class);

    public static final String TIMESTAMP_HEADER = "X-A2A-Timestamp";
    public static final String NONCE_HEADER = "X-A2A-Nonce";
    public static final String HOP_HEADER = "X-A2A-Hop";
    public static final String SIGNATURE_HEADER = "X-A2A-Signature";

    private static final Pattern A2A_PATH = Pattern.compile("^/api/(a2a|agents/[^/]+/a2a)(/.*)?$");
    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9._:=+/-]{8,128}$");
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int MAX_ID_PROBE = 64 * 1024;

    private final A2aMessageSecurityProperties props;
    private final A2aNonceStore nonces;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public A2aMessageSecurityFilter(
            A2aMessageSecurityProperties props, A2aNonceStore nonces, ObjectMapper mapper) {
        this(props, nonces, mapper, Clock.systemUTC());
    }

    A2aMessageSecurityFilter(
            A2aMessageSecurityProperties props,
            A2aNonceStore nonces,
            ObjectMapper mapper,
            Clock clock) {
        this.props = props;
        this.nonces = nonces;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !A2A_PATH.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest effective = request;
        try {
            String signature = request.getHeader(SIGNATURE_HEADER);
            boolean verifySignature =
                    hasText(props.getSigningSecret())
                            && (signature != null || props.isRequireSignature());
            if (verifySignature) {
                effective = new CachedBodyRequest(request, readBounded(request, MAX_BODY));
            }
            int hop = admit(effective);
            final HttpServletRequest forward = effective;
            A2aHopContext.callWith(
                    hop,
                    () -> {
                        chain.doFilter(forward, response);
                        return null;
                    });
        } catch (A2aError e) {
            log.warn(
                    "A2A request refused ({}): {} {}",
                    e.getCode(),
                    request.getMethod(),
                    request.getRequestURI());
            reject(request, response, e);
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    /** Validates the headers; returns the hop to bind for this request. Throws {@link A2aError}. */
    int admit(HttpServletRequest request) throws IOException {
        String ts = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String hopHeader = request.getHeader(HOP_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        boolean secretSet = hasText(props.getSigningSecret());

        if (secretSet && props.isRequireSignature() && signature == null) {
            throw signatureInvalid("a signature is required");
        }
        boolean none = ts == null && nonce == null && hopHeader == null && signature == null;
        if (none) {
            if (props.isRequireReplayProtection()) {
                throw headersRequired("missing X-A2A-Timestamp, X-A2A-Nonce and X-A2A-Hop");
            }
            return 0;
        }
        if (props.isRequireReplayProtection()
                && (ts == null || nonce == null || hopHeader == null)) {
            throw headersRequired("X-A2A-Timestamp, X-A2A-Nonce and X-A2A-Hop are all required");
        }
        if ((ts == null) != (nonce == null) || (signature != null && ts == null)) {
            throw headersRequired("X-A2A-Timestamp and X-A2A-Nonce must be sent together");
        }

        int hop = 0;
        if (hopHeader != null) {
            hop = parseHop(hopHeader);
            if (hop > props.getMaxHops()) {
                throw new A2aError(
                        A2aError.HOP_LIMIT_EXCEEDED,
                        "A2A hop limit exceeded: possible forwarding loop",
                        Map.of("hop", hop, "maxHops", props.getMaxHops()));
            }
        }
        if (ts == null) {
            return hop; // hop-only request: loop protection without replay protection
        }

        Duration window = props.getReplayWindow();
        Instant sent = parseTimestamp(ts);
        long skew = Math.abs(Duration.between(sent, clock.instant()).toSeconds());
        if (skew > window.toSeconds()) {
            throw new A2aError(
                    A2aError.TIMESTAMP_OUT_OF_WINDOW,
                    "X-A2A-Timestamp is outside the accepted window",
                    Map.of("windowSeconds", window.toSeconds()));
        }
        if (!NONCE.matcher(nonce).matches()) {
            throw new A2aError(
                    A2aError.REPLAY_HEADERS_REQUIRED,
                    "X-A2A-Nonce must be 8-128 characters of [A-Za-z0-9._:=+/-]");
        }
        if (secretSet && signature != null) {
            byte[] body = request instanceof CachedBodyRequest c ? c.body : new byte[0];
            if (!A2aMessageSigner.verify(
                    props.getSigningSecret(),
                    signature,
                    request.getMethod(),
                    request.getRequestURI(),
                    ts,
                    nonce,
                    hop,
                    body)) {
                throw signatureInvalid("signature does not match the request");
            }
        }
        switch (nonces.claim(nonce, window)) {
            case REPLAY ->
                    throw new A2aError(
                            A2aError.REPLAY_DETECTED,
                            "X-A2A-Nonce was already used: replayed request refused");
            case FULL ->
                    throw new A2aError(
                            A2aError.REPLAY_CAPACITY,
                            "Too many distinct nonces in the window for this tenant; retry later");
            case ACCEPTED -> {}
        }
        return hop;
    }

    private static A2aError headersRequired(String detail) {
        return new A2aError(
                A2aError.REPLAY_HEADERS_REQUIRED, "Replay protection headers: " + detail);
    }

    private static A2aError signatureInvalid(String detail) {
        return new A2aError(A2aError.SIGNATURE_INVALID, "X-A2A-Signature: " + detail);
    }

    private static int parseHop(String raw) {
        String v = raw.strip();
        if (v.isEmpty() || v.length() > 6 || !v.chars().allMatch(Character::isDigit)) {
            throw headersRequired("X-A2A-Hop must be a non-negative integer");
        }
        return Integer.parseInt(v);
    }

    private static Instant parseTimestamp(String raw) {
        String v = raw.strip();
        try {
            if (v.matches("\\d{1,12}")) {
                return Instant.ofEpochSecond(Long.parseLong(v));
            }
            return Instant.parse(v);
        } catch (RuntimeException e) {
            throw new A2aError(
                    A2aError.TIMESTAMP_OUT_OF_WINDOW,
                    "X-A2A-Timestamp must be epoch seconds or an ISO-8601 instant");
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static byte[] readBounded(HttpServletRequest request, int max) throws IOException {
        byte[] all = request.getInputStream().readNBytes(max + 1);
        if (all.length > max) {
            throw signatureInvalid("body too large to verify");
        }
        return all;
    }

    // ------------------------------------------------------------ rejection

    private void reject(HttpServletRequest request, HttpServletResponse response, A2aError e)
            throws IOException {
        if (request.getRequestURI().contains("/a2a/rest")) {
            A2aRestController.Mapping m = A2aRestController.mapping(e.getCode());
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("@type", "type.googleapis.com/google.rpc.ErrorInfo");
            info.put("reason", m.reason());
            info.put("domain", A2aRestController.ERROR_DOMAIN);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", m.http().value());
            error.put("status", m.status());
            error.put("message", e.getMessage());
            error.put("details", List.of(info));
            response.setStatus(m.http().value());
            response.setContentType(A2aRestController.MEDIA_TYPE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader(A2aVersion.HEADER, A2aVersion.V1_0.wire());
            response.getWriter().write(mapper.writeValueAsString(Map.of("error", error)));
            response.getWriter().flush();
            return;
        }
        A2aVersion version;
        try {
            version = A2aVersion.fromHeader(request.getHeader(A2aVersion.HEADER));
        } catch (A2aError ignored) {
            version = A2aVersion.V0_3;
        }
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", e.getCode());
        err.put("message", e.getMessage());
        if (e.getData() != null) {
            err.put("data", e.getData());
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", requestId(request));
        envelope.put("error", err);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader(A2aVersion.HEADER, version.wire());
        response.getWriter().write(mapper.writeValueAsString(envelope));
        response.getWriter().flush();
    }

    /** Best effort: the JSON-RPC id of a request we are refusing; null id when unreadable. */
    private JsonNode requestId(HttpServletRequest request) {
        try {
            byte[] body =
                    request instanceof CachedBodyRequest c
                            ? c.body
                            : request.getInputStream().readNBytes(MAX_ID_PROBE);
            JsonNode node = mapper.readTree(body);
            if (node != null && node.isObject() && node.has("id")) {
                return node.get("id");
            }
        } catch (Exception ignored) {
            // unreadable body: the spec's null id
        }
        return NullNode.getInstance();
    }

    /** Replays an already-consumed body so the controller still reads it. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String enc = getCharacterEncoding();
            java.nio.charset.Charset cs =
                    enc == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(enc);
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }
}
