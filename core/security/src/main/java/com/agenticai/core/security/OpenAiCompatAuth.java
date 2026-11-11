package com.agenticai.core.security;

import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Grant-key and OpenAI-compatible-surface helpers extracted from {@link SecurityConfig} to keep
 * that class under the file-size cap. All methods are static; the {@code openAiCompatEnabled} flag
 * is passed in rather than read from configuration so this stays a pure helper.
 */
final class OpenAiCompatAuth {

    private OpenAiCompatAuth() {}

    /**
     * True when the request carries {@code Authorization: Bearer vmcp_...} (a grant key, not a
     * JWT).
     */
    static boolean hasGrantKeyBearer(jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null
                && header.regionMatches(true, 0, "Bearer vmcp_", 0, "Bearer vmcp_".length());
    }

    /**
     * Default bearer extraction, except that on {@code /v1/**} (OpenAI-compatible surface, when
     * enabled) a {@code vmcp_} grant key is NOT handed to the JWT decoder, which would reject it as
     * "Malformed token" before the key filter ever ran. Every other path and token is unchanged.
     */
    static BearerTokenResolver grantKeyAwareBearerTokenResolver(boolean openAiCompatEnabled) {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> {
            if (openAiCompatEnabled
                    && request.getRequestURI().startsWith("/v1/")
                    && hasGrantKeyBearer(request)) {
                return null;
            }
            return delegate.resolve(request);
        };
    }

    /**
     * Default bearer 401, except that on {@code /v1/**} (flag on) an unauthenticated or
     * invalid-token call gets OpenAI's error envelope, which an OpenAI SDK parses into a typed
     * AuthenticationError. Everything else is the stock {@link
     * BearerTokenAuthenticationEntryPoint}.
     */
    static AuthenticationEntryPoint openAiAwareEntryPoint(boolean openAiCompatEnabled) {
        BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
        return (request, response, ex) -> {
            if (openAiCompatEnabled && request.getRequestURI().startsWith("/v1/")) {
                response.setStatus(401);
                response.setHeader("WWW-Authenticate", "Bearer");
                response.setContentType("application/json");
                response.getWriter()
                        .write(
                                "{\"error\":{\"message\":\"Incorrect API key provided.\","
                                        + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_api_key\"}}");
                return;
            }
            delegate.commence(request, response, ex);
            writeUnauthorizedProblem(request, response);
        };
    }

    /**
     * SE-80: the stock bearer entry point answers 401 with an empty body while every other error
     * carries a problem+json document. Adds one (fixed text: nothing about why the token failed
     * leaks; the {@code WWW-Authenticate} header the delegate set still carries the RFC 6750
     * detail). Skipped when the response is already committed or has a body.
     */
    static void writeUnauthorizedProblem(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        if (response.isCommitted()) {
            return;
        }
        String path =
                request.getRequestURI() == null
                        ? ""
                        : request.getRequestURI().replace("\\", "").replace("\"", "");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                // codeql[java/xss] -- problem+json, not HTML; the reflected path has quotes and
                // backslashes stripped.
                .write(
                        "{\"type\":\"urn:vegaduta:problem:unauthorized\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Authentication"
                                + " is required to access this resource.\",\"instance\":\""
                                + path
                                + "\",\"timestamp\":\""
                                + java.time.Instant.now()
                                + "\",\"error\":\"Authentication is required to access this"
                                + " resource.\"}");
    }
}
