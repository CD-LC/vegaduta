package com.agenticai.core.storage;

import com.agenticai.core.security.TenantContext;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Filesystem-backed {@link FileStorageService} for single-node / self-hosted
 * installs that do not run MinIO or S3. Opt-in only:
 * {@code app.storage.type=local} (env {@code APP_STORAGE_TYPE=local}); the
 * root directory is {@code app.storage.local.path} (env
 * {@code APP_STORAGE_LOCAL_PATH}). With the property unset,
 * {@link S3FileStorageService} is active exactly as before.
 *
 * <p>Semantics deliberately mirror {@link S3FileStorageService}:
 * <ul>
 *   <li>The same tenant key scoping - a key written while a {@link
 *       TenantContext} is active is stored under {@code tenant/<id>/<key>}.</li>
 *   <li>The same cross-tenant guard - a caller-supplied key that already
 *       embeds another tenant's {@code tenant/<id>/} prefix is refused.</li>
 *   <li>The same bare-key fallback for reads (objects written with no
 *       TenantContext, e.g. by background jobs), gated by
 *       {@code app.storage.legacy-unprefixed-fallback} and the same prefix
 *       allowlist - AND, unlike S3, by {@code
 *       app.storage.local.legacy-unprefixed-fallback}, which defaults to
 *       {@code false} (SEC-051): a tenant request never reads the global bare
 *       namespace unless an operator opts in.</li>
 * </ul>
 *
 * <p>Filesystem-specific hardening:
 * <ul>
 *   <li><b>Path traversal:</b> keys are rejected if absolute, containing
 *       {@code ..}/backslashes/NUL/empty segments, and every resolved path
 *       is normalized and must remain under the configured root.</li>
 *   <li><b>Atomic writes:</b> content goes to a temp file in the target
 *       directory and is then moved into place ({@code ATOMIC_MOVE} where the
 *       filesystem supports it), so a reader never sees a half-written
 *       object.</li>
 * </ul>
 *
 * <p>Known differences from S3 (documented, not bugs): content type is not
 * persisted (the interface never returns it); the S3 bucket lifecycle rule
 * that expires {@code demo-images/} has no equivalent here, so those files
 * persist until deleted; and there is no multi-node sharing - every core
 * replica must mount the same volume, or run exactly one replica.
 */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local")
