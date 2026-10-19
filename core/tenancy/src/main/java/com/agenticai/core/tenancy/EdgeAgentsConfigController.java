package com.agenticai.core.tenancy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime server-side gate for Edge Agents, companion to the build-time {@code
 * NEXT_PUBLIC_EDGE_AGENTS} bake. The client bake decides whether the
 * edge UI ships in a given build at all; this endpoint is the admin-tunable kill switch on top of
 * that - flipping {@code platform.edge-agents.enabled} to {@code false} via the existing generic
 * {@code PUT /api/platform/settings/{key}} turns the surface off instantly, tenant-wide, with no
 * rebuild/redeploy, exactly the "admin-tunable, no hardcoded consumer limits" rule.
 *
 * <p>Unauthenticated on purpose (see {@code SecurityConfig}'s permitAll rule for this path) -
 * the anonymous public {@code /try} surface needs to read this before any sign-in happens, same
 * reasoning as {@code /api/demo/**}. Defaults to {@code false} (Wave 1: default off) so a missing
 * setting row never silently turns the feature on.
 *
 * <p><b>Model manifest:</b> also serves the versioned on-device model manifest at {@code GET
 * /api/edge/manifest} - the client download manager's single source of truth for which models
 * exist, how big they are, what a device needs to run them, and (eventually) where to fetch the
 * weights from. Admin-tunable via the {@code edge.model-manifest} platform setting (a JSON
 * document, editable through the same generic settings endpoint) with a seeded in-code default
 * when unset - same "admin-tunable, never hardcoded-only" posture as the enabled flag above.
 */
@RestController
@RequestMapping("/api/edge")
public class EdgeAgentsConfigController {

    private static final Logger log = LoggerFactory.getLogger(EdgeAgentsConfigController.class);

    static final String ENABLED_KEY = "platform.edge-agents.enabled";

    /**
     * Platform-setting key holding the manifest as a JSON document ({@link EdgeModelManifest}
     * shape). Unset (the normal state until an admin ever overrides it) falls back to {@link
     * #DEFAULT_MANIFEST}; a stored value that fails to parse ALSO falls back to the default
     * rather than 500ing - same "callers should never have to handle a missing/corrupt setting
     * as a hard failure" contract {@code PlatformSettingsService}'s own getters follow.
     */
    static final String MANIFEST_KEY = "edge.model-manifest";

    /**
     * One entry per acquirable on-device asset, matching the client's {@code ManifestModel}
     * type in {@code web/app/lib/edge/capabilities.ts} field-for-field (the canonical wire
     * contract): {@code tier} is a NUMBER (0 = browser built-in, 1 = WebLLM/WebGPU,
     * 2 = WASM/other), {@code kind} is {@code "llm"} or {@code "video-interpolation"}, and
     * raw model identity (Gemini Nano / Llama / Qwen / RIFE) lives ONLY in {@code detailName}
     * - {@code displayName} stays brand-first. {@code url == null} is a legitimate, permanent
     * state clients MUST handle, with two distinct meanings kept honest in {@code detailName}:
     * for the WebLLM tier-1 models it means "no VegaDuta-hosted weights yet - the client engine
     * fetches from the MLC/WebLLM default CDN by model id" (the plan's Phase-1 hosting
     * decision); for the FILM entry it means "weights not published anywhere yet."
     * {@code sha256}/{@code minDeviceMemoryGB} are nullable for the same reason (unknown /
     * not applicable); {@code sizeBytes} is 0 when there is nothing to download (tier 0) or
     * nothing published yet (FILM).
     *
     * <p>{@code speedTier} (EDGE-C, Wave 2 - closes the "server manifest record has no
     * speedTier field" half of finding E-5): {@code "fast"} for the small/quick tier-1 LLMs
     * (~1B-2B) picked for ordinary conversational turns, {@code "capable"} for the bigger ones
     * (3B+) picked when a turn looks complex - mirroring the exact values the client's
     * {@code BUILTIN_FALLBACK_MANIFEST} in {@code web/app/lib/edge/tier1.ts} already carries,
     * so API-served and fallback manifests select identically. Null (and tolerated as absent on
     * the wire) for everything that isn't a tier-1 LLM row - tier 0 has nothing to select
     * between and the video/audio/vision utilities aren't chat models. A stored admin override
     * that predates this field simply deserializes to null, which the client treats as
     * untagged-legacy (biggest-fitting-wins) - see {@code selectBestModel}'s degradation rule.
     *
     * <p>{@code contextWindowSize} (D-9, {@code docs/rewriting-story/04-design/
     * EDGE-CONTEXT-BUDGET-DESIGN.md}): the model's context window in tokens, read verbatim
     * from the installed {@code @mlc-ai/web-llm@0.2.84} package's prebuilt model records
     * ({@code overrides.context_window_size}, extracted 2026-08-07 - every tier-1 id in this
     * file is 4096 except TinyLlama v1.0 at 2048; same never-guess rule as
     * {@code vram_required_MB}). Null for everything that isn't a tier-1 WebLLM LLM row
     * (tier 0's Chrome built-in has its own opaque limit; the video/audio/vision utilities
     * aren't chat models). Admin-overridable via the stored manifest - the client passes a
     * raised value into the WebLLM engine load config and budgets its prompts against it;
     * stored drift is normalized by {@link #normalizeStoredEntries} (non-positive/absent
     * -&gt; null, never guessed).
     */
    public record EdgeModelEntry(
        String id,
        int tier,
        String displayName,
        String detailName,
        long sizeBytes,
        String url,
        String sha256,
        Double minDeviceMemoryGB,
        boolean requiresWebGpu,
        String kind,
        String speedTier,
        Integer contextWindowSize
    ) {
    }

    /**
     * Versioned wrapper so clients can cheaply detect "manifest changed since I last looked".
     * {@code version} is a STRING per the wire contract (client {@code ModelManifest.version}).
     */
    public record EdgeModelManifest(String version, List<EdgeModelEntry> models) {
    }

    /** Seeded default manifest - see {@link EdgeModelCatalog#DEFAULT_MANIFEST}. */
    static final EdgeModelManifest DEFAULT_MANIFEST = EdgeModelCatalog.DEFAULT_MANIFEST;

    /** Admin registry candidates - see {@link EdgeModelCatalog#REGISTRY_CATALOG}. */
    static final List<EdgeModelEntry> REGISTRY_CATALOG = EdgeModelCatalog.REGISTRY_CATALOG;

    /** Read-only audio/vision utilities - see {@link EdgeModelCatalog#FIXED_UTILITIES}. */
    static final List<EdgeModelEntry> FIXED_UTILITIES = EdgeModelCatalog.FIXED_UTILITIES;

    private final PlatformSettingsService platformSettingsService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<EdgeAgentPolicyLookup> agentPolicyLookup;

    public EdgeAgentsConfigController(
        PlatformSettingsService platformSettingsService,
        ObjectMapper objectMapper,
        ObjectProvider<EdgeAgentPolicyLookup> agentPolicyLookup
    ) {
        this.platformSettingsService = platformSettingsService;
        this.objectMapper = objectMapper;
        this.agentPolicyLookup = agentPolicyLookup;
    }

    /**
     * {@code agent} (Edge Wave 2, EDGE-A/EDGE-B) is null in every case that predates this
     * field - no {@code agentId} asked for, no credential presented, agent not resolvable for
     * this caller, or no {@link EdgeAgentPolicyLookup} bean wired (tenancy-only context) - so
     * every pre-existing consumer that only reads {@code enabled} keeps parsing byte-identical
     * responses.
     */
    public record EdgeConfigResponse(boolean enabled, EdgeAgentPolicyLookup.EdgeAgentPolicyView agent) {
    }

    /**
     * Wave 2 (EDGE-B): with {@code ?agentId=...}, ALSO answers that agent's routing policy and
     * guardrail gating for an authenticated caller. The path stays permitAll at the
     * filter-chain layer (the anonymous {@code /try} surface still needs {@code enabled}
     * pre-sign-in), so per-agent data is gated HERE, mirroring the safe subset of {@code
     * EdgeToolExecutionController}'s own credential handling (Keycloak JWT or
     * {@code X-Device-Token}) via {@link EdgeAgentPolicyLookup} - an unauthenticated or
     * unauthorized probe gets exactly the same response shape as "no agentId asked for"
     * (anti-enumeration: null, never an error status).
     */
    @GetMapping("/config")
    public EdgeConfigResponse getConfig(
        @RequestParam(value = "agentId", required = false) UUID agentId,
        @RequestHeader(value = "X-Device-Token", required = false) String deviceToken,
        Principal principal
    ) {
        boolean enabled = platformSettingsService.getBoolean(ENABLED_KEY, false);
        EdgeAgentPolicyLookup.EdgeAgentPolicyView agent = null;
        if (agentId != null) {
            EdgeAgentPolicyLookup lookup = agentPolicyLookup.getIfAvailable();
            if (lookup != null) {
                agent = lookup.lookup(agentId, principal, deviceToken);
            }
        }
        return new EdgeConfigResponse(enabled, agent);
    }

    /**
     * The F-1 model manifest. Unauthenticated like {@code /config} (static, tenant-free
     * metadata the pre-sign-in {@code /try} surface also needs) and cacheable for 5 minutes -
     * the client does one cheap check per session start, never polls, so a short public
     * max-age keeps admin manifest edits taking effect within minutes without any
     * cache-busting machinery.
     */
    @GetMapping("/manifest")
    public ResponseEntity<EdgeModelManifest> getManifest() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .body(resolveManifest());
    }

    /**
     * Everything the web-admin "Edge AI models" editor needs in one call: the
     * {@code effective} manifest (what clients currently get), the in-code
     * {@code defaults} (the full candidate catalog, so a model an admin removed
     * from the stored manifest can still be listed and re-enabled), and whether
     * a stored override exists at all. Unlike {@code /manifest}, this ALSO
     * reveals admin-disabled candidate models, and it is consumed only by the
     * platform-admin console - so, unlike {@code /config}/{@code /manifest}
     * above, {@code SecurityConfig} gates this one specific path to
     * {@code ROLE_platform-admin} rather than leaving it on the controller's
     * otherwise-permitAll surface. The WRITE side stays where it always was:
     * authenticated {@code PUT /api/platform/settings/edge.model-manifest}.
     */
    public record EdgeManifestEditorResponse(
        EdgeModelManifest effective,
        EdgeModelManifest defaults,
        boolean storedOverridePresent,
        /** Read-only - see {@link #FIXED_UTILITIES}'s javadoc. Never affected
         * by {@code storedOverridePresent}; there is nothing to override. */
        List<EdgeModelEntry> fixedUtilities
    ) {
    }

    @GetMapping("/manifest/full")
    public EdgeManifestEditorResponse getManifestForEditor() {
        String stored = platformSettingsService.getString(MANIFEST_KEY, null);
        return new EdgeManifestEditorResponse(
            resolveManifest(),
            DEFAULT_MANIFEST,
            stored != null && !stored.isBlank(),
            FIXED_UTILITIES
        );
    }

    /**
     * The "browse official registry" candidate list for the web-admin editor -
     * see {@link #REGISTRY_CATALOG}'s javadoc for what's in it and why.
     * Admin-only for the same reason {@code /manifest/full} is: it names
     * models nobody has opted to serve yet, so it stays off the permitAll
     * surface {@code /manifest} sits on (see {@code SecurityConfig}).
     */
    @GetMapping("/manifest/registry")
    public List<EdgeModelEntry> getRegistry() {
        return REGISTRY_CATALOG;
    }

    private EdgeModelManifest resolveManifest() {
        String stored = platformSettingsService.getString(MANIFEST_KEY, null);
        if (stored == null || stored.isBlank()) {
            return DEFAULT_MANIFEST;
        }
        try {
            return normalizeStoredEntries(objectMapper.readValue(stored, EdgeModelManifest.class));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            log.warn("Platform setting '{}' holds unparseable manifest JSON - serving the built-in default. {}",
                MANIFEST_KEY, e.getMessage());
            return DEFAULT_MANIFEST;
        }
    }

    /**
     * Tolerant normalization of the drift-prone fields on a stored admin override, one pass,
     * one copy at most per entry:
     *
     * <p>{@code speedTier} (EDGE-C): the two canonical values pass through (case/whitespace-
     * insensitively, re-canonicalized to lowercase); anything else - a typo, a future value
     * this server doesn't know, the field simply absent on a pre-Wave-2 override - becomes
     * null rather than reaching clients as a value their selector would silently ignore
     * anyway. Mirrors the client's own {@code coerceSpeedTier} in
     * {@code web/app/lib/edge/capabilities.ts} ("never guessed"), so a drifted stored
     * document degrades identically on both ends.
     *
     * <p>{@code contextWindowSize} (D-9): a context window must be a positive token count;
     * zero, negative, or absent (a pre-D-9 stored override) becomes null - the client then
     * falls back to the installed engine record, never a guessed number. Mirrors the
     * client's {@code coerceContextWindowSize}.
     *
     * <p>Entries already canonical (the overwhelmingly common case) are returned as-is with
     * no copying.
     */
    private static EdgeModelManifest normalizeStoredEntries(EdgeModelManifest manifest) {
        if (manifest == null || manifest.models() == null) {
            return manifest;
        }
        boolean dirty = false;
        List<EdgeModelEntry> normalized = new java.util.ArrayList<>(manifest.models().size());
        for (EdgeModelEntry entry : manifest.models()) {
            String canonicalTier = canonicalSpeedTier(entry == null ? null : entry.speedTier());
            Integer canonicalWindow = canonicalContextWindow(entry == null ? null : entry.contextWindowSize());
            if (entry != null
                && (!java.util.Objects.equals(canonicalTier, entry.speedTier())
                    || !java.util.Objects.equals(canonicalWindow, entry.contextWindowSize()))) {
                dirty = true;
                normalized.add(new EdgeModelEntry(
                    entry.id(), entry.tier(), entry.displayName(), entry.detailName(),
                    entry.sizeBytes(), entry.url(), entry.sha256(), entry.minDeviceMemoryGB(),
                    entry.requiresWebGpu(), entry.kind(), canonicalTier, canonicalWindow
                ));
            } else {
                normalized.add(entry);
            }
        }
        return dirty ? new EdgeModelManifest(manifest.version(), normalized) : manifest;
    }

    private static String canonicalSpeedTier(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return ("fast".equals(v) || "capable".equals(v)) ? v : null;
    }

    /** Positive values pass through; zero/negative (and null) -> null, never guessed. */
    private static Integer canonicalContextWindow(Integer raw) {
        return raw != null && raw > 0 ? raw : null;
    }
}
