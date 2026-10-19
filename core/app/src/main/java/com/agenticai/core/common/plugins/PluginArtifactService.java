package com.agenticai.core.common.plugins;

import com.agenticai.core.common.plugins.PluginCatalogController.PluginArtifact;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores and serves the first-party plugin binaries the {@code plugins.catalog} entries
 * point at, under {@code plugin-artifacts/{pluginId}/{version}/{fileName}} in object
 * storage. Every path segment is validated against {@link #SEGMENT} (and {@code .}/
 * {@code ..} rejected outright) on BOTH the write and read side, so the public download
 * endpoint can never be steered outside its own prefix - this is a fixed three-segment
 * lookup, never a bucket browser.
 *
 * <h2>Bare keys, deliberately outside tenant scoping</h2>
 * {@code S3FileStorageService} transparently prefixes physical keys with
 * {@code tenant/<tenantId>/} whenever a {@link TenantContext} is present (see its
 * javadoc). Plugin artifacts are PLATFORM-owned, tenant-free objects: the upload runs
 * inside a platform-admin's JWT request (TenantContext present - would write
 * {@code tenant/<adminTenant>/plugin-artifacts/...}), while the download is permitAll
 * (usually NO TenantContext - would read the bare key) - so without intervention the
 * two sides would miss each other. Both sides therefore run inside
 * {@link #withBareStorageKeys}, which saves/clears/restores the caller's context around
 * the storage call so keys are written AND read bare. The bare-key path was verified
 * against {@code S3FileStorageService}: with no context, {@code tenantScopedKey} returns
 * the key unchanged, {@code verifyTenantOwnership} no-ops (the key never starts with
 * {@code tenant/}), and no legacy-fallback allowlisting is involved (the primary lookup
 * IS the bare key) - so no {@code LEGACY_FALLBACK_PREFIXES} change is needed.
 */
@Service
public class PluginArtifactService {

    static final String KEY_PREFIX = "plugin-artifacts/";

    /**
     * The only characters a pluginId/version/fileName segment may contain. Note the
     * class deliberately excludes {@code /}, {@code \} and {@code %} - an encoded
     * {@code %2F} arrives here still carrying its literal {@code %} (and Spring
     * Security's firewall rejects encoded slashes in paths before this code runs
     * anyway), so it fails this pattern rather than ever decoding into a separator.
     * {@code .}/{@code ..} match the pattern (dots are legal in filenames) and are
     * rejected explicitly in {@link #isSafeSegment}.
     */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    /** The three artifact formats the three first-party plugins actually ship as. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".vsix", ".zip", ".crx");

    private final FileStorageService fileStorageService;
    private final PluginCatalogService catalogService;

    public PluginArtifactService(FileStorageService fileStorageService, PluginCatalogService catalogService) {
        this.fileStorageService = fileStorageService;
        this.catalogService = catalogService;
    }

    /**
     * What the upload endpoint returns: exactly the {@link PluginArtifact} fragment
     * web-admin merges into the catalog entry on upload success, plus the echo of
     * where it landed.
     */
    public record StoredArtifact(String pluginId, String version, PluginArtifact artifact) {
    }

    /**
     * Validates, digests, and stores an uploaded plugin build. {@code pluginId} must
     * name an entry in the EFFECTIVE catalog (stored-setting-wins - see
     * {@link PluginCatalogService#resolveCatalog}); the file name is taken from the
     * upload's original name with any client-supplied directory part stripped.
     * Re-uploading the same pluginId/version/fileName overwrites (S3 put semantics) -
     * that is the intended "re-cut a bad build" path, not an error.
     *
     * @throws IllegalArgumentException on any validation failure (mapped to 400 by
     *     {@code GlobalExceptionHandler})
     */
    public StoredArtifact store(String pluginId, String version, MultipartFile file) {
        List<String> knownIds = catalogService.resolveCatalog().plugins().stream()
            .map(PluginCatalogController.PluginEntry::id)
            .toList();
        if (!knownIds.contains(pluginId)) {
            throw new IllegalArgumentException(
                "Unknown pluginId '" + pluginId + "' - not in the plugin catalog (" + String.join(", ", knownIds) + ").");
        }
        requireSafeSegment(pluginId, "pluginId");
        requireSafeSegment(version, "version");
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded plugin artifact is empty.");
        }
        String fileName = stripDirectories(file.getOriginalFilename());
        requireSafeSegment(fileName, "file name");
        String extension = extensionOf(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                "Unsupported plugin artifact type '" + extension + "' - allowed: " + ALLOWED_EXTENSIONS + ".");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read uploaded plugin artifact: " + e.getMessage());
        }
        String sha256 = sha256Hex(content);
        String key = KEY_PREFIX + pluginId + "/" + version + "/" + fileName;
        withBareStorageKeys(() -> {
            fileStorageService.put(key, content, "application/octet-stream");
            return null;
        });
        return new StoredArtifact(pluginId, version, new PluginArtifact(fileName, content.length, sha256));
    }

    /** An open artifact stream plus the metadata the download response needs. The caller owns closing the stream. */
    public record ArtifactDownload(InputStream stream, long sizeBytes, String fileName) {
    }

    /**
     * Opens a stored artifact for the public download endpoint. Any malformed segment
     * gets the same {@link NoSuchElementException} (404 via {@code
     * GlobalExceptionHandler}) as a genuinely absent object - anti-enumeration, and a
     * traversal probe deserves no more detail than "not found".
     */
    public ArtifactDownload open(String pluginId, String version, String fileName) {
        if (!isSafeSegment(pluginId) || !isSafeSegment(version) || !isSafeSegment(fileName)
            || !ALLOWED_EXTENSIONS.contains(extensionOf(fileName))) {
            throw new NoSuchElementException("No plugin artifact at that path.");
        }
        String key = KEY_PREFIX + pluginId + "/" + version + "/" + fileName;
        return withBareStorageKeys(() -> {
            if (!fileStorageService.exists(key)) {
                throw new NoSuchElementException("No plugin artifact at that path.");
            }
            // size() before get() so Content-Length is known without buffering the
            // stream; both resolve the same bare key under the same cleared context.
            long size = fileStorageService.size(key);
            return new ArtifactDownload(fileStorageService.get(key), size, fileName);
        });
    }

    private static void requireSafeSegment(String value, String what) {
        if (!isSafeSegment(value)) {
            throw new IllegalArgumentException(
                "Invalid " + what + " - only letters, digits, '.', '_' and '-' are allowed.");
        }
    }

    private static boolean isSafeSegment(String value) {
        return value != null
            && SEGMENT.matcher(value).matches()
            && !".".equals(value)
            && !"..".equals(value);
    }

    /** Strips any client-supplied directory part (some browsers/tools send full paths). */
    private static String stripDirectories(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        int lastSeparator = Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\'));
        return lastSeparator >= 0 ? originalFilename.substring(lastSeparator + 1) : originalFilename;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; reaching this is a broken runtime, not a request problem.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Runs {@code action} with NO {@link TenantContext} so {@code S3FileStorageService}
     * neither tenant-prefixes the physical key nor ownership-checks it (see the class
     * javadoc), then restores whatever context the caller had - the surrounding request
     * (audit logging, further tenant-scoped work) must be unaffected.
     */
    private static <T> T withBareStorageKeys(Supplier<T> action) {
        TenantContext saved = TenantContext.current().orElse(null);
        TenantContext.clear();
        try {
            return action.get();
        } finally {
            if (saved != null) {
                TenantContext.set(saved);
            }
        }
    }
}
