package com.agenticai.core.common.apicontract;

import com.agenticai.core.common.ErrorResponseFactory;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.ratelimit.RedisFixedWindowCounter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Opt-in per-caller request budget for the authenticated API ({@code app.rate-limit.api.enabled},
 * off by default), with the IETF {@code RateLimit} / {@code RateLimit-Policy} header fields
 * (draft-ietf-httpapi-ratelimit-headers) on every {@code /api/**} response it counts:
 *
 * <pre>
 * RateLimit-Policy: "api";q=600;w=60
 * RateLimit: "api";r=598;t=41
 * </pre>
 *
 * When the budget is spent it answers 429 Problem Details with {@code Retry-After}. The caller is
 * the tenant plus the authenticated subject (a user, or a tenant API key's subject), so one noisy
 * integration cannot spend a colleague's budget. It runs after Spring Security, inside {@code
 * TenantContextFilter}, and skips unauthenticated requests (the public routes have {@code
 * PublicEndpointRateLimitFilter}). Feature caps (LLM gateway, MCP, OpenAI-compatible surface)
 * keep their own limiters.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 40)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    static final String POLICY = "RateLimit-Policy";
    static final String RATE_LIMIT = "RateLimit";
    private static final String KEY_PREFIX = "agentic:api-rate:";

    private final ObjectProvider<StringRedisTemplate> redis;
    private final ErrorResponseFactory errors;
    private final boolean enabled;
    private final long perWindow;
    private final Duration window;

    public ApiRateLimitFilter(
            ObjectProvider<StringRedisTemplate> redis,
            ErrorResponseFactory errors,
            @Value("${app.rate-limit.api.enabled:false}") boolean enabled,
            @Value("${app.rate-limit.api.requests-per-window:600}") long perWindow,
            @Value("${app.rate-limit.api.window-seconds:60}") long windowSeconds) {
        this.redis = redis;
        this.errors = errors;
        this.enabled = enabled;
        this.perWindow = Math.max(1, perWindow);
        this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<TenantContext> ctx = TenantContext.current();
        if (ctx.isEmpty() || ctx.get().getSubject() == null) {
            chain.doFilter(request, response);
            return;
        }
        String caller = ctx.get().getTenantId() + ":" + ctx.get().getSubject();
        RedisFixedWindowCounter.Usage usage =
                RedisFixedWindowCounter.acquire(
                        redis.getIfAvailable(), KEY_PREFIX + sha256(caller), perWindow, window);
        response.setHeader(POLICY, "\"api\";q=" + perWindow + ";w=" + window.toSeconds());
        response.setHeader(
                RATE_LIMIT, "\"api\";r=" + usage.remaining() + ";t=" + usage.resetSeconds());
        if (!usage.allowed()) {
            response.setHeader("Retry-After", String.valueOf(usage.resetSeconds()));
            ProblemResponses.write(
                    errors,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "API rate limit exceeded; retry after " + usage.resetSeconds() + " seconds",
                    "api-rate-limited",
                    null);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Subjects can be emails; keep them out of Redis keys. */
    private static String sha256(String value) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
