package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.oauth.OAuthProviderRegistry;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * A catalog entry backed by a one-click OAuth provider must only be offered while that provider can actually
 * connect - never a Connect button that cannot work.
 */
class McpRegistryControllerCatalogFilterTest {

    private final McpCatalogService catalogService = new McpCatalogService();

    @SuppressWarnings("unchecked")
    private McpRegistryController controller(OAuthProviderRegistry registry) {
        McpRegistryController c = new McpRegistryController(
            mock(McpRegistryService.class), catalogService, mock(OfficialMcpRegistryClient.class),
            mock(ObjectProvider.class), new McpConnectDispatcher(new SyncTaskExecutor()));
        if (registry != null) {
            c.setOAuthProviderRegistry(registry);
        }
        return c;
    }

    private static OAuthProviderRegistry registry(boolean featureOn, String... configured) {
        Map<String, String> p = new HashMap<>();
        p.put("app.mcp.oauth.providers-enabled", Boolean.toString(featureOn));
        p.put("app.mcp.oauth.provider-callback-base-url", "https://api.example.com");
        p.put("app.mcp.oauth.provider-console-redirect-uri", "https://app.example.com/tools");
        for (String id : configured) {
            p.put("app.mcp.oauth.providers." + id + ".client-id", "cid");
            p.put("app.mcp.oauth.providers." + id + ".client-secret", "secret");
        }
        return new OAuthProviderRegistry(p::get);
    }

    private static List<String> keys(List<McpCatalogEntry> entries) {
        return entries.stream().map(McpCatalogEntry::key).toList();
    }

    @Test
    void providerBackedEntriesAreHiddenWhileTheFeatureIsOffOrTheProviderUnconfigured() {
        List<String> off = keys(controller(registry(false, "github", "linear")).catalog());
        assertFalse(off.contains("github-oauth"));
        assertFalse(off.contains("linear-oauth"));

        List<String> partial = keys(controller(registry(true, "github")).catalog());
        assertTrue(partial.contains("github-oauth"));
        assertFalse(partial.contains("linear-oauth"));
    }

    @Test
    void everyOtherEntryIsAlwaysListed() {
        List<String> all = keys(catalogService.list());
        List<String> shown = keys(controller(registry(false)).catalog());

        for (String key : all) {
            if (!key.endsWith("-oauth")) {
                assertTrue(shown.contains(key), key);
            }
        }
        assertTrue(shown.contains("atlassian"), "Atlassian's own OAuth entry is not provider-registry backed");
    }

    @Test
    void withoutAProviderRegistryTheCatalogIsUnfiltered() {
        assertEquals(keys(catalogService.list()), keys(controller(null).catalog()));
    }

    @Test
    void providerBackedCatalogEntriesAreWellFormed() {
        OAuthProviderRegistry registry = registry(true);
        for (McpCatalogEntry e : catalogService.list()) {
            if (e.oauthProviderId() == null) {
                continue;
            }
            var provider = registry.find(e.oauthProviderId()).orElseThrow();
            assertTrue(e.requiresOAuth(), e.key());
            assertFalse(e.ready(), e.key());
            assertTrue(provider.hasMcpServer(), e.key() + " must have an MCP server to register");
            assertEquals(provider.mcpServerName(), e.name(), "the catalog name is the name the linker registers under");
            assertEquals(provider.mcpUrl(), e.dynamicUrl());
            assertTrue(e.requiredEnvVars().isEmpty());
        }
    }

    @Test
    void theLegacyCatalogConstructorsDefaultToNoProvider() {
        assertTrue(catalogService.list().stream().anyMatch(e -> e.oauthProviderId() == null));
        assertNull(catalogService.find("atlassian").orElseThrow().oauthProviderId());
    }

    @Test
    void aProviderBackedNameIsACatalogEntryOnlyWhileItsProviderIsConfigured() {
        // Feature off / no registry: "github-oauth" is an ordinary custom name, so it cannot unlock the
        // FREE-plan "catalog connectors only" gate for an arbitrary URL.
        assertFalse(catalogService.isCatalogEntry("github-oauth"));
        assertTrue(catalogService.find("linear-oauth").isEmpty());

        catalogService.setOAuthProviderRegistry(registry(false, "github"));
        assertFalse(catalogService.isCatalogEntry("github-oauth"));

        catalogService.setOAuthProviderRegistry(registry(true, "github"));
        assertTrue(catalogService.isCatalogEntry("GitHub-OAuth"));
        assertFalse(catalogService.isCatalogEntry("linear-oauth"), "linear itself is not configured");
        assertTrue(catalogService.isCatalogEntry("atlassian"), "non-provider entries are unaffected");
    }
}
