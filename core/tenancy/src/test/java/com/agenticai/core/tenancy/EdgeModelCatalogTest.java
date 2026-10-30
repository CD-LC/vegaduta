package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.tenancy.EdgeAgentsConfigController.EdgeModelEntry;
import com.agenticai.core.tenancy.EdgeAgentsConfigController.EdgeModelManifest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link EdgeModelCatalog} is pure in-code data, so the useful assertions are the invariants its
 * two lists' javadocs promise: the manifest is versioned, ids are unique, every tier-1 LLM row
 * carries the fields the client selector needs, and the admin-facing {@code REGISTRY_CATALOG} stays
 * disjoint from the default manifest (a registry row duplicating a default would let an admin "add"
 * a model that is already served).
 */
class EdgeModelCatalogTest {

    @Test
    void defaultManifestIsVersionedAndNonEmpty() {
        EdgeModelManifest manifest = EdgeModelCatalog.DEFAULT_MANIFEST;

        assertEquals("1", manifest.version());
        assertFalse(manifest.models().isEmpty());
    }

    @Test
    void defaultManifestStartsWithTheFreeBrowserBuiltinTier() {
        EdgeModelEntry first = EdgeModelCatalog.DEFAULT_MANIFEST.models().get(0);

        assertEquals("chrome-builtin", first.id());
        assertEquals(0, first.tier());
        assertEquals("llm", first.kind());
    }

    @Test
    void everyEntryHasAUniqueNonBlankIdDisplayNameAndKind() {
        Set<String> ids = new HashSet<>();
        for (EdgeModelEntry entry : EdgeModelCatalog.DEFAULT_MANIFEST.models()) {
            assertNotNull(entry.id());
            assertFalse(entry.id().isBlank());
            assertTrue(ids.add(entry.id()), "duplicate model id: " + entry.id());
            assertNotNull(entry.displayName());
            assertFalse(entry.displayName().isBlank());
            assertNotNull(entry.kind());
            assertFalse(entry.kind().isBlank());
        }
    }

    @Test
    void everyTierOneLlmRowCarriesASizeMemoryRequirementAndContextWindow() {
        for (EdgeModelEntry entry : EdgeModelCatalog.DEFAULT_MANIFEST.models()) {
            if (entry.tier() == 1 && "llm".equals(entry.kind())) {
                assertTrue(entry.sizeBytes() > 0, entry.id());
                assertNotNull(entry.minDeviceMemoryGB(), entry.id());
                assertNotNull(entry.contextWindowSize(), entry.id());
                assertNotNull(entry.speedTier(), entry.id());
                assertTrue(entry.requiresWebGpu(), entry.id());
            }
        }
    }

    @Test
    void theRifeVideoEngineIsDownloadableByDefault() {
        EdgeModelEntry rife =
                EdgeModelCatalog.DEFAULT_MANIFEST.models().stream()
                        .filter(e -> e.id().equals("rife-video-interpolation"))
                        .findFirst()
                        .orElseThrow();

        assertEquals("video-interpolation", rife.kind());
        assertNotNull(rife.url());
        assertTrue(rife.url().startsWith("https://"));
        assertNotNull(rife.sha256());
        assertTrue(rife.sizeBytes() > 0);
    }

    @Test
    void registryCatalogIsNonEmptyAndDisjointFromTheDefaultManifest() {
        assertFalse(EdgeModelCatalog.REGISTRY_CATALOG.isEmpty());

        Set<String> defaultIds = new HashSet<>();
        for (EdgeModelEntry entry : EdgeModelCatalog.DEFAULT_MANIFEST.models()) {
            defaultIds.add(entry.id());
        }
        Set<String> registryIds = new HashSet<>();
        for (EdgeModelEntry entry : EdgeModelCatalog.REGISTRY_CATALOG) {
            assertFalse(
                    defaultIds.contains(entry.id()),
                    "registry row duplicates a default: " + entry.id());
            assertTrue(registryIds.add(entry.id()), "duplicate registry id: " + entry.id());
            // Every registry candidate is a tier-1 WebLLM chat model - the
            // same shape the default manifest's own tier-1 rows carry.
            assertEquals(1, entry.tier());
            assertEquals("llm", entry.kind());
            assertTrue(entry.sizeBytes() > 0, entry.id());
            assertNotNull(entry.minDeviceMemoryGB(), entry.id());
        }
    }

    @Test
    void fixedUtilitiesCoverTheThreeNonManifestModels() {
        assertEquals(3, EdgeModelCatalog.FIXED_UTILITIES.size());

        Set<String> kinds = new HashSet<>();
        for (EdgeModelEntry entry : EdgeModelCatalog.FIXED_UTILITIES) {
            kinds.add(entry.kind());
            assertEquals(2, entry.tier());
            assertFalse(entry.requiresWebGpu(), entry.id());
            assertTrue(entry.sizeBytes() > 0, entry.id());
        }
        assertEquals(Set.of("stt", "tts", "vision"), kinds);
    }
}
