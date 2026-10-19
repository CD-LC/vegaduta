package com.agenticai.core.common.security;

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
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-caller rate limits for the unauthenticated endpoints that had none (security audit 2026-10).
 * Endpoints that already throttle themselves - signup, device claim, demo, share, workflow forms,
 * Studio apps, landing beacon - are deliberately not listed here, so nothing is double-counted.
 *
 * <p>Runs before the body is read and before Spring Security, so a throttled caller costs one Redis
 * round trip. Limits are per minute and configurable; the defaults sit well above legitimate use:
 *
 * <ul>
 *   <li>commerce checkout (creates a payment-provider session each call): 10 per IP
 *   <li>email unsubscribe link: 30 per IP
 *   <li>device gateway (poll every 5 s + event uploads): 300 per device token
 *   <li>SCIM provisioning (IdP sync bursts): 600 per IP
 *   <li>public status / maintenance + announcement poll ({@code GET /api/public/status}, answered
 *       from a 15 s cache): 120 per IP - an office NAT with many open tabs polling every 30-60 s
 *       stays far below it
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class PublicEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "agentic:public-rate:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** One throttled surface: which requests it covers and how the caller is keyed. */
    record Rule(
            String name, String method, Pattern path, long perMinute, boolean keyByDeviceToken) {
        boolean matches(HttpServletRequest request) {
            return matches(request.getMethod(), request.getRequestURI());
        }

        boolean matches(String httpMethod, String uri) {
            return (method == null || method.equalsIgnoreCase(httpMethod))
                    && path.matcher(uri).matches();
        }
    }

    /** The throttled surfaces with placeholder limits: only their method and path are read. */
    private static final List<Rule> ROUTES = rules(0, 0, 0, 0, 0);

    /**
     * True when this filter throttles the route, so it can answer 429 with {@code Retry-After}.
     * Read by the OpenAPI generator; a path template such as {@code /scim/v2/Users/{id}} matches
     * because every variable segment pattern here is {@code [^/]+}.
     */
    public static boolean throttles(String httpMethod, String path) {
        for (Rule rule : ROUTES) {
            if (rule.matches(httpMethod, path)) {
                return true;
            }
        }
        return false;
    }

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final List<Rule> rules;

    public PublicEndpointRateLimitFilter(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${app.rate-limit.public.checkout-per-minute:10}") long checkoutPerMinute,
            @Value("${app.rate-limit.public.unsubscribe-per-minute:30}") long unsubscribePerMinute,
            @Value("${app.rate-limit.public.device-gateway-per-minute:300}") long devicePerMinute,
            @Value("${app.rate-limit.public.scim-per-minute:600}") long scimPerMinute,
            @Value("${app.rate-limit.public.status-per-minute:120}") long statusPerMinute) {
        this.redisProvider = redisProvider;
        this.rules =
                rules(
                        checkoutPerMinute,
                        unsubscribePerMinute,
                        devicePerMinute,
                        scimPerMinute,
                        statusPerMinute);
    }

    private static List<Rule> rules(
            long checkoutPerMinute,
            long unsubscribePerMinute,
            long devicePerMinute,
            long scimPerMinute,
            long statusPerMinute) {
        return List.of(
                new Rule(
                        "checkout",
                        "POST",
                        Pattern.compile("^/api/commerce/feed/[^/]+/checkout/?$"),
                        checkoutPerMinute,
                        false),
                new Rule(
                        "unsubscribe",
                        null,
                        Pattern.compile("^/api/public/email/unsubscribe(/.*)?$"),
                        unsubscribePerMinute,
                        false),
                new Rule(
                        "device",
                        "POST",
                        Pattern.compile(
                                "^/api/devices/(poll|screen-state|notifications|app-usage|sms|commands/[^/]+/result)/?$"),
                        devicePerMinute,
                        true),
                new Rule(
                        "scim",
                        null,
                        Pattern.compile("^/scim/v2(/.*)?$"),
                        scimPerMinute,
                        false),
                new Rule(
                        "status",
                        "GET",
                        Pattern.compile("^/api/public/status/?$"),
                        statusPerMinute,
                        false));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (Rule rule : rules) {
            if (rule.matches(request)) {
                String key = KEY_PREFIX + rule.name() + ":" + callerKey(request, rule);
                if (!RedisFixedWindowCounter.tryAcquire(
                        redisProvider.getIfAvailable(), key, rule.perMinute(), WINDOW)) {
                    response.setStatus(429);
                    response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
                    response.setContentType("application/json");
                    response.getWriter()
                            .write(
                                    "{\"status\":429,\"error\":\"Too Many"
                                            + " Requests\",\"message\":\"Rate limit exceeded\"}");
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Client IP as resolved by Tomcat's RemoteIpValve (same source every other limiter uses), or -
     * for device calls - a hash of the device token, so many devices behind one NAT do not share a
     * bucket and a token never lands in Redis in the clear.
     */
    static String callerKey(HttpServletRequest request, Rule rule) {
        if (rule.keyByDeviceToken()) {
            String token = request.getHeader("X-Device-Token");
            if (token != null && !token.isBlank()) {
                return "t:" + sha256(token.length() > 512 ? token.substring(0, 512) : token);
            }
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
