package com.agenticai.core.agents.tools.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Save-time validation of an OpenAPI source's URL/auth settings. IP literals keep every check
 * inside the test JVM - no DNS resolution is needed to exercise the guard path.
 */
class OpenApiSourceSettingsTest {

    @Test
    void suggestBaseUrlPrefersAnAbsoluteServerUrl() {
        assertEquals(
                "https://api.example.com/v1",
                OpenApiSourceSettings.suggestBaseUrl(
                        "https://api.example.com/v1", "https://x.example.com/spec.json"));
        // A relative server URL resolves against the spec URL.
        assertEquals(
                "https://x.example.com/v2",
                OpenApiSourceSettings.suggestBaseUrl("/v2", "https://x.example.com/spec.json"));
        // No server URL: the spec's own origin is the suggestion.
        assertEquals(
                "https://x.example.com",
                OpenApiSourceSettings.suggestBaseUrl(null, "https://x.example.com/docs/spec.json"));
        assertNull(OpenApiSourceSettings.suggestBaseUrl(null, null));
        assertNull(OpenApiSourceSettings.suggestBaseUrl(null, "not a url"));
    }

    @Test
    void requireBaseUrlStripsTrailingSlashesAndRejectsBadUrls() {
        assertEquals(
                "https://93.184.216.34/api",
                OpenApiSourceSettings.requireBaseUrl(" https://93.184.216.34/api// "));

        assertThrows(OpenApiToolException.class, () -> OpenApiSourceSettings.requireBaseUrl(null));
        assertThrows(OpenApiToolException.class, () -> OpenApiSourceSettings.requireBaseUrl(" "));
        OpenApiToolException e =
                assertThrows(
                        OpenApiToolException.class,
                        () -> OpenApiSourceSettings.requireBaseUrl("ftp://93.184.216.34"));
        assertTrue(e.getMessage().startsWith("Base URL rejected:"), e.getMessage());
        assertThrows(
                OpenApiToolException.class,
                () -> OpenApiSourceSettings.requireBaseUrl("http://127.0.0.1"));
    }

    @Test
    void parseAuthTypeNormalisesAndRejectsUnknown() {
        assertEquals(OpenApiAuthType.NONE, OpenApiSourceSettings.parseAuthType(null));
        assertEquals(OpenApiAuthType.NONE, OpenApiSourceSettings.parseAuthType(" "));
        assertEquals(OpenApiAuthType.BEARER, OpenApiSourceSettings.parseAuthType(" bearer "));
        assertEquals(
                OpenApiAuthType.API_KEY_HEADER,
                OpenApiSourceSettings.parseAuthType("api_key_header"));
        assertThrows(OpenApiToolException.class, () -> OpenApiSourceSettings.parseAuthType("kerberos"));
    }

    @Test
    void oauth2TokenUrlIsRequiredMustBePublicAndMustBeHttps() {
        assertNull(OpenApiSourceSettings.resolveOauth2TokenUrl(OpenApiAuthType.NONE, "https://t"));
        assertThrows(
                OpenApiToolException.class,
                () ->
                        OpenApiSourceSettings.resolveOauth2TokenUrl(
                                OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS, null));
        assertEquals(
                "https://93.184.216.34/token",
                OpenApiSourceSettings.resolveOauth2TokenUrl(
                        OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS, " https://93.184.216.34/token "));
        // A public http URL is still refused: the request carries the client secret.
        OpenApiToolException e =
                assertThrows(
                        OpenApiToolException.class,
                        () ->
                                OpenApiSourceSettings.resolveOauth2TokenUrl(
                                        OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS,
                                        "http://93.184.216.34/token"));
        assertTrue(e.getMessage().contains("https"), e.getMessage());
        assertThrows(
                OpenApiToolException.class,
                () ->
                        OpenApiSourceSettings.resolveOauth2TokenUrl(
                                OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS, "https://127.0.0.1/t"));
    }

    @Test
    void oauth2ClientIdAndStyleAreOnlyResolvedForClientCredentials() {
        assertNull(OpenApiSourceSettings.resolveOauth2ClientId(OpenApiAuthType.BEARER, "id"));
        assertThrows(
                OpenApiToolException.class,
                () ->
                        OpenApiSourceSettings.resolveOauth2ClientId(
                                OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS, " "));
        assertEquals(
                "abc",
                OpenApiSourceSettings.resolveOauth2ClientId(
                        OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS, " abc "));

        assertNull(OpenApiSourceSettings.resolveOauth2TokenStyle(OpenApiAuthType.NONE, "JSON"));
        assertEquals(
                "FORM",
                OpenApiSourceSettings.resolveOauth2TokenStyle(
                        OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS, null));
        assertEquals(
                "JSON",
                OpenApiSourceSettings.resolveOauth2TokenStyle(
                        OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS, " json "));
        assertThrows(
                OpenApiToolException.class,
                () ->
                        OpenApiSourceSettings.resolveOauth2TokenStyle(
                                OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS, "xml"));
    }

    @Test
    void apiKeyHeaderNameDefaultsAndIsScreened() {
        assertNull(OpenApiSourceSettings.resolveHeaderName(OpenApiAuthType.NONE, "X-Key"));
        assertEquals(
                "X-API-Key",
                OpenApiSourceSettings.resolveHeaderName(OpenApiAuthType.API_KEY_HEADER, null));
        assertEquals(
                "X-Acme-Key",
                OpenApiSourceSettings.resolveHeaderName(
                        OpenApiAuthType.API_KEY_HEADER, " X-Acme-Key "));
        // Hop-by-hop / forbidden header names cannot carry the credential.
        assertThrows(
                OpenApiToolException.class,
                () -> OpenApiSourceSettings.resolveHeaderName(OpenApiAuthType.API_KEY_HEADER, "Host"));
        assertThrows(
                OpenApiToolException.class,
                () ->
                        OpenApiSourceSettings.resolveHeaderName(
                                OpenApiAuthType.API_KEY_HEADER, "bad header"));
    }

    @Test
    void describeAuthNamesEachScheme() {
        assertEquals("bearer-token auth", OpenApiSourceSettings.describeAuth(OpenApiAuthType.BEARER));
        assertEquals(
                "OAuth2 client-credentials auth",
                OpenApiSourceSettings.describeAuth(OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS));
        assertEquals(
                "API-key-header auth", OpenApiSourceSettings.describeAuth(OpenApiAuthType.NONE));
    }
}
