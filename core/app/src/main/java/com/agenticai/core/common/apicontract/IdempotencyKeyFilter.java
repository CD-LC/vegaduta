package com.agenticai.core.common.apicontract;

import com.agenticai.core.common.ErrorResponseFactory;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code Idempotency-Key} for side-effecting requests (IETF
 * draft-ietf-httpapi-idempotency-key-header).
 *
 * <p>A client that sends {@code Idempotency-Key: <unique value>} on a {@code POST} or {@code PATCH}
 * under {@code /api/**} can retry the same request safely:
 *
 * <ul>
 *   <li>First request: runs normally. Its status, content type and body (any status below 500, body
 *       up to {@value #MAX_STORED_BODY} bytes) are stored for {@code app.api.idempotency.ttl-hours}
 *       (24 by default), keyed by tenant, caller, method, path and key.
 *   <li>Retry with the same body: the stored response is replayed with {@code Idempotent-Replayed:
 *       true}; the handler does not run again.
 *   <li>Retry while the first is still running: 409 {@code idempotency-key-in-use} with {@code
 *       Retry-After}.
 *   <li>Same key, different body: 422 {@code idempotency-key-reused}.
 * </ul>
 *
 * A 5xx or a streamed response (SSE, which Spring marks by calling {@link
 * ShallowEtagHeaderFilter#disableContentCaching}) is not stored, and the key is released so the
 * client can retry. Requests without the header are untouched. JSON and empty bodies only:
 * multipart and form posts pass through. When Redis is unavailable the filter passes the request
 * through and logs once; there is no in-process fallback because a per-replica store would replay
 * on one pod and run twice on another.
 *
 * <p>Two routes keep their own, older semantics and are skipped here: workflow runs (409 on a
 * repeated key, see {@code WorkflowController#run}) and evaluation runs (dataset-scoped key in the
 * database).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 30)
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";
    static final int MAX_STORED_BODY = 256 * 1024;
    static final int MAX_KEY_LENGTH = 255;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyFilter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String KEY_PREFIX = "agentic:idem:";
    private static final String IN_FLIGHT = "in-flight";
    private static final Pattern KEY_SYNTAX = Pattern.compile("^[\\x21-\\x7E]{1,255}$");
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final List<String> OWN_SEMANTICS =
            List.of("/api/workflows/*/run", "/api/agents/*/quality/datasets/*/runs");
    private static final String ATTR_KEY = IdempotencyKeyFilter.class.getName() + ".KEY";
    private static final String ATTR_FINGERPRINT = IdempotencyKeyFilter.class.getName() + ".FP";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ErrorResponseFactory errors;
    private final Duration ttl;
    private final Duration inFlightTtl;
    private volatile boolean warnedNoRedis;

    public IdempotencyKeyFilter(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ErrorResponseFactory errors,
            @Value("${app.api.idempotency.ttl-hours:24}") long ttlHours,
            @Value("${app.api.idempotency.in-flight-seconds:300}") long inFlightSeconds) {
        this.redisProvider = redisProvider;
        this.errors = errors;
        this.ttl = Duration.ofHours(Math.max(1, ttlHours));
        this.inFlightTtl = Duration.ofSeconds(Math.max(5, inFlightSeconds));
    }

    /** Async dispatches are filtered too, so a deferred (non-streamed) result is still stored. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * True when a request with this method and path can use {@code Idempotency-Key} here (JSON or
     * empty bodies, authenticated). Read by the OpenAPI generator; a path template matches because
     * the exclusions are ant patterns with {@code *} segments.
     */
    public static boolean covers(String httpMethod, String path) {
        String method = httpMethod.toUpperCase(java.util.Locale.ROOT);
        if (!"POST".equals(method) && !"PATCH".equals(method) || !path.startsWith("/api/")) {
            return false;
        }
        for (String p : OWN_SEMANTICS) {
            if (MATCHER.match(p, path)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (isAsyncDispatch(request)) {
            return request.getAttribute(ATTR_KEY) == null;
        }
        if (request.getHeader(HEADER) == null
                || !covers(request.getMethod(), request.getRequestURI())) {
            return true;
        }
        String ct = request.getContentType();
        return ct != null && !ct.toLowerCase().contains("json");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAsyncDispatch(request)) {
            chain.doFilter(request, response);
            finish(request, response);
            return;
        }
        String key = request.getHeader(HEADER).strip();
        if (!KEY_SYNTAX.matcher(key).matches()) {
            ProblemResponses.write(
                    errors,
                    response,
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " visible ASCII characters",
                    "idempotency-key-invalid",
                    null);
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        Optional<TenantContext> ctx = TenantContext.current();
        if (redis == null || ctx.isEmpty()) {
            if (redis == null && !warnedNoRedis) {
                warnedNoRedis = true;
                log.warn(
                        "Idempotency-Key received but Redis is not configured; requests run without replay protection");
            }
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        String fingerprint = sha256(body);
        String storeKey =
                KEY_PREFIX
                        + sha256(
                                (ctx.get().getTenantId()
                                                + "\n"
                                                + ctx.get().getSubject()
                                                + "\n"
                                                + request.getMethod()
                                                + "\n"
                                                + request.getRequestURI()
                                                + "\n"
                                                + key)
                                        .getBytes(StandardCharsets.UTF_8));
        String existing;
        boolean claimed;
        try {
            existing = redis.opsForValue().get(storeKey);
            claimed =
                    existing == null
                            && Boolean.TRUE.equals(
                                    redis.opsForValue()
                                            .setIfAbsent(
                                                    storeKey,
                                                    IN_FLIGHT + ":" + fingerprint,
                                                    inFlightTtl));
            if (existing == null && !claimed) {
                existing = redis.opsForValue().get(storeKey);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Idempotency store unavailable; running request without replay protection: {}",
                    e.toString());
            chain.doFilter(new CachedBodyRequest(request, body), response);
            return;
        }
        if (!claimed) {
            rejectOrReplay(existing, fingerprint, response);
            return;
        }
        request.setAttribute(ATTR_KEY, storeKey);
        request.setAttribute(ATTR_FINGERPRINT, fingerprint);
        ContentCachingResponseWrapper wrapped = new StreamingAwareResponse(response, request);
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
        } catch (IOException | ServletException | RuntimeException e) {
            release(storeKey);
            throw e;
        }
        finish(request, wrapped);
    }

    /** Stores (or releases) once the response is complete, i.e. not while async processing runs. */
    private void finish(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (isAsyncStarted(request)) {
            return;
        }
        String storeKey = (String) request.getAttribute(ATTR_KEY);
        ContentCachingResponseWrapper wrapped =
                WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        if (storeKey == null || wrapped == null) {
            return;
        }
        StreamingAwareResponse streaming = wrapped instanceof StreamingAwareResponse s ? s : null;
        int status = wrapped.getStatus();
        byte[] content = wrapped.getContentAsByteArray();
        boolean storable =
                status < 500
                        && content.length <= MAX_STORED_BODY
                        && (streaming == null || !streaming.isStreaming());
        try {
            if (storable) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("fp", request.getAttribute(ATTR_FINGERPRINT));
                record.put("status", status);
                record.put("contentType", wrapped.getContentType());
                record.put("body", Base64.getEncoder().encodeToString(content));
                redisProvider
                        .getObject()
                        .opsForValue()
                        .set(storeKey, MAPPER.writeValueAsString(record), ttl);
            } else {
                release(storeKey);
            }
        } catch (RuntimeException e) {
            log.warn("Could not store idempotent response: {}", e.toString());
        } finally {
            wrapped.copyBodyToResponse();
        }
    }

    private void rejectOrReplay(String existing, String fingerprint, HttpServletResponse response)
            throws IOException {
        if (existing == null || existing.startsWith(IN_FLIGHT + ":")) {
            response.setHeader("Retry-After", "1");
            ProblemResponses.write(
                    errors,
                    response,
                    HttpStatus.CONFLICT,
                    "A request with this Idempotency-Key is still being processed",
                    "idempotency-key-in-use",
                    null);
            return;
        }
        Map<?, ?> record = MAPPER.readValue(existing, Map.class);
        if (!fingerprint.equals(record.get("fp"))) {
            ProblemResponses.write(
                    errors,
                    response,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "This Idempotency-Key was already used with a different request body",
                    "idempotency-key-reused",
                    null);
            return;
        }
        response.setStatus(((Number) record.get("status")).intValue());
        if (record.get("contentType") instanceof String ct) {
            response.setContentType(ct);
        }
        response.setHeader(REPLAYED, "true");
        byte[] content = Base64.getDecoder().decode((String) record.get("body"));
        response.setContentLength(content.length);
        response.getOutputStream().write(content);
    }

    private void release(String storeKey) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.delete(storeKey);
            }
        } catch (RuntimeException e) {
            log.debug("Could not release idempotency key: {}", e.toString());
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Buffers the response for storing, except when Spring MVC streams it: the SSE/emitter return
     * value handlers call {@link ShallowEtagHeaderFilter#disableContentCaching}, after which writes
     * go straight to the client.
     */
    static final class StreamingAwareResponse extends ContentCachingResponseWrapper {
        private final HttpServletRequest request;

        StreamingAwareResponse(HttpServletResponse response, HttpServletRequest request) {
            super(response);
            this.request = request;
        }

        boolean isStreaming() {
            return request.getAttribute(STREAMING_ATTRIBUTE) != null;
        }

        @Override
        public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException {
            return isStreaming() && getContentSize() == 0
                    ? getResponse().getOutputStream()
                    : super.getOutputStream();
        }

        @Override
        public java.io.PrintWriter getWriter() throws IOException {
            return isStreaming() && getContentSize() == 0
                    ? getResponse().getWriter()
                    : super.getWriter();
        }

        @Override
        public void flushBuffer() throws IOException {
            if (isStreaming()) {
                copyBodyToResponse();
                getResponse().flushBuffer();
            } else {
                super.flushBuffer();
            }
        }
    }

    /**
     * The name ShallowEtagHeaderFilter uses for its "streaming, do not cache" request attribute.
     */
    static final String STREAMING_ATTRIBUTE =
            ShallowEtagHeaderFilter.class.getName() + ".STREAMING";

    /** Replays the body this filter already read, so the handler can read it again. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
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

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
