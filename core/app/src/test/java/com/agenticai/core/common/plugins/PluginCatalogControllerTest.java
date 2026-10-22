package com.agenticai.core.common.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.common.plugins.PluginCatalogController.PluginCatalog;
import com.agenticai.core.common.plugins.PluginCatalogController.PluginEntry;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * IDE-plugin plan B3 - the public plugin catalog at {@code GET /api/plugins/catalog}: seeded
 * in-code default when the {@code plugins.catalog} platform setting is unset, admin-stored JSON
 * winning when present, and corrupt stored JSON falling back to the default rather than 500ing.
 * Plain unit tests calling the controller method directly, no MockMvc/web layer - mirrors {@code
 * EdgeAgentsConfigControllerTest}, the pattern this controller copies.
 */
class PluginCatalogControllerTest {

    private PlatformSettingsService settings;
    private PluginCatalogController controller;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        controller =
                new PluginCatalogController(
                        new PluginCatalogService(
                                settings, com.agenticai.core.json.JsonMappers.plain()));
    }

    @Test
    void catalogDefaultsToSeededConstantWhenSettingUnset() {
        when(settings.getString(PluginCatalogController.CATALOG_KEY, null)).thenReturn(null);

        ResponseEntity<PluginCatalog> response = controller.getCatalog();
        PluginCatalog catalog = response.getBody();

        assertNotNull(catalog);
        assertEquals("1", catalog.version()); // version is a STRING on the wire
        assertEquals(3, catalog.plugins().size());
        assertEquals(
                List.of("vscode", "jetbrains", "chrome"),
                catalog.plugins().stream().map(PluginEntry::id).toList());
        for (PluginEntry entry : catalog.plugins()) {
            // Honest "nothing published yet" state: no artifact, no version, no
            // fabricated store link - clients must render these as coming-soon.
            assertNull(
                    entry.artifact(),
                    entry.id() + " must ship with no artifact until first upload");
            assertNull(entry.latestVersion(), entry.id());
            assertNull(entry.releasedAt(), entry.id());
            assertNull(
                    entry.externalStoreUrl(), entry.id() + " must not fabricate a store listing");
            assertNotNull(entry.displayName(), entry.id());
            assertNotNull(entry.description(), entry.id());
        }
        // The JetBrains description carries the local-inference honesty note (no
        // WebGPU in the in-IDE browser), per the plan's hosted+Ollama-only v1.
        assertTrue(catalog.plugins().get(1).description().contains("WebGPU"));
    }

    @Test
    void catalogResponseCarriesShortPublicCacheHeader() {
        when(settings.getString(PluginCatalogController.CATALOG_KEY, null)).thenReturn(null);

        String cacheControl = controller.getCatalog().getHeaders().getCacheControl();

        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("max-age=300"));
        assertTrue(cacheControl.contains("public"));
    }

    @Test
    void adminStoredCatalogSettingOverridesDefault() {
        when(settings.getString(PluginCatalogController.CATALOG_KEY, null))
                .thenReturn(
                        """
            {
              "version": "4",
              "plugins": [
                {
                  "id": "vscode",
                  "displayName": "VegaDuta for VS Code",
                  "description": "Agents in your editor.",
                  "latestVersion": "1.2.0",
                  "releasedAt": "2026-08-09T00:00:00Z",
                  "releaseNotes": "Local completions.",
                  "artifact": {
                    "fileName": "vegaduta-1.2.0.vsix",
                    "sizeBytes": 123456,
                    "sha256": "abc123"
                  },
                  "externalStoreUrl": "https://marketplace.visualstudio.com/items?itemName=vegaduta.vegaduta"
                }
              ]
            }
            """);

        PluginCatalog catalog = controller.getCatalog().getBody();

        assertNotNull(catalog);
        assertEquals("4", catalog.version());
        assertEquals(1, catalog.plugins().size());
        PluginEntry entry = catalog.plugins().get(0);
        assertEquals("vscode", entry.id());
        assertEquals("1.2.0", entry.latestVersion());
        assertNotNull(entry.artifact());
        assertEquals("vegaduta-1.2.0.vsix", entry.artifact().fileName());
        assertEquals(123456L, entry.artifact().sizeBytes());
        assertEquals("abc123", entry.artifact().sha256());
    }

    @Test
    void corruptStoredCatalogFallsBackToDefaultInsteadOfFailing() {
        when(settings.getString(PluginCatalogController.CATALOG_KEY, null))
                .thenReturn("{ this is not valid json");

        PluginCatalog catalog = controller.getCatalog().getBody();

        assertNotNull(catalog);
        assertEquals(PluginCatalogController.DEFAULT_CATALOG, catalog);
    }
}
