package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.oauth.OAuthProviderRegistry.Provider;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OAuthProviderRegistryTest {

    private static final List<String> EXPECTED_IDS = List.of(
        "google-sheets", "google-drive", "google-gmail", "google-calendar", "github", "slack", "notion",
        "linear", "hubspot", "microsoft-graph", "asana", "zoom");

    @Test
    void shipsTheTwelveRequestedProvidersInDisplayOrder() {
        OAuthProviderRegistry registry = OAuthProviderTestSupport.registry(Map.of());
        assertEquals(EXPECTED_IDS, registry.all().stream().map(Provider::id).toList());
    }

    @Test
    void everythingIsNotConfiguredByDefaultBecauseTheFeatureIsOff() {
        OAuthProviderRegistry registry = OAuthProviderTestSupport.registry(Map.of());
        assertFalse(registry.featureEnabled());
        for (Provider p : registry.all()) {
            assertFalse(p.configured(), p.id());
            assertEquals("feature_off", p.notConfiguredReason(), p.id());
        }
    }

    @Test
    void featureOffWinsEvenWhenCredentialsAreSet() {
        Map<String, String> props = OAuthProviderTestSupport.props("github");
        props.put("app.mcp.oauth.providers-enabled", "false");
        Provider github = OAuthProviderTestSupport.registry(props).find("github").orElseThrow();
        assertFalse(github.configured());
        assertEquals("feature_off", github.notConfiguredReason());
    }

    @Test
    void aProviderWithBlankClientIdOrSecretIsReportedNotConfigured() {
        Map<String, String> props = OAuthProviderTestSupport.props("github", "slack");
        props.put("app.mcp.oauth.providers.slack.client-secret", "   ");
        OAuthProviderRegistry registry = OAuthProviderTestSupport.registry(props);

        assertTrue(registry.find("github").orElseThrow().configured());
        Provider slack = registry.find("slack").orElseThrow();
        assertFalse(slack.configured());
        assertEquals("no_client", slack.notConfiguredReason());
        assertEquals("no_client", registry.find("notion").orElseThrow().notConfiguredReason());
    }

    @Test
    void anExplicitlyDisabledProviderIsNotConfiguredEvenWithCredentials() {
        Map<String, String> props = OAuthProviderTestSupport.props("github");
        props.put("app.mcp.oauth.providers.github.enabled", "false");
        Provider github = OAuthProviderTestSupport.registry(props).find("github").orElseThrow();
        assertFalse(github.configured());
        assertEquals("disabled", github.notConfiguredReason());
    }

    @Test
    void aMissingOrInsecureCallbackBaseMakesEveryProviderUnusable() {
        Map<String, String> none = OAuthProviderTestSupport.props("github");
        none.remove("app.mcp.oauth.provider-callback-base-url");
        Provider noBase = OAuthProviderTestSupport.registry(none).find("github").orElseThrow();
        assertEquals("no_callback_base", noBase.notConfiguredReason());
        assertNull(noBase.redirectUri());

        Map<String, String> http = OAuthProviderTestSupport.props("github");
        http.put("app.mcp.oauth.provider-callback-base-url", "http://api.example.com");
        assertEquals("no_callback_base",
            OAuthProviderTestSupport.registry(http).find("github").orElseThrow().notConfiguredReason());

        Map<String, String> loopback = OAuthProviderTestSupport.props("github");
        loopback.put("app.mcp.oauth.provider-callback-base-url", "http://localhost:8080");
        assertTrue(OAuthProviderTestSupport.registry(loopback).find("github").orElseThrow().configured(),
            "plain http is allowed for a loopback developer machine");
    }

    @Test
    void aBlankOrLoopbackConsoleRedirectOnARealDeploymentMakesProvidersUnusable() {
        Map<String, String> blank = OAuthProviderTestSupport.props("github");
        blank.remove("app.mcp.oauth.provider-console-redirect-uri");
        assertEquals("no_console_redirect",
            OAuthProviderTestSupport.registry(blank).find("github").orElseThrow().notConfiguredReason());

        Map<String, String> loopback = OAuthProviderTestSupport.props("github");
        loopback.put("app.mcp.oauth.provider-console-redirect-uri", "http://localhost:3000/tools");
        assertEquals("no_console_redirect",
            OAuthProviderTestSupport.registry(loopback).find("github").orElseThrow().notConfiguredReason(),
            "a public callback with a localhost landing page would send every user to localhost");

        Map<String, String> junk = OAuthProviderTestSupport.props("github");
        junk.put("app.mcp.oauth.provider-console-redirect-uri", "not a url");
        assertEquals("no_console_redirect",
            OAuthProviderTestSupport.registry(junk).find("github").orElseThrow().notConfiguredReason());
    }

    @Test
    void aLoopbackConsoleRedirectIsFineOnADeveloperMachineWhoseCallbackIsLoopbackToo() {
        Map<String, String> dev = OAuthProviderTestSupport.props("github");
        dev.put("app.mcp.oauth.provider-callback-base-url", "http://localhost:8080");
        dev.put("app.mcp.oauth.provider-console-redirect-uri", "http://localhost:3000/tools");
        assertTrue(OAuthProviderTestSupport.registry(dev).find("github").orElseThrow().configured());
    }

    @Test
    void defaultScopesAreReadOnly() {
        OAuthProviderRegistry registry = OAuthProviderTestSupport.registry(Map.of());
        for (Provider p : registry.all()) {
            for (String scope : p.scopes()) {
                assertFalse(scope.contains("send") || scope.contains("write") || scope.equals("repo")
                    || scope.equals("calendar.events") || scope.endsWith("/spreadsheets") || scope.endsWith("/drive.file"),
                    p.id() + " default scope is broader than read-only: " + scope);
            }
        }
    }

    @Test
    void theRedirectUriIsTheBaseWithoutTrailingSlashPlusAFixedPerProviderPath() {
        Map<String, String> props = OAuthProviderTestSupport.props("github");
        props.put("app.mcp.oauth.provider-callback-base-url", "https://api.example.com//");
        assertEquals("https://api.example.com/oauth/mcp/github/callback",
            OAuthProviderTestSupport.registry(props).find("github").orElseThrow().redirectUri());
    }

    @Test
    void googleIsFourSeparateScopeSetsOnOneEndpointFamily() {
        OAuthProviderRegistry registry = OAuthProviderTestSupport.registry(Map.of());
        Set<List<String>> scopeSets = new HashSet<>();
        for (String id : List.of("google-sheets", "google-drive", "google-gmail", "google-calendar")) {
            Provider p = registry.find(id).orElseThrow();
            assertEquals("google", p.spec().family());
            assertEquals("https://accounts.google.com/o/oauth2/v2/auth", p.spec().authorizeUrl());
            assertEquals("https://oauth2.googleapis.com/token", p.spec().tokenUrl());
            assertEquals("offline", p.spec().extraAuthParams().get("access_type"));
            assertEquals("consent", p.spec().extraAuthParams().get("prompt"));
            scopeSets.add(p.scopes());
        }
        assertEquals(4, scopeSets.size(), "each Google product has its own scope set");
        assertTrue(registry.find("google-sheets").orElseThrow().scopes().contains("https://www.googleapis.com/auth/spreadsheets.readonly"));
        assertTrue(registry.find("google-calendar").orElseThrow().scopes().contains("https://www.googleapis.com/auth/calendar.readonly"));
    }

    @Test
    void scopeOverrideAcceptsCommasAndWhitespaceAndFallsBackWhenBlank() {
        Map<String, String> props = OAuthProviderTestSupport.props("slack", "linear");
        props.put("app.mcp.oauth.providers.slack.scopes", "chat:write, users:read  channels:read,chat:write");
        props.put("app.mcp.oauth.providers.linear.scopes", "  ");
        OAuthProviderRegistry registry = OAuthProviderTestSupport.registry(props);

        assertEquals(List.of("chat:write", "users:read", "channels:read"), registry.find("slack").orElseThrow().scopes());
        assertEquals(List.of("read"), registry.find("linear").orElseThrow().scopes());
    }

    @Test
    void mcpUrlComesFromTheBuiltInDefaultUnlessOverriddenAndOnlyThatHostMayReceiveTheToken() {
        Map<String, String> props = OAuthProviderTestSupport.props("linear", "notion");
        props.put("app.mcp.oauth.providers.notion.mcp-url", "https://mcp.notion.example/mcp");
        OAuthProviderRegistry registry = OAuthProviderTestSupport.registry(props);

        Provider linear = registry.find("linear").orElseThrow();
        assertEquals("https://mcp.linear.app/mcp", linear.mcpUrl());
        assertEquals("linear-oauth", linear.mcpServerName());
        assertTrue(linear.allowsMcpServerUrl("https://mcp.linear.app/mcp"));
        assertTrue(linear.allowsMcpServerUrl("https://MCP.LINEAR.APP/other/path"));
        assertFalse(linear.allowsMcpServerUrl("https://evil.example.com/mcp"));
        assertFalse(linear.allowsMcpServerUrl("http://mcp.linear.app/mcp"), "https only");
        assertFalse(linear.allowsMcpServerUrl("https://mcp.linear.app.evil.com/mcp"));
        assertFalse(linear.allowsMcpServerUrl(null));
        assertFalse(linear.allowsMcpServerUrl("not a url"));

        Provider notion = registry.find("notion").orElseThrow();
        assertTrue(notion.hasMcpServer());
        assertTrue(notion.allowsMcpServerUrl("https://mcp.notion.example/mcp"));

        Provider zoom = registry.find("zoom").orElseThrow();
        assertFalse(zoom.hasMcpServer());
        assertFalse(zoom.allowsMcpServerUrl("https://zoom.us/mcp"), "a provider without an MCP server never releases its token");
    }

    @Test
    void microsoftEndpointsCarryTheConfigurableEntraTenant() {
        Map<String, String> props = OAuthProviderTestSupport.props("microsoft-graph");
        Provider common = OAuthProviderTestSupport.registry(props).find("microsoft-graph").orElseThrow();
        assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/authorize", common.resolve(common.spec().authorizeUrl()));

        props.put("app.mcp.oauth.providers.microsoft-graph.tenant", "contoso.onmicrosoft.com");
        Provider scoped = OAuthProviderTestSupport.registry(props).find("microsoft-graph").orElseThrow();
        assertEquals("https://login.microsoftonline.com/contoso.onmicrosoft.com/oauth2/v2.0/token", scoped.resolve(scoped.spec().tokenUrl()));
    }

    @Test
    void everyProviderIdFitsTheRegistrationMarkerColumnAndEveryEndpointIsHttps() {
        for (Provider p : OAuthProviderTestSupport.registry(Map.of()).all()) {
            assertTrue(OAuthProviderService.markerFor(p.id()).length() <= 32, p.id());
            assertTrue(p.spec().authorizeUrl().startsWith("https://"), p.id());
            assertTrue(p.spec().tokenUrl().startsWith("https://"), p.id());
            if (p.spec().revokeUrl() != null) {
                assertTrue(p.spec().revokeUrl().startsWith("https://"), p.id());
            }
            assertNotEquals("", p.spec().name());
        }
    }

    @Test
    void findIsCaseInsensitiveAndEmptyForUnknownOrNull() {
        OAuthProviderRegistry registry = OAuthProviderTestSupport.registry(Map.of());
        assertTrue(registry.find("GitHub").isPresent());
        assertTrue(registry.find("nope").isEmpty());
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void readsItsKeysFromASpringEnvironmentWithRelaxedHyphenatedIds() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.mcp.oauth.providers-enabled", "true")
            .withProperty("app.mcp.oauth.provider-callback-base-url", "https://api.example.com")
            .withProperty("app.mcp.oauth.provider-console-redirect-uri", "https://app.example.com/tools")
            .withProperty("app.mcp.oauth.providers.google-sheets.client-id", "gid")
            .withProperty("app.mcp.oauth.providers.google-sheets.client-secret", "gsecret");
        OAuthProviderRegistry registry = new OAuthProviderRegistry(env);
        assertTrue(registry.find("google-sheets").orElseThrow().configured());
        assertFalse(registry.find("google-drive").orElseThrow().configured());
    }
}
