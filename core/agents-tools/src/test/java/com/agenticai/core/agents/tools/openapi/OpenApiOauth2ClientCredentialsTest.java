package com.agenticai.core.agents.tools.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The OAuth2 client-credentials wire format. The HTTP leg is deliberately not exercised - {@code
 * OpenApiUrlGuard} refuses localhost token endpoints, which is the correct production posture - so
 * what CAN be pinned is the body/content-type the executor sends (FORM for Availity, JSON for
 * Optum) and that an auth-type switch never leaves fields of the old mode behind.
 */
class OpenApiOauth2ClientCredentialsTest {

    private static OpenApiToolSource oauth2Source(String tokenStyle, String scope) {
        return new OpenApiToolSource(
                "Availity",
                "availity",
                null,
                "{}",
                "Availity APIs",
                "1.0.0",
                "https://api.availity.com",
                OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS,
                null,
                "https://api.availity.com/v1/token",
                "client-123",
                scope,
                tokenStyle,
                "ciphertext",
                "...cret",
                "tester");
    }

    @Test
    void formStylePostsUrlencodedClientCredentials() {
        OpenApiToolExecutor.TokenRequestBody body =
                OpenApiToolExecutor.buildTokenRequestBody(
                        oauth2Source("FORM", "scope.one scope.two"), "s3cr&t");
        assertEquals("application/x-www-form-urlencoded", body.contentType());
        assertTrue(body.body().startsWith("grant_type=client_credentials"));
        assertTrue(body.body().contains("client_id=client-123"));
        // Reserved characters must be percent-encoded, not sent raw.
        assertTrue(body.body().contains("client_secret=s3cr%26t"));
        // Space-separated scope list travels as one encoded scope param.
        assertTrue(body.body().contains("scope=scope.one%20scope.two"));
    }

    @Test
    void jsonStylePostsJsonClientCredentials() throws Exception {
        OpenApiToolExecutor.TokenRequestBody body =
                OpenApiToolExecutor.buildTokenRequestBody(oauth2Source("JSON", "sc"), "s3cr&t");
        assertEquals("application/json", body.contentType());
        JsonNode parsed = com.agenticai.core.json.JsonMappers.plain().readTree(body.body());
        assertEquals("client_credentials", parsed.get("grant_type").asString());
        assertEquals("client-123", parsed.get("client_id").asString());
        // JSON transport - the secret is NOT url-encoded.
        assertEquals("s3cr&t", parsed.get("client_secret").asString());
        assertEquals("sc", parsed.get("scope").asString());
    }

    @Test
    void scopeIsOmittedWhenBlankOrAbsent() {
        OpenApiToolExecutor.TokenRequestBody form =
                OpenApiToolExecutor.buildTokenRequestBody(oauth2Source("FORM", "   "), "x");
        assertFalse(form.body().contains("scope="));
        OpenApiToolExecutor.TokenRequestBody json =
                OpenApiToolExecutor.buildTokenRequestBody(oauth2Source("JSON", null), "x");
        assertFalse(json.body().contains("\"scope\""));
    }

    @Test
    void nullStyleDefaultsToForm() {
        OpenApiToolExecutor.TokenRequestBody body =
                OpenApiToolExecutor.buildTokenRequestBody(oauth2Source(null, null), "x");
        assertEquals("application/x-www-form-urlencoded", body.contentType());
    }

    @Test
    void switchingAuthTypeClearsFieldsThatDoNotBelongToIt() {
        OpenApiToolSource source = oauth2Source("FORM", "sc");
        source.updateSettings(
                "Availity",
                "https://api.availity.com",
                OpenApiAuthType.API_KEY_HEADER,
                "X-Api-Key",
                null,
                null,
                null,
                null);
        assertNull(source.getOauth2TokenUrl());
        assertNull(source.getOauth2ClientId());
        assertNull(source.getOauth2Scope());
        assertNull(source.getOauth2TokenStyle());
        assertEquals("X-Api-Key", source.getAuthHeaderName());

        source.updateSettings(
                "Availity",
                "https://api.availity.com",
                OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS,
                null,
                "https://api.availity.com/v1/token",
                "cid",
                "sc",
                "JSON");
        assertNull(source.getAuthHeaderName());
        assertEquals("JSON", source.getOauth2TokenStyle());
        assertEquals("cid", source.getOauth2ClientId());
    }
}
