package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.storage.FileStorageException;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.dto.TenantSettingsResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * White-label logo UPLOAD, for tenants whose plan carries the {@code
 * white_label} entitlement. Before this existed, {@code brand_logo_url}
 * (V162) could only be set by PUT-ing an absolute {@code https} URL to
 * {@code /api/tenant/settings} - i.e. the tenant had to already be hosting
 * their logo somewhere public, which for most customers means they cannot
 * use the feature at all. This endpoint takes the image itself, stores it in
 * object storage the way {@code AccountController} stores avatars, and
 * writes the resulting public URL into that same column - so every existing
 * reader (the agent/workflow share pages, the embeddable widget, {@code
 * TenantLimits#brandLogoUrl}) keeps working unchanged, with no new field and
 * no migration.
 *
 * <p><b>Why there is no class-level {@code @RequestMapping}.</b> The two
 * halves of this feature must live under two DIFFERENT path prefixes, and
 * that split is a security boundary, not a naming preference:
 * <ul>
 *   <li>{@code POST/DELETE /api/tenant/branding/logo} sit under {@code
 *       /api/tenant/**}, which {@code SecurityConfig} pins to {@code
 *       ROLE_tenant-admin} - the same posture as {@code PUT
 *       /api/tenant/settings}, whose branding fields these write.</li>
 *   <li>{@code GET /api/share/branding/{tenantId}/logo.{ext}} must be
 *       {@code permitAll}: it is rendered as an {@code <img src>} on the
 *       PUBLIC, unauthenticated share pages, which carry no token to send.
 *       It sits under {@code /api/share/**} alongside the agent and workflow
 *       share endpoints that have the same anonymous-viewer posture.</li>
 * </ul>
 * Keeping both in one class is deliberate: the object-key convention and the
 * extension to content-type mapping have to stay in lockstep between the
 * write and the read, and splitting them across two files is how that pair
 * silently drifts.
 *
 * <p><b>Why the tenant id is in the public URL.</b> The read needs to know
 * whose logo to serve, and a stored opaque handle would mean a new column
 * plus a migration for no security gain: a tenant id is not a capability
 * here. The GET below grants nothing beyond the logo bytes, is
 * entitlement-gated on every request, and refuses to serve anything the
 * tenant has not deliberately published as their public brand mark.
 */
@RestController
@Profile("security")
public class TenantBrandingLogoController {

    /**
     * Generous for a brand mark - the share-page header renders it at {@code
     * h-7} (28px tall) and the widget at {@code h-5}. Same role as {@code
     * AccountController#MAX_AVATAR_BYTES}: not a UX limit, just a guard
     * against something wildly oversized being posted straight at the API.
     * Comfortably under the 30MB Spring multipart ceiling configured in
     * {@code application-knowledge.yml} (a profile every deployment runs), so
     * an over-cap upload fails here with a readable message rather than as a
     * raw multipart rejection the caller cannot interpret.
     */
    private static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;

    /**
     * Object-storage namespace. {@code FileStorageService} prefixes every new
     * key with {@code tenant/<tenantId>/} on its own (see {@code
     * S3FileStorageService#tenantScopedKey}), so the tenant id deliberately
     * does NOT appear here - putting it in twice would produce {@code
     * tenant/acme/tenant-logos/acme...} and, worse, make the key depend on
     * the caller's own idea of its id rather than the context the storage
     * layer enforces.
     */
    private static final String KEY_PREFIX = "tenant-logos/";

    /**
     * The stored object's basename. Fixed per tenant (only the extension
     * varies), so re-uploading overwrites rather than orphaning - same
     * reasoning as {@code AccountController}'s fixed avatar key.
     */
    private static final String KEY_BASENAME = "brand-logo";

    /**
     * Raster formats only, keyed by the extension that goes in both the
     * object key and the public URL.
     *
     * <p><b>SVG is excluded on purpose.</b> An SVG is a script-bearing
     * document, not an inert image: served as {@code image/svg+xml} from our
     * own API origin and opened directly, it executes in that origin. Since
     * the GET below is {@code permitAll} and the file is attacker-supplied (a
     * tenant admin is not the platform), accepting SVG here would be stored
     * XSS against the API origin. {@code nosniff} and a CSP would both help,
     * but the format simply is not needed for a logo.
     */
    private static final Map<String, MediaType> ALLOWED_FORMATS = Map.of(
        "png", MediaType.IMAGE_PNG,
        "jpg", MediaType.IMAGE_JPEG,
        "webp", MediaType.parseMediaType("image/webp")
    );

    /** Content types accepted on upload, mapped to the extension they are stored under. */
    private static final Map<String, String> CONTENT_TYPE_TO_EXTENSION = Map.of(
        "image/png", "png",
        "image/jpeg", "jpg",
        "image/jpg", "jpg",
        "image/webp", "webp"
    );

    /**
     * Public path of the GET below, with {@code %s} for the tenant id and the
     * extension. Single source of truth for the URL this controller both
     * mints and serves.
     */
    private static final String PUBLIC_PATH_TEMPLATE = "/api/share/branding/%s/logo.%s";

    private final TenantRepository tenantRepository;
    private final TenantLimits tenantLimits;
    private final FileStorageService fileStorageService;
    private final FreeModeService freeModeService;
    private final String configuredPublicBaseUrl;

    public TenantBrandingLogoController(
        TenantRepository tenantRepository,
        TenantLimits tenantLimits,
        FileStorageService fileStorageService,
        FreeModeService freeModeService,
        // Same override-or-derive shape as app.a2a.public-base-url: normally
        // empty, because server.forward-headers-strategy=native already makes
        // the request's own origin the public one behind Caddy/Container
        // Apps. Set it for any deployment where that is not true.
        @Value("${app.branding.public-base-url:}") String configuredPublicBaseUrl
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantLimits = tenantLimits;
        this.fileStorageService = fileStorageService;
        this.freeModeService = freeModeService;
        this.configuredPublicBaseUrl = configuredPublicBaseUrl;
    }

    /**
     * Stores the uploaded image and points {@code brand_logo_url} at it,
     * returning the same {@link TenantSettingsResponse} shape {@code
     * /api/tenant/settings} returns so the caller refreshes its whole
     * settings state from one round trip.
     *
     * <p>Entitlement-gated identically to the branding fields on {@code PUT
     * /api/tenant/settings} - a FREE/PREMIUM tenant gets the same
     * upgrade-oriented 403 rather than a silent no-op, because these two
     * fields are the whole point of the entitlement.
     */
    @PostMapping("/api/tenant/branding/logo")
    public TenantSettingsResponse upload(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        String tenantId = tenantId();
        requireWhiteLabel(tenantId);
        Tenant tenant = requireTenant(tenantId);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No logo file was uploaded.");
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            throw new IllegalArgumentException(
                "Logo is too large - limit is " + (MAX_LOGO_BYTES / 1024 / 1024) + "MB.");
        }
        String extension = extensionFor(file.getContentType());
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded logo: " + e.getMessage());
        }

        // Built and validated BEFORE the object is written, so a deployment
        // whose public origin cannot be resolved fails without leaving an
        // orphaned object behind and without a half-applied brand.
        String publicUrl = publicLogoUrl(request, tenantId, extension);

        String objectKey = objectKey(extension);
        String previousObjectKey = objectKeyFor(tenant.getBrandLogoUrl(), tenantId);
        fileStorageService.put(objectKey, content, file.getContentType());
        // Only fires when the FORMAT changed (png -> webp, say); re-uploading
        // the same format overwrites objectKey above and this is skipped, so
        // it can never delete the object just written.
        if (previousObjectKey != null && !previousObjectKey.equals(objectKey)) {
            deleteQuietly(previousObjectKey);
        }
        tenant.updateBranding(tenant.getBrandDisplayName(), publicUrl);
        tenantRepository.save(tenant);
        return settings(tenantId);
    }

    /**
     * Removes the uploaded logo and clears {@code brand_logo_url}, putting
     * the tenant back on the platform mark. Idempotent: clearing a brand that
     * is already clear is a 200, not a 404 - the caller asked for an end
     * state, and it holds.
     *
     * <p>Only deletes from storage when the stored URL is one WE minted. A
     * tenant that set an externally-hosted logo through {@code PUT
     * /api/tenant/settings} still gets the column cleared (that is what they
     * asked for), but nothing is deleted, because there is nothing of theirs
     * in our bucket.
     */
    @DeleteMapping("/api/tenant/branding/logo")
    public TenantSettingsResponse remove() {
        String tenantId = tenantId();
        requireWhiteLabel(tenantId);
        Tenant tenant = requireTenant(tenantId);
        String objectKey = objectKeyFor(tenant.getBrandLogoUrl(), tenantId);
        if (objectKey != null) {
            deleteQuietly(objectKey);
        }
        tenant.updateBranding(tenant.getBrandDisplayName(), null);
        tenantRepository.save(tenant);
        return settings(tenantId);
    }

    /**
     * Serves an uploaded logo to anonymous viewers of a share page.
     *
     * <p><b>Every request re-checks the entitlement and the stored URL</b>
     * rather than trusting that an object exists at the key. That preserves
     * the deliberate double gate {@code TenantLimitsServiceImpl#brandLogoUrl}
     * already implements: a plan downgrade instantly restores platform
     * branding everywhere without anyone having to go and delete stored
     * values, and a tenant who clears their brand stops serving the old image
     * immediately even though the object may linger in the bucket. It also
     * means the storage key is never derived from raw path input - only from
     * a URL this controller itself minted and the tenant's own row still
     * points at.
     *
     * <p>{@code TenantContext} is set by hand and restored in a finally: this
     * path runs no {@code TenantContextFilter} (there is no JWT), and {@code
     * FileStorageService} resolves its physical key from that context. Same
     * hand-set-then-clear shape {@code AgentShareService#withResolvedShare}
     * uses for the other anonymous surfaces, and the only reason it is safe
     * here is that the tenant being entered is the one owning the row just
     * read, and the scope is a single object read.
     *
     * <p>Reads the bytes fully instead of streaming: the object is capped at
     * {@link #MAX_LOGO_BYTES} at write time, so this is a bounded read, and
     * try-with-resources closing one stream is materially simpler than the
     * request-completion close-callback {@code AccountController#getAvatar}
     * needs for its unbounded streaming case.
     */
    @GetMapping("/api/share/branding/{tenantId}/logo.{ext}")
    public ResponseEntity<byte[]> serve(@PathVariable("tenantId") String tenantId, @PathVariable("ext") String ext) {
        String extension = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        MediaType contentType = ALLOWED_FORMATS.get(extension);
        if (contentType == null) {
            return ResponseEntity.notFound().build();
        }
        Tenant tenant = tenantRepository.findBySchemaName(tenantId).orElse(null);
        if (tenant == null || !tenantLimits.whiteLabel(tenantId)) {
            return ResponseEntity.notFound().build();
        }
        // The tenant's CURRENT brand URL must be exactly the one being asked
        // for - this is what makes the key below trusted rather than
        // path-derived.
        String expected = String.format(PUBLIC_PATH_TEMPLATE, tenantId, extension);
        String stored = tenant.getBrandLogoUrl();
        if (stored == null || !stored.endsWith(expected)) {
            return ResponseEntity.notFound().build();
        }

        byte[] content;
        TenantContext previous = TenantContext.current().orElse(null);
        TenantContext.set(TenantContext.of(tenantId, "system:brand-logo:" + tenantId, Set.of()));
        try (InputStream in = fileStorageService.get(objectKey(extension))) {
            content = in.readAllBytes();
        } catch (FileStorageException | IOException e) {
            // The row points at an object that is gone (bucket wipe, partial
            // restore). Same "treat as no logo" fallback AccountController
            // makes for a missing avatar - the share page falls back to the
            // brand NAME, which is the correct degraded state, not a 5xx on
            // somebody's public page.
            return ResponseEntity.notFound().build();
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }

        return ResponseEntity.ok()
            .contentType(contentType)
            // The object is overwritten in place on re-upload, so this must
            // NOT be immutable the way DemoImageController's per-id key can
            // be - a short public TTL keeps a rebrand from being stuck in
            // caches (and CDNs) for days.
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
            .header("X-Content-Type-Options", "nosniff")
            .body(content);
    }

    /** Storage key for one format. Tenant scoping is added by the storage layer - see {@link #KEY_PREFIX}. */
    private static String objectKey(String extension) {
        return KEY_PREFIX + KEY_BASENAME + "." + extension;
    }

    /**
     * The storage key a stored brand URL refers to, or {@code null} when that
     * URL is not one of ours (an externally-hosted logo set through {@code
     * PUT /api/tenant/settings}) or is not set at all. Recovering the key
     * from the URL is what lets the column stay the single source of truth -
     * there is no second "which format is stored" column to keep in sync.
     */
    public static String objectKeyFor(String storedBrandLogoUrl, String tenantId) {
        if (!StringUtils.hasText(storedBrandLogoUrl)) {
            return null;
        }
        for (String extension : ALLOWED_FORMATS.keySet()) {
            if (storedBrandLogoUrl.endsWith(String.format(PUBLIC_PATH_TEMPLATE, tenantId, extension))) {
                return objectKey(extension);
            }
        }
        return null;
    }

    private void deleteQuietly(String objectKey) {
        try {
            fileStorageService.delete(objectKey);
        } catch (FileStorageException ignored) {
            // The new logo is already stored and the row already points at
            // it; failing the whole request over a leftover object would turn
            // a successful rebrand into an error the admin can do nothing
            // about.
        }
    }

    private static String extensionFor(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        String extension = CONTENT_TYPE_TO_EXTENSION.get(normalized);
        if (extension == null) {
            throw new IllegalArgumentException(
                "Logo must be a PNG, JPEG or WebP image (received "
                    + (StringUtils.hasText(normalized) ? normalized : "no content type")
                    + "). SVG is not accepted because it can carry scripts.");
        }
        return extension;
    }

    /**
     * {@code app.branding.public-base-url} when set, else the origin of the
     * current request (which honors {@code X-Forwarded-*} via {@code
     * server.forward-headers-strategy=native}) - the same resolution {@code
     * A2aAgentCardService#resolvePublicBaseUrl} makes.
     *
     * <p>Rejected rather than stored if that origin is not a public https
     * one. This value is baked into a column that public, unauthenticated
     * pages render, so an {@code http://localhost:8080} origin - what a
     * misconfigured proxy or a local run resolves to - would write a
     * permanently broken (and mixed-content) URL into every share page for
     * that tenant. Failing loudly at upload time is recoverable; a bad column
     * value needs a migration.
     */
    private String publicLogoUrl(HttpServletRequest request, String tenantId, String extension) {
        String base = StringUtils.hasText(configuredPublicBaseUrl)
            ? configuredPublicBaseUrl
            : ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        base = base.replaceAll("/+$", "");
        String url = base + String.format(PUBLIC_PATH_TEMPLATE, tenantId, extension);
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not build a public logo URL from base '" + base + "' - set app.branding.public-base-url.");
        }
        String host = uri.getHost();
        boolean publicHttps = "https".equalsIgnoreCase(uri.getScheme())
            && StringUtils.hasText(host)
            && !"localhost".equalsIgnoreCase(host)
            && !host.startsWith("127.")
            && !"[::1]".equals(host);
        if (!publicHttps) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "This deployment cannot mint a public logo URL: it resolved to '" + url
                    + "', which is not a public https address. Set app.branding.public-base-url to the API's "
                    + "public origin (e.g. https://api.example.com) and retry.");
        }
        if (url.length() > 512) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "The generated logo URL exceeds the 512-character brand_logo_url column width.");
        }
        return url;
    }

    private void requireWhiteLabel(String tenantId) {
        if (!tenantLimits.whiteLabel(tenantId)) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "White-labelling is not included in your plan - upgrade to Unlimited to replace "
                    + "VegaDuta branding with your own on shared agent pages."
            );
        }
    }

    private Tenant requireTenant(String tenantId) {
        return tenantRepository.findBySchemaName(tenantId)
            .orElseThrow(() -> new IllegalStateException("No Tenant row for schema '" + tenantId + "'"));
    }

    /** Mirrors {@code TenantSettingsController#get} so a branding write returns the same settings shape the caller already holds. */
    private TenantSettingsResponse settings(String tenantId) {
        Tenant tenant = tenantRepository.findBySchemaName(tenantId).orElse(null);
        return new TenantSettingsResponse(
            tenant != null ? tenant.getId() : null,
            tenantLimits.accountType(tenantId),
            tenantLimits.experienceMode(tenantId),
            tenantLimits.maxUsers(tenantId),
            tenantLimits.maxAgents(tenantId).orElse(null),
            tenant != null ? tenant.getName() : null,
            tenantLimits.plan(tenantId),
            tenantLimits.maxWorkflows(tenantId).orElse(null),
            tenantLimits.usePlatformLlm(tenantId),
            tenantLimits.byokAllowed(tenantId),
            tenantLimits.whatsappAllowed(tenantId),
            tenantLimits.whiteLabel(tenantId),
            tenantLimits.maxKbCollections(tenantId).orElse(null),
            tenantLimits.liveVisionAllowed(tenantId),
            tenant != null ? tenant.getBrandDisplayName() : null,
            tenant != null ? tenant.getBrandLogoUrl() : null,
            freeModeService.isEnabled(),
            tenantLimits.externalChannelsAllowed(tenantId),
            tenantLimits.developerApiAllowed(tenantId),
            tenantLimits.ssoAllowed(tenantId),
            tenantLimits.scimAllowed(tenantId),
            tenantLimits.workflowExportAllowed(tenantId),
            tenantLimits.automationAllowed(tenantId)
        );
    }

    private String tenantId() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated /api/tenant/branding request - "
                    + "SecurityConfig should have blocked this"));
    }
}
