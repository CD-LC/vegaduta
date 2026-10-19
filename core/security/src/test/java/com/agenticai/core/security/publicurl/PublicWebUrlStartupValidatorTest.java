package com.agenticai.core.security.publicurl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PublicWebUrlStartupValidatorTest {

    @Test
    void blankUnderSecurityProfileFailsStartupNamingTheVariable() {
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> PublicWebUrlStartupValidator.validate(true, ""));
        assertTrue(ex.getMessage().contains("app.web.public-url"), ex.getMessage());
        assertTrue(ex.getMessage().contains("APP_WEB_PUBLIC_URL"), ex.getMessage());
        assertThrows(
                IllegalStateException.class,
                () -> PublicWebUrlStartupValidator.validate(true, null));
    }

    @Test
    void blankWithoutSecurityProfileOnlyWarns() {
        assertDoesNotThrow(() -> PublicWebUrlStartupValidator.validate(false, ""));
        assertDoesNotThrow(() -> PublicWebUrlStartupValidator.validate(false, null));
    }

    @Test
    void configuredValueIsAcceptedUnderEitherProfile() {
        assertDoesNotThrow(
                () -> PublicWebUrlStartupValidator.validate(true, "https://app.example.com"));
        assertDoesNotThrow(
                () -> PublicWebUrlStartupValidator.validate(false, "http://localhost:3000"));
    }

    @Test
    void consoleRedirectPrefersExplicitThenDerivesThenBlank() {
        assertEquals(
                "https://console.example.com/x",
                PublicWebUrl.consoleRedirect(
                        "https://console.example.com/x", "https://app.example.com", "/tools"));
        assertEquals(
                "https://app.example.com/tools",
                PublicWebUrl.consoleRedirect("", "https://app.example.com///", "/tools"));
        assertEquals("", PublicWebUrl.consoleRedirect("", "", "/tools"));
        assertEquals("", PublicWebUrl.consoleRedirect(null, null, "/tools"));
    }

    @Test
    void requireRefusesBlankWithBothPropertyNames() {
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> PublicWebUrl.require("", "app.x.oauth.console-redirect-uri"));
        assertTrue(ex.getMessage().contains("app.web.public-url"), ex.getMessage());
        assertTrue(ex.getMessage().contains("app.x.oauth.console-redirect-uri"), ex.getMessage());
        assertEquals(
                "https://a.example/tools", PublicWebUrl.require("https://a.example/tools", "p"));
    }
}