public class LocalFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    private static final String TENANT_KEY_PREFIX = "tenant/";
    private static final Pattern OWN_TENANT_PREFIX = Pattern.compile("^tenant/([^/]+)/.*");
    private static final String TEMP_PREFIX = ".upload-";

    private final StorageProperties properties;
    private final Path root;

    public LocalFileStorageService(StorageProperties properties) {
        this.properties = properties;
        String configured = properties.getLocal().getPath();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                "app.storage.type=local requires app.storage.local.path (env APP_STORAGE_LOCAL_PATH) to be set");
        }
        this.root = Path.of(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Could not create local storage root '" + root + "' (app.storage.local.path) - "
                    + "check the directory exists and is writable by the core process", e);
        }
        if (!Files.isWritable(root)) {
            throw new IllegalStateException("Local storage root '" + root + "' is not writable by the core process");
        }
        log.info("File storage: local filesystem at {}", root);
    }

    /** Visible for tests. */
    Path root() {
        return root;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        Path target = resolve(tenantScopedKey(validateKey(key)));
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), TEMP_PREFIX, ".tmp");
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            throw new FileStorageException("Could not store object '" + key + "'", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort cleanup of an orphaned temp file
                }
            }
        }
    }

    @Override
    public InputStream get(String key) {
        Path path = locateExisting(key, "read");
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw new FileStorageException("No object at key '" + key + "'", e);
        } catch (IOException e) {
            throw new FileStorageException("Could not read object '" + key + "'", e);
        }
    }

    @Override
    public long size(String key) {
        Path path = locateExisting(key, "size lookup");
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new FileStorageException("Could not read size of object '" + key + "'", e);
        }
    }

    @Override
    public InputStream getRange(String key, long start, long endInclusive) {
        if (start < 0 || endInclusive < start) {
            throw new IllegalArgumentException(
                "Invalid byte range " + start + "-" + endInclusive + " - start must be >= 0 and end >= start");
        }
        Path path = locateExisting(key, "ranged read");
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            long size = channel.size();
            if (start >= size) {
                channel.close();
                throw new FileStorageException(
                    "Range " + start + "-" + endInclusive + " not satisfiable for object '" + key + "' of size " + size, null);
            }
            long length = Math.min(endInclusive, size - 1) - start + 1;
            channel.position(start);
            return new BoundedInputStream(Channels.newInputStream(channel), length);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new FileStorageException("Could not read range of object '" + key + "'", e);
        }
    }

    @Override
    public boolean exists(String key) {
        String validated = validateKey(key);
        verifyTenantOwnership(validated);
        String physicalKey = tenantScopedKey(validated);
        if (Files.isRegularFile(resolve(physicalKey))) {
            return true;
        }
        return !physicalKey.equals(validated)
            && legacyFallbackAllowed(validated, "existence check")
            && Files.isRegularFile(resolve(validated));
    }

    @Override
    public void delete(String key) {
        String validated = validateKey(key);
        verifyTenantOwnership(validated);
        String physicalKey = tenantScopedKey(validated);
        deleteFile(physicalKey);
        if (!physicalKey.equals(validated)) {
            // Same as S3FileStorageService#delete: the bare copy is removed too
            // (not gated by the legacy fallback flag).
            deleteFile(validated);
        }
    }

    private void deleteFile(String physicalKey) {
        try {
            Files.deleteIfExists(resolve(physicalKey));
        } catch (IOException e) {
            throw new FileStorageException("Could not delete object '" + physicalKey + "'", e);
        }
    }

    /** Resolves the tenant-scoped path, falling back to the bare key under the same rules as S3. */
    private Path locateExisting(String key, String operation) {
        String validated = validateKey(key);
        verifyTenantOwnership(validated);
        String physicalKey = tenantScopedKey(validated);
        Path scoped = resolve(physicalKey);
        if (Files.isRegularFile(scoped)) {
            return scoped;
        }
        if (!physicalKey.equals(validated) && legacyFallbackAllowed(validated, operation)) {
            Path bare = resolve(validated);
            if (Files.isRegularFile(bare)) {
                return bare;
            }
        }
        throw new FileStorageException("No object at key '" + key + "'", null);
    }

    /**
     * Rejects keys that could escape the root or alias another object.
     * Keys are S3-style: forward-slash separated, relative, no dot segments.
     */
    static String validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be blank");
        }
        if (key.indexOf('\0') >= 0 || key.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Storage key contains an illegal character: '" + key + "'");
        }
        if (key.startsWith("/") || key.endsWith("/") || (key.length() > 1 && key.charAt(1) == ':')) {
            throw new IllegalArgumentException("Storage key must be a relative path: '" + key + "'");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Storage key contains an illegal path segment: '" + key + "'");
            }
            if (segment.startsWith(TEMP_PREFIX)) {
                throw new IllegalArgumentException("Storage key segment uses a reserved prefix: '" + key + "'");
            }
        }
        return key;
    }

    /** Second line of defence after {@link #validateKey}: the normalized path must stay under root. */
    private Path resolve(String physicalKey) {
        // Re-validate AFTER tenant prefixing so a malformed tenant id can't
        // introduce dot segments either.
        validateKey(physicalKey);
        Path resolved = root.resolve(physicalKey).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            log.error("Blocked storage path traversal attempt: key '{}' resolves outside {}", physicalKey, root);
            throw new FileStorageException("Object key '" + physicalKey + "' resolves outside the storage root", null);
        }
        return resolved;
    }

    private String tenantScopedKey(String key) {
        return TenantContext.current()
            .map(TenantContext::getTenantId)
            .filter(id -> id != null && !id.isBlank())
            .map(tenantId -> TENANT_KEY_PREFIX + tenantId + "/" + key)
            .orElse(key);
    }

    private boolean legacyFallbackAllowed(String key, String operation) {
        // SEC-051: local stores default the fallback OFF (Local#legacyUnprefixedFallback).
        if (!properties.isLegacyUnprefixedFallback() || !properties.getLocal().isLegacyUnprefixedFallback()) {
            return false;
        }
        boolean allowed = S3FileStorageService.LEGACY_FALLBACK_PREFIXES.stream().anyMatch(key::startsWith);
        if (!allowed) {
            log.debug("Refused un-prefixed fallback for {} of key '{}' - prefix not allowlisted", operation, key);
        }
        return allowed;
    }

    private void verifyTenantOwnership(String key) {
        Matcher matcher = OWN_TENANT_PREFIX.matcher(key);
        if (!matcher.matches()) {
            return;
        }
        String embeddedTenantId = matcher.group(1);
        TenantContext.current().map(TenantContext::getTenantId).ifPresent(currentTenantId -> {
            if (currentTenantId != null && !currentTenantId.isBlank() && !currentTenantId.equals(embeddedTenantId)) {
                log.error(
                    "Blocked cross-tenant object storage access: key '{}' is scoped to tenant '{}', but the "
                        + "current request's TenantContext is tenant '{}'",
                    key, embeddedTenantId, currentTenantId
                );
                throw new FileStorageException("Object key '" + key + "' does not belong to the current tenant", null);
            }
        });
    }

    private static void closeQuietly(@Nullable FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    /** Limits reads to {@code remaining} bytes and closes the underlying channel stream on close. */
    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
