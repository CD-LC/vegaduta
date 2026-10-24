package com.agenticai.core.common;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the web console (Next.js dev server talking to this API on :8080 - a different origin,
 * so without this every fetch() call from the browser is silently blocked by the browser itself
 * before a response is even read, regardless of whether the request would otherwise succeed).
 *
 * <p>Accepts a comma-separated list of allowed origins, not just one - CORS matches the browser's
 * exact Origin header (scheme + host + port), so "http://localhost:3000" does NOT match
 * "http://192.168.x.x:3000" even though they're the same running dev server. Defaults cover the two
 * most common local-dev addresses (localhost and 127.0.0.1) for BOTH Next.js apps this backend
 * serves: the tenant console (web/, port 3000) and the standalone platform-admin console
 * (web-admin/, port 3001 - see that app's package.json "dev"/"start" scripts and its own README for
 * why it's a separate app). Add a LAN IP or a deployed origin via WEB_ALLOWED_ORIGINS for
 * phone/other-device testing or production without touching code.
 *
 * <p>exposedHeaders("X-Session-Id") matters specifically for chat: by default, browsers only let JS
 * read a small allowlist of "simple" response headers via fetch's Headers API - custom headers like
 * X-Session-Id are invisible to res.headers.get(...) unless explicitly exposed here, even once CORS
 * otherwise allows the request through. Content-Disposition is exposed for the same reason - the
 * workflow Output(file) download endpoint (#152) sets it so api.js's downloadWorkflowRunOutput can
 * read the real filename back out of the response. The API-convention headers (paging cursor,
 * ETag, rate limit, deprecation, idempotent replay; see docs/api/README.md) are exposed so browser
 * clients of the public API can read them too.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public WebConfig(
            @Value(
                            "${app.web.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:3001,http://127.0.0.1:3001}")
                    String allowedOrigins) {
        this.allowedOrigins =
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(
                        "X-Session-Id",
                        "X-Turn-Id",
                        "X-Journal-Turn-Id",
                        "Content-Disposition",
                        "X-Total-Count",
                        "X-Has-More",
                        "X-Next-Cursor",
                        "X-Request-Id",
                        "ETag",
                        "Retry-After",
                        "RateLimit",
                        "RateLimit-Policy",
                        "Deprecation",
                        "Sunset",
                        "Link",
                        "Idempotent-Replayed")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
