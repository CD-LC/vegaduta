package com.agenticai.core.common.plugins;

import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public catalog behind the first-party IDE/browser plugin marketplace surface
 * ({@code web/app/plugins}, plus in-product discovery from the plugin clients themselves):
 * which plugins exist, what the latest version is, and where to download it. Deliberately
 * a platform-setting-backed catalog with an in-code seeded default - the exact
 * {@code EdgeAgentsConfigController} manifest pattern, NOT the community-template table:
 * these are a handful of first-party binaries curated by the platform operator, not
 * moderated tenant submissions, so a DB migration + lifecycle would be pure overhead.
 * Admin edits go through the existing generic {@code PUT /api/platform/settings/{key}}
 * (key {@link #CATALOG_KEY}); the artifact upload endpoint
 * ({@code PluginArtifactController}) is what turns an entry's {@code artifact} from
 * {@code null} into a real downloadable build.
 *
 * <p>Unauthenticated on purpose (see {@code SecurityConfig}'s permitAll rule for this
 * path, placed with the templates-gallery rule): the marketplace page is public and the
 * plugins themselves need to check for updates before any sign-in happens. Read-only,
 * no tenant data.
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginCatalogController {


    /**
     * Platform-setting key holding the catalog as a JSON document ({@link PluginCatalog}
     * shape). Unset (the normal state until an admin ever overrides it) falls back to
     * {@link #DEFAULT_CATALOG}; a stored value that fails to parse ALSO falls back to
     * the default rather than 500ing - same "callers should never have to handle a
     * missing/corrupt setting as a hard failure" contract as the edge manifest.
     */
    static final String CATALOG_KEY = "plugins.catalog";

    /**
     * A downloadable build of a plugin. {@code fileName}/{@code sizeBytes}/{@code sha256}
     * are computed server-side at upload time ({@code PluginArtifactService}) - never
     * hand-entered - so the sha256 shown on the marketplace page is always the digest of
     * the exact bytes the download endpoint serves. The download URL is derived, not
     * stored: {@code /api/plugins/artifacts/{pluginId}/{latestVersion}/{fileName}}.
     */
    public record PluginArtifact(String fileName, long sizeBytes, String sha256) {
    }

    /**
     * One marketplace entry. {@code artifact == null} is a legitimate, honest state
     * clients MUST handle ("no build published yet" - the default until the first
     * upload), as are null {@code latestVersion}/{@code releasedAt}/{@code releaseNotes}.
     * {@code externalStoreUrl} is the official-store listing (VS Code Marketplace /
     * JetBrains Marketplace / Chrome Web Store) once the maintainer publishes there - also null
     * until that manual step happens; the platform never fabricates one.
     * {@code releasedAt} is an ISO-8601 instant string on the wire.
     */
    public record PluginEntry(
        String id,
        String displayName,
        String description,
        String latestVersion,
        String releasedAt,
        String releaseNotes,
        PluginArtifact artifact,
        String externalStoreUrl
    ) {
    }

    /**
     * Versioned wrapper so clients can cheaply detect "catalog changed since I last
     * looked" - {@code version} is a STRING, same wire convention as the edge manifest.
     */
    public record PluginCatalog(String version, List<PluginEntry> plugins) {
    }

    /**
     * Seeded default: the three first-party plugins, none with a published build yet
     * (every {@code artifact}/{@code latestVersion}/{@code externalStoreUrl} starts
     * null - the marketplace page renders these as "coming soon", never a dead
     * download link). Descriptions state what each plugin actually does in v1,
     * including the JetBrains local-inference limitation, rather than marketing
     * aspiration.
     */
    static final PluginCatalog DEFAULT_CATALOG = new PluginCatalog("1", List.of(
        new PluginEntry(
            "vscode",
            "VegaDuta for VS Code",
            "Chat with your tenant's agents, run workflows, and apply explain/fix/refactor "
                + "actions to selected code without leaving the editor. Inline completions "
                + "run local-first on a free on-device model (WebGPU) when your machine "
                + "supports it, falling back to hosted inference. Sign-in uses the OAuth "
                + "device flow - works over Remote/SSH/WSL.",
            null, null, null, null, null
        ),
        new PluginEntry(
            "jetbrains",
            "VegaDuta for JetBrains IDEs",
            "Agent chat, workflow runs, and explain/refactor selection actions for "
                + "IntelliJ-platform IDEs. Inference is hosted, or local via an "
                + "OpenAI-compatible server you run yourself (Ollama, LM Studio, "
                + "llama.cpp) - the in-IDE browser does not support WebGPU, so the "
                + "zero-install on-device model of the other plugins is not available here. "
                + "Sign-in uses the OAuth device flow.",
            null, null, null, null, null
        ),
        new PluginEntry(
            "chrome",
            "VegaDuta for Chrome",
            "Side-panel agent chat plus right-click actions on any page or selection. "
                + "Private quick actions can run fully on-device on a free WebGPU model - "
                + "nothing leaves your machine - with hosted agents and workflows for "
                + "heavier work.",
            null, null, null, null, null
        )
    ));

    private final PluginCatalogService catalogService;

    public PluginCatalogController(PluginCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Same cache posture as the edge manifest: clients check once per session start,
     * so a short public max-age keeps admin catalog edits (and fresh uploads) taking
     * effect within minutes without any cache-busting machinery.
     */
    @GetMapping("/catalog")
    public ResponseEntity<PluginCatalog> getCatalog() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .body(resolveCatalog());
    }

    /** The effective catalog; see {@link PluginCatalogService#resolveCatalog()}. */
    PluginCatalog resolveCatalog() {
        return catalogService.resolveCatalog();
    }
}
