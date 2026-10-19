package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.EdgeAgentPolicyLookup.EdgeAgentPolicyView;
import com.agenticai.core.tenancy.EdgeAgentsConfigController.EdgeConfigResponse;
import com.agenticai.core.tenancy.EdgeAgentsConfigController.EdgeModelEntry;
import com.agenticai.core.tenancy.EdgeAgentsConfigController.EdgeModelManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

/**
 * The versioned on-device model
 * manifest at {@code GET /api/edge/manifest}: seeded in-code default when the
 * {@code edge.model-manifest} platform setting is unset, admin-stored JSON
 * winning when present, and corrupt stored JSON falling back to the default
 * rather than 500ing. Plain unit tests calling the controller method directly,
 * no MockMvc/web layer - the established convention in this module's tests
 * (see {@code TenantSettingsControllerTest}'s header note).
 */
class EdgeAgentsConfigControllerTest {

    private PlatformSettingsService settings;
    private EdgeAgentPolicyLookup policyLookup;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<EdgeAgentPolicyLookup> policyLookupProvider = mock(ObjectProvider.class);
    private EdgeAgentsConfigController controller;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        policyLookup = mock(EdgeAgentPolicyLookup.class);
        when(policyLookupProvider.getIfAvailable()).thenReturn(policyLookup);
        controller = new EdgeAgentsConfigController(settings, new ObjectMapper(), policyLookupProvider);
    }

    @Test
    void manifestDefaultsToSeededConstantWhenSettingUnset() {
        when(settings.getString(EdgeAgentsConfigController.MANIFEST_KEY, null)).thenReturn(null);

        ResponseEntity<EdgeModelManifest> response = controller.getManifest();
        EdgeModelManifest manifest = response.getBody();

        assertNotNull(manifest);
        assertEquals("1", manifest.version()); // version is a STRING on the wire
        // 9 rows through 2026-09-09, plus the four appended 2026-09-17 (three
        // Qwen2.5-Coder sizes promoted from the registry + Qwen3 4B) so the
        // IDE/browser plugins get a code-trained model without an admin click.
        assertEquals(13, manifest.models().size());

        // Tier 0: browser-provided, nothing to download - no url, size 0.
        EdgeModelEntry tier0 = manifest.models().get(0);
        assertEquals("chrome-builtin", tier0.id());
        assertEquals(0, tier0.tier()); // tier is a NUMBER on the wire
        assertNull(tier0.url());
        assertEquals(0L, tier0.sizeBytes());
        assertEquals("llm", tier0.kind());

        // Tier 1 WebLLM trio: ids + sizes verbatim from the installed
        // @mlc-ai/web-llm prebuilt list, WebGPU required, url null (client
        // engine falls back to the MLC default CDN - stated in detailName).
        EdgeModelEntry llama3b = manifest.models().get(1);
        assertEquals("Llama-3.2-3B-Instruct-q4f16_1-MLC", llama3b.id());
        assertEquals(1, llama3b.tier());
        assertEquals(2_263_690_000L, llama3b.sizeBytes());
        assertTrue(llama3b.requiresWebGpu());
        assertNull(llama3b.url());
        assertTrue(llama3b.detailName().contains("MLC"));
        assertEquals("llm", llama3b.kind());

        EdgeModelEntry qwen = manifest.models().get(2);
        assertEquals("Qwen2.5-1.5B-Instruct-q4f16_1-MLC", qwen.id());
        assertEquals(1, qwen.tier());
        assertEquals(1_629_750_000L, qwen.sizeBytes());
        assertTrue(qwen.requiresWebGpu());
        assertNull(qwen.url());

        EdgeModelEntry llama1b = manifest.models().get(3);
        assertEquals("Llama-3.2-1B-Instruct-q4f16_1-MLC", llama1b.id());
        assertEquals(1, llama1b.tier());
        assertEquals(879_040_000L, llama1b.sizeBytes());
        assertTrue(llama1b.requiresWebGpu());
        assertNull(llama1b.url());

        // RIFE + FILM marker entries. RIFE was promoted to a real, verified
        // url 2026-08-07 (see DEFAULT_MANIFEST's javadoc) - Motion+
        // (web/app/lib/edge/video/rife.ts) is downloadable by default now.
        // FILM stays unpublished (null url) until a licensed ONNX export
        // turns up; clients must still handle that null gracefully.
        EdgeModelEntry rife = manifest.models().get(4);
        assertEquals("rife-video-interpolation", rife.id());
        assertEquals(2, rife.tier());
        assertEquals("video-interpolation", rife.kind());
        assertEquals("https://huggingface.co/FuryTMP/RIFE_fp32/resolve/main/RIFE_fp32.onnx", rife.url());
        assertEquals(21_604_631L, rife.sizeBytes());
        assertEquals("6a31074c0f588648982b5e828aee6c27e005015a712a46ea63da48c65fa9a26b", rife.sha256());

        EdgeModelEntry film = manifest.models().get(5);
        assertEquals("film-video-interpolation", film.id());
        assertEquals(2, film.tier());
        assertEquals("video-interpolation", film.kind());
        assertNull(film.url());

        // Appended pair (2026-08-06): real vram_required_MB from the
        // installed @mlc-ai/web-llm package, same convention as the trio.
        EdgeModelEntry phi35 = manifest.models().get(6);
        assertEquals("Phi-3.5-mini-instruct-q4f16_1-MLC", phi35.id());
        assertEquals(1, phi35.tier());
        assertEquals(3_672_070_000L, phi35.sizeBytes());
        assertTrue(phi35.requiresWebGpu());
        assertNull(phi35.url());

        EdgeModelEntry gemma2b = manifest.models().get(7);
        assertEquals("gemma-2-2b-it-q4f16_1-MLC", gemma2b.id());
        assertEquals(1, gemma2b.tier());
        assertEquals(1_895_300_000L, gemma2b.sizeBytes());
        assertTrue(gemma2b.requiresWebGpu());
        assertNull(gemma2b.url());

        // Appended (2026-09): tool-calling-tuned 7B, appended LAST so the
        // index-based assertions above and stored overrides keep matching.
        EdgeModelEntry hermes = manifest.models().get(8);
        assertEquals("Hermes-2-Pro-Mistral-7B-q4f16_1-MLC", hermes.id());
        assertEquals(1, hermes.tier());
        assertEquals(4_033_280_000L, hermes.sizeBytes());
        assertTrue(hermes.requiresWebGpu());
        assertNull(hermes.url());
        assertEquals("llm", hermes.kind());

        // Cache contract: public, 5 minutes.
        String cacheControl = response.getHeaders().getCacheControl();
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("max-age=300"));
        assertTrue(cacheControl.contains("public"));
    }

    @Test
    void defaultManifestSerializesToTheCanonicalWireShape() {
        // Guards the exact JSON the client's ManifestModel type
        // (web/app/lib/edge/capabilities.ts) parses: version as a string,
        // tier as a number, and the capital-GB minDeviceMemoryGB field name.
        com.fasterxml.jackson.databind.JsonNode json =
            new ObjectMapper().valueToTree(EdgeAgentsConfigController.DEFAULT_MANIFEST);

        assertTrue(json.get("version").isTextual());
        assertEquals("1", json.get("version").asText());

        com.fasterxml.jackson.databind.JsonNode llama = json.get("models").get(1);
        assertTrue(llama.get("tier").isInt());
        assertEquals(1, llama.get("tier").asInt());
        assertTrue(llama.has("minDeviceMemoryGB"));
        assertEquals(8.0, llama.get("minDeviceMemoryGB").asDouble());
        assertTrue(llama.get("url").isNull());
        assertTrue(llama.get("sha256").isNull());
        assertTrue(llama.get("requiresWebGpu").asBoolean());
        assertEquals("llm", llama.get("kind").asText());
        // D-9: contextWindowSize is a NUMBER on the wire (client
        // coerceContextWindowSize parses it), null on non-tier-1 rows.
        assertTrue(llama.get("contextWindowSize").isInt());
        assertEquals(4096, llama.get("contextWindowSize").asInt());
        assertTrue(json.get("models").get(0).get("contextWindowSize").isNull());
    }

    @Test
    void adminStoredManifestSettingOverridesDefault() {
        when(settings.getString(EdgeAgentsConfigController.MANIFEST_KEY, null)).thenReturn("""
            {
              "version": "7",
              "models": [
                {
                  "id": "custom-model",
                  "tier": 1,
                  "displayName": "On-device model",
                  "detailName": "Custom 1B (q8), self-hosted",
                  "sizeBytes": 123456789,
                  "url": "https://models.staging.example.com/custom-model/",
                  "sha256": "abc123",
                  "minDeviceMemoryGB": 4,
                  "requiresWebGpu": true,
                  "kind": "llm"
                }
              ]
            }
            """);

        EdgeModelManifest manifest = controller.getManifest().getBody();

        assertNotNull(manifest);
        assertEquals("7", manifest.version());
        assertEquals(1, manifest.models().size());
        EdgeModelEntry entry = manifest.models().get(0);
        assertEquals("custom-model", entry.id());
        assertEquals(1, entry.tier());
        assertEquals("https://models.staging.example.com/custom-model/", entry.url());
        assertEquals("abc123", entry.sha256());
        assertEquals(4.0, entry.minDeviceMemoryGB());
        assertTrue(entry.requiresWebGpu());
    }

    @Test
    void registryCandidatesAreAllTier1AndDisjointFromTheDefaultCatalog() {
        java.util.List<EdgeModelEntry> registry = controller.getRegistry();

        assertTrue(registry.size() > 0);
        java.util.Set<String> defaultIds = new java.util.HashSet<>();
        for (EdgeModelEntry m : EdgeAgentsConfigController.DEFAULT_MANIFEST.models()) {
            defaultIds.add(m.id());
        }
        for (EdgeModelEntry candidate : registry) {
            // rife-fp32-onnx-furytmp (the one tier-2 candidate this catalog
            // ever held) was promoted into DEFAULT_MANIFEST on 2026-08-07,
            // so every remaining registry row is back to tier-1 WebLLM.
            assertEquals(1, candidate.tier(), candidate.id() + " should be tier 1");
            assertTrue(candidate.requiresWebGpu(), candidate.id() + " (tier 1) should require WebGPU");
            assertNull(candidate.url(), candidate.id() + " (tier 1) should resolve by id against the MLC CDN, not a stored url");
            assertTrue(candidate.sizeBytes() > 0, candidate.id() + " should have a real, non-zero size");
            assertTrue(
                !defaultIds.contains(candidate.id()),
                candidate.id() + " is already in DEFAULT_MANIFEST - registry candidates must be new"
            );
        }
    }

    @Test
    void editorResponseIncludesFixedAudioVisionUtilitiesReadOnly() {
        when(settings.getString(EdgeAgentsConfigController.MANIFEST_KEY, null)).thenReturn(null);

        var editor = controller.getManifestForEditor();
        java.util.List<EdgeModelEntry> fixed = editor.fixedUtilities();

        assertEquals(3, fixed.size());
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<String> kinds = new java.util.HashSet<>();
        for (EdgeModelEntry entry : fixed) {
            ids.add(entry.id());
            kinds.add(entry.kind());
            assertEquals(2, entry.tier());
            assertFalse(entry.requiresWebGpu());
            assertNull(entry.url());
            assertTrue(entry.sizeBytes() > 0);
        }
        assertEquals(java.util.Set.of("edge-stt-whisper", "edge-tts-kokoro", "edge-vision-smolvlm"), ids);
        assertEquals(java.util.Set.of("stt", "tts", "vision"), kinds);
        // Always present regardless of whether an admin override exists - these
        // are never part of the toggleable manifest.
        assertFalse(editor.storedOverridePresent());
    }

    @Test
    void corruptStoredManifestFallsBackToDefaultInsteadOfFailing() {
        when(settings.getString(EdgeAgentsConfigController.MANIFEST_KEY, null))
            .thenReturn("{ this is not valid json");

        EdgeModelManifest manifest = controller.getManifest().getBody();

        assertNotNull(manifest);
        assertEquals(EdgeAgentsConfigController.DEFAULT_MANIFEST, manifest);
    }

    // ------------------------------------------------------------------
    // Edge Wave 2 — EDGE-C: speedTier on the manifest wire record
    // ------------------------------------------------------------------

    @Test
    void defaultManifestSpeedTiersMirrorTheClientFallback() {
        // Same values as tier1.ts's BUILTIN_FALLBACK_MANIFEST (1B-2B => fast,
        // 3B+ => capable); non-tier-1-LLM rows stay untagged (null).
        var models = EdgeAgentsConfigController.DEFAULT_MANIFEST.models();
        assertNull(models.get(0).speedTier());                 // chrome-builtin (tier 0)
        assertEquals("capable", models.get(1).speedTier());    // Llama 3B
        assertEquals("fast", models.get(2).speedTier());       // Qwen 1.5B
        assertEquals("fast", models.get(3).speedTier());       // Llama 1B
        assertNull(models.get(4).speedTier());                 // RIFE (video)
        assertNull(models.get(5).speedTier());                 // FILM (video)
        assertEquals("capable", models.get(6).speedTier());    // Phi 3.5 mini (3.8B)
        assertEquals("fast", models.get(7).speedTier());       // Gemma 2 2B

        // Every tier-1 LLM row in DEFAULT_MANIFEST and the registry catalog
        // carries a tag — an untagged tier-1 row would silently re-enable
        // the legacy biggest-fitting-wins selection for the whole manifest.
        for (EdgeModelEntry entry : models) {
            if (entry.tier() == 1) {
                assertNotNull(entry.speedTier(), entry.id() + " (tier 1) must carry a speedTier");
            }
        }
        for (EdgeModelEntry entry : EdgeAgentsConfigController.REGISTRY_CATALOG) {
            assertNotNull(entry.speedTier(), entry.id() + " (registry) must carry a speedTier");
            assertTrue(
                "fast".equals(entry.speedTier()) || "capable".equals(entry.speedTier()),
                entry.id() + " speedTier must be canonical"
            );
        }
    }

    // ------------------------------------------------------------------
    // D-9 — contextWindowSize on the manifest wire record
    // ------------------------------------------------------------------

    @Test
    void contextWindowsComeFromTheInstalledEngineRecordsNeverGuessed() {
        // Values extracted verbatim from @mlc-ai/web-llm@0.2.84's prebuilt
        // model records (overrides.context_window_size, 2026-08-07): every
        // tier-1 id in this file's catalogs is 4096 except TinyLlama v1.0
        // (2048). Non-tier-1 rows (tier 0, video, fixed utilities) stay null
        // — no record exists for them, so no number is published.
        var models = EdgeAgentsConfigController.DEFAULT_MANIFEST.models();
        assertNull(models.get(0).contextWindowSize()); // chrome-builtin (tier 0)
        assertNull(models.get(4).contextWindowSize()); // RIFE (video)
        assertNull(models.get(5).contextWindowSize()); // FILM (video)
        for (EdgeModelEntry entry : models) {
            if (entry.tier() == 1) {
                assertEquals(Integer.valueOf(4096), entry.contextWindowSize(),
                    entry.id() + " (tier 1) must carry the installed record's window");
            }
        }
        for (EdgeModelEntry entry : EdgeAgentsConfigController.REGISTRY_CATALOG) {
            Integer expected = "TinyLlama-1.1B-Chat-v1.0-q4f16_1-MLC".equals(entry.id()) ? 2048 : 4096;
            assertEquals(expected, entry.contextWindowSize(), entry.id());
        }
        for (EdgeModelEntry entry : EdgeAgentsConfigController.FIXED_UTILITIES) {
            assertNull(entry.contextWindowSize(), entry.id() + " (utility) has no chat window");
        }
    }

    @Test
    void storedManifestContextWindowsAreTolerantlyNormalized() {
        // Positive passes through (the admin raise the client honors); 0 /
        // negative / absent (a pre-D-9 stored override) -> null, never
        // guessed — mirrors the client's coerceContextWindowSize.
        when(settings.getString(EdgeAgentsConfigController.MANIFEST_KEY, null)).thenReturn("""
            {
              "version": "10",
              "models": [
                { "id": "m-raised", "tier": 1, "displayName": "A", "sizeBytes": 1,
                  "requiresWebGpu": true, "kind": "llm", "speedTier": "fast", "contextWindowSize": 8192 },
                { "id": "m-zero", "tier": 1, "displayName": "B", "sizeBytes": 2,
                  "requiresWebGpu": true, "kind": "llm", "speedTier": "fast", "contextWindowSize": 0 },
                { "id": "m-negative", "tier": 1, "displayName": "C", "sizeBytes": 3,
                  "requiresWebGpu": true, "kind": "llm", "speedTier": "fast", "contextWindowSize": -1 },
                { "id": "m-pre-d9", "tier": 1, "displayName": "D", "sizeBytes": 4,
                  "requiresWebGpu": true, "kind": "llm", "speedTier": "capable" }
              ]
            }
            """);

        EdgeModelManifest manifest = controller.getManifest().getBody();

        assertNotNull(manifest);
        assertEquals(Integer.valueOf(8192), manifest.models().get(0).contextWindowSize());
        assertNull(manifest.models().get(1).contextWindowSize());
        assertNull(manifest.models().get(2).contextWindowSize());
        assertNull(manifest.models().get(3).contextWindowSize());
        // The speedTier half of the same normalization pass is untouched.
        assertEquals("fast", manifest.models().get(0).speedTier());
        assertEquals("capable", manifest.models().get(3).speedTier());
    }

    @Test
    void storedManifestSpeedTiersAreTolerantlyNormalized() {
        // "FAST " (casing/whitespace drift) -> "fast"; "turbo" (unknown) ->
        // null; absent field (pre-Wave-2 stored override) -> null. Never
        // guessed, never a 500 — mirrors the client's coerceSpeedTier.
        when(settings.getString(EdgeAgentsConfigController.MANIFEST_KEY, null)).thenReturn("""
            {
              "version": "9",
              "models": [
                { "id": "m-drifted-case", "tier": 1, "displayName": "A", "sizeBytes": 1,
                  "requiresWebGpu": true, "kind": "llm", "speedTier": "FAST " },
                { "id": "m-unknown-value", "tier": 1, "displayName": "B", "sizeBytes": 2,
                  "requiresWebGpu": true, "kind": "llm", "speedTier": "turbo" },
                { "id": "m-pre-wave2", "tier": 1, "displayName": "C", "sizeBytes": 3,
                  "requiresWebGpu": true, "kind": "llm" },
                { "id": "m-canonical", "tier": 1, "displayName": "D", "sizeBytes": 4,
                  "requiresWebGpu": true, "kind": "llm", "speedTier": "capable" }
              ]
            }
            """);

        EdgeModelManifest manifest = controller.getManifest().getBody();

        assertNotNull(manifest);
        assertEquals("fast", manifest.models().get(0).speedTier());
        assertNull(manifest.models().get(1).speedTier());
        assertNull(manifest.models().get(2).speedTier());
        assertEquals("capable", manifest.models().get(3).speedTier());
    }

    // ------------------------------------------------------------------
    // Edge Wave 2 — EDGE-B: per-agent policy lookup on GET /config
    // ------------------------------------------------------------------

    private static final Principal PRINCIPAL = () -> "keycloak-user-123";

    @Test
    void configWithoutAgentIdKeepsThePreWave2ShapeAndNeverTouchesTheLookup() {
        when(settings.getBoolean(EdgeAgentsConfigController.ENABLED_KEY, false)).thenReturn(true);

        EdgeConfigResponse response = controller.getConfig(null, null, null);

        assertTrue(response.enabled());
        assertNull(response.agent());
        verify(policyLookup, never()).lookup(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void configWithAgentIdReturnsWhateverTheLookupResolves() {
        UUID agentId = UUID.randomUUID();
        EdgeAgentPolicyView view = new EdgeAgentPolicyView("EDGE_ONLY", true, true);
        when(settings.getBoolean(EdgeAgentsConfigController.ENABLED_KEY, false)).thenReturn(true);
        when(policyLookup.lookup(agentId, PRINCIPAL, "device-token")).thenReturn(view);

        EdgeConfigResponse response = controller.getConfig(agentId, "device-token", PRINCIPAL);

        assertTrue(response.enabled());
        assertEquals(view, response.agent());
    }

    @Test
    void configDegradesToNullAgentWhenNoLookupBeanIsWired() {
        // tenancy-only context (unit tests, partial deployments): the
        // ObjectProvider resolves nothing and /config simply answers the
        // pre-Wave-2 shape rather than failing.
        when(policyLookupProvider.getIfAvailable()).thenReturn(null);
        when(settings.getBoolean(EdgeAgentsConfigController.ENABLED_KEY, false)).thenReturn(false);

        EdgeConfigResponse response = controller.getConfig(UUID.randomUUID(), null, PRINCIPAL);

        assertNull(response.agent());
    }
}
