package com.agenticai.core.agents.tools.openapi;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Save-time validation and normalisation of an {@link OpenApiToolSource}'s settings (base URL,
 * auth type, OAuth2 token URL / client id / style, API-key header name) for {@link
 * OpenApiToolSourceService}. Pure and static; split out of the service only to keep each file
 * reviewable.
 */
final class OpenApiSourceSettings {

    private static final Pattern HEADER_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$");
    private static final Set<String> FORBIDDEN_AUTH_HEADERS =
            Set.of("host", "content-length", "content-type", "connection", "transfer-encoding");

    /**
     * servers[0].url, made absolute against the spec URL when relative; null when neither helps
     * (the console then asks).
     */
    static String suggestBaseUrl(String serverUrl, String specUrl) {
        if (StringUtils.hasText(serverUrl)) {
            String s = serverUrl.strip();
            if (s.startsWith("http://") || s.startsWith("https://")) {
                return s;
            }
            if (specUrl != null) {
                try {
                    return URI.create(specUrl).resolve(s).toString();
                } catch (IllegalArgumentException ignored) {
                    // Fall through to the spec-origin fallback below.
                }
            }
        }
        if (specUrl != null) {
            try {
                URI u = URI.create(specUrl);
                if (u.getScheme() != null && u.getHost() != null) {
                    return u.getScheme() + "://" + u.getRawAuthority();
                }
            } catch (IllegalArgumentException ignored) {
                // No suggestion.
            }
        }
        return null;
    }

    static String requireBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new OpenApiToolException(
                    "Base URL is required - the document does not declare an absolute server URL.");
        }
        String stripped = baseUrl.strip();
        try {
            OpenApiUrlGuard.validatePublicHttpUri(stripped);
        } catch (OpenApiToolException e) {
            throw new OpenApiToolException("Base URL rejected: " + e.getMessage() + ".");
        }
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    static OpenApiAuthType parseAuthType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return OpenApiAuthType.NONE;
        }
        try {
            return OpenApiAuthType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OpenApiToolException(
                    "Unknown auth type \""
                            + raw
                            + "\" - use NONE, API_KEY_HEADER, BEARER or OAUTH2_CLIENT_CREDENTIALS.");
        }
    }

    /**
     * The token endpoint is validated at SAVE time with the same public-URL rule the executor
     * applies at call time (and the executor re-validates on every call after DNS resolution - the
     * save-time check only buys a friendlier rejection). HTTPS is additionally required here even
     * though {@link OpenApiUrlGuard} permits http: the request carries the client secret, and a
     * plaintext-http token call would hand it to the wire.
     */
    static String resolveOauth2TokenUrl(OpenApiAuthType authType, String tokenUrl) {
        if (authType != OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS) {
            return null;
        }
        if (!StringUtils.hasText(tokenUrl)) {
            throw new OpenApiToolException(
                    "A token URL is required for OAuth2 client-credentials auth "
                            + "(e.g. https://api.availity.com/v1/token or https://sandbox-apigw.optum.com/apip/auth/v2/token).");
        }
        String stripped = tokenUrl.strip();
        try {
            OpenApiUrlGuard.validatePublicHttpUri(stripped);
        } catch (OpenApiToolException e) {
            throw new OpenApiToolException("Token URL rejected: " + e.getMessage() + ".");
        }
        if (!stripped.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new OpenApiToolException(
                    "Token URL must be https - the request carries the client secret.");
        }
        return stripped;
    }

    static String resolveOauth2ClientId(OpenApiAuthType authType, String clientId) {
        if (authType != OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS) {
            return null;
        }
        if (!StringUtils.hasText(clientId)) {
            throw new OpenApiToolException(
                    "A client id is required for OAuth2 client-credentials auth.");
        }
        return clientId.strip();
    }

    /** FORM (urlencoded body - Availity) or JSON (JSON body - Optum); default FORM. */
    static String resolveOauth2TokenStyle(OpenApiAuthType authType, String tokenStyle) {
        if (authType != OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS) {
            return null;
        }
        if (!StringUtils.hasText(tokenStyle)) {
            return "FORM";
        }
        String normalized = tokenStyle.strip().toUpperCase(Locale.ROOT);
        if (!normalized.equals("FORM") && !normalized.equals("JSON")) {
            throw new OpenApiToolException("Token request style must be FORM or JSON.");
        }
        return normalized;
    }

    static String resolveHeaderName(OpenApiAuthType authType, String authHeaderName) {
        if (authType != OpenApiAuthType.API_KEY_HEADER) {
            return null;
        }
        String header = StringUtils.hasText(authHeaderName) ? authHeaderName.strip() : "X-API-Key";
        if (!HEADER_NAME_PATTERN.matcher(header).matches()
                || FORBIDDEN_AUTH_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
            throw new OpenApiToolException(
                    "\"" + header + "\" is not a usable API-key header name.");
        }
        return header;
    }

    static String describeAuth(OpenApiAuthType authType) {
        if (authType == OpenApiAuthType.BEARER) {
            return "bearer-token auth";
        }
        if (authType == OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS) {
            return "OAuth2 client-credentials auth";
        }
        return "API-key-header auth";
    }

    private OpenApiSourceSettings() {}
}
