package com.agenticai.core.storage;

import com.agenticai.core.security.TenantContext;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tenant-isolation layer of every object-store backed {@link FileStorageService} (S3 and
 * S3-compatible endpoints, Azure Blob Storage): it decides which PHYSICAL key a caller's logical
 * key maps to, refuses keys that belong to another tenant, and owns the legacy un-prefixed
 * fallback. A backend only implements the small set of physical-key primitives below ({@link
 * #writeObject}, {@link #readObject}, {@link #objectLength}, {@link #objectPresent}, {@link
 * #removeObject}, {@link #listTenantIds}, {@link #listObjects}) and reports "no such object" as
 * {@link ObjectNotFoundException} and any other failure as {@link BackendException}; it never
 * decides what key to touch. Keeping that decision in ONE class is the point: the isolation rules
 * below were written against a real cross-tenant read, and a second backend must not get a chance
 * to reimplement them differently.
 *
 * <h2>Storage-layer tenant isolation (infra audit fix, 2026-07-27)</h2>
 *
 * Before this fix, one shared root MinIO credential served every tenant and almost no object key
 * carried a tenant-ID prefix - isolation was purely a byproduct of the owning DB row being
 * tenant-schema-scoped, with no check that a resolved key actually belonged to the caller's tenant
 * before this class served it. This class now does two things about that, transparently to every
 * caller (no call-site changes needed for chat-attachments, knowledge-documents, generated-images,
 * sarvam-audio, elevenlabs-audio, or avatars - all of them run inside a JWT-authenticated request
 * that {@code TenantContextFilter} has already populated {@link TenantContext} for):
 *
 * <ol>
 *   <li><b>New keys are transparently tenant-scoped.</b> {@link #put} prefixes the physical S3 key
 *       with {@code tenant/<tenantId>/} whenever {@link TenantContext#current()} is present, where
 *       {@code tenantId} is whatever {@code TenantContext} carries (the JWT's {@code tenant_id}
 *       claim - see {@code TenantContextFilter} - which in this codebase is the tenant's schema
 *       name, not its UUID primary key). Callers keep passing/persisting the same bare logical key
 *       as before (e.g. {@code "chat-attachments/<agentId>/<uuid>"}) - {@link #get}/{@link
 *       #exists}/ {@link #delete} independently recompute the identical physical key from the SAME
 *       {@code TenantContext} mechanism, so the round trip "just works" with zero call-site
 *       changes. This is deliberately the centralized fix the task asked to prefer over touching
 *       every one of ~35 call sites: {@code storage} can depend on {@code security} (a leaf module,
 *       see this module's pom.xml) without creating a cycle.
 *   <li><b>Defense-in-depth ownership check.</b> {@link #get}/{@link #exists}/ {@link #delete}
 *       first check whether the CALLER-SUPPLIED key already looks like a fully-qualified {@code
 *       tenant/<tenantId>/...} key (i.e. someone is passing a physical key directly, not the bare
 *       logical key every caller in this codebase normally holds) - if so, and the embedded {@code
 *       tenantId} does not match the current {@link TenantContext}, the call is refused and logged
 *       rather than silently served. Ordinary callers never trip this (their keys never carry the
 *       prefix in the first place - it only exists on the physical object), so this is pure
 *       defense-in-depth against a key leaking across a tenant boundary some other way.
 * </ol>
 *
 * <p><b>Known limitation - pre-existing, un-prefixed objects.</b> Every object uploaded before this
 * change lives at its bare logical key with no tenant folder. {@link #get}/{@link #exists}
 * therefore fall back to the bare key when the tenant-prefixed lookup misses, and {@link #delete}
 * always attempts both the prefixed AND the bare key (S3/MinIO's delete is a no-op, not an error,
 * for a key that isn't there - see {@link #delete}'s own javadoc) so erasure/cleanup keeps working
 * for old data too. This class deliberately does NOT migrate/rename any existing object - that is
 * an operational bucket migration outside what a code change alone can safely do. Pre-existing
 * objects simply have no tenant prefix to isolation-check against; that gap cannot be closed
 * retroactively by this class.
 *
 * <h2>The legacy fallback is the cross-tenant edge (security audit, 2026-07-29)</h2>
 *
 * That fallback deserves naming for what it is: the bare-key namespace is <b>global</b> - every
 * tenant's legacy objects sit in one flat space with no tenant in the path - so any caller that
 * reaches {@link #get} with a key it did not derive from its OWN tenant-scoped DB row can read
 * across the boundary through it. That is not hypothetical: {@code AgentChatService}'s chat turn
 * passed {@code ChatRequest.attachmentKey} (raw, off the request body) straight into {@link #get},
 * so {@code "tenant-exports/<other tenant's uuid>/<millis>.json"} missed at {@code
 * tenant/<caller>/tenant-exports/...} and then resolved unprefixed - another tenant's full JSON
 * data export, read into the attacker's prompt. The call site is fixed at its own boundary (see
 * {@code AgentChatService.requireOwnAttachmentKey}); this class narrows the mechanism so the next
 * such call site is not a data breach:
 *
 * <ol>
 *   <li><b>Read fallback is allowlisted by prefix.</b> {@link #get}/{@link #exists} only retry the
 *       bare key for a prefix in {@link #LEGACY_FALLBACK_PREFIXES} - the namespaces some caller in
 *       this codebase actually reads back. {@code tenant-exports/} is deliberately excluded: it is
 *       the enumerable, highest-value namespace in the bucket and <b>nothing in this codebase ever
 *       reads it back</b> (the export is handed to the caller in the same response that writes it),
 *       so excluding it costs zero functionality. New prefixes added after 2026-07-27 do not belong
 *       here either - objects under them are written prefixed from day one and never need the
 *       fallback.
 *   <li><b>The whole fallback is disable-able</b> via {@code
 *       app.storage.legacy-unprefixed-fallback=false}, for a deployment whose bucket is known to
 *       hold no un-prefixed objects. Left ON by default because a code change cannot inspect the
 *       bucket, and defaulting it off would silently 404 real files.
 *   <li><b>{@link #delete} is NOT gated</b> - see its javadoc for why.
 * </ol>
 */
public abstract class TenantScopedFileStorage implements FileStorageService {

    /** New-key tenant-scoping prefix - see this class's javadoc. */
    static final String TENANT_KEY_PREFIX = "tenant/";

    /**
     * Recognizes a key that ALREADY carries our own tenant-scoping convention, for the
     * defense-in-depth ownership check.
     */
    private static final Pattern OWN_TENANT_PREFIX =
            Pattern.compile("^tenant/([^/]+)/.*", Pattern.DOTALL);

    /**
     * The only key namespaces {@link #get}/{@link #exists} may retry un-prefixed after a
     * tenant-scoped miss - see this class's javadoc for why this is an allowlist and not
     * "everything".
     *
     * <p>Derived from the actual {@code put}/{@code get} call sites across the modules ({@code
     * core/agents}, {@code core/knowledge}, {@code core/imagegen}, {@code core/sarvam}, {@code
     * core/elevenlabs}, {@code core/tenancy}) as of 2026-07-29. Same "duplicated literal, no shared
     * constant class" trade-off as the {@code demo-images/} literal below - {@code storage} must
     * stay a near-leaf module and cannot depend on the six modules that own these strings.
     *
     * <p><b>Notably absent: {@code tenant-exports/}.</b> That is a decision, not an oversight - it
     * is the one namespace holding a whole tenant's database contents in a single guessable object,
     * and no code path in this repo ever calls {@code get}/{@code exists} on it, so denying the
     * fallback there breaks nothing while removing the audit's exact exploit. Erasure of legacy
     * exports still works because {@link #delete} is not gated by this list.
     */
    static final Set<String> LEGACY_FALLBACK_PREFIXES =
            Set.of(
                    "avatars/",
                    "chat-attachments/",
                    "demo-images/",
                    "demo-videos/",
                    "elevenlabs-audio/",
                    "generated-images/",
                    "generated-videos/",
                    "knowledge-documents/",
                    "sarvam-audio/");

    private static final Logger log = LoggerFactory.getLogger(TenantScopedFileStorage.class);

    protected final StorageProperties properties;

    protected TenantScopedFileStorage(StorageProperties properties) {
        this.properties = properties;
    }

    /** The physical object does not exist (S3 {@code NoSuchKey}, Blob {@code BlobNotFound}). */
    protected static final class ObjectNotFoundException extends RuntimeException {
        public ObjectNotFoundException(Throwable cause) {
            super(cause);
        }
    }

    /** Any other backend failure; the cause is the vendor SDK's exception. */
    protected static final class BackendException extends RuntimeException {
        public BackendException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    /**
     * Receives one listed object: its physical key and last-modified time in epoch millis (0 if
     * unknown).
     */
    @FunctionalInterface
    protected interface ObjectListener {
        void accept(String physicalKey, long lastModifiedMillis);
    }

    // ---- backend primitives: physical keys only -------------------------------------------------

    protected abstract void writeObject(String physicalKey, byte[] content, String contentType);

    /**
     * Opens the object (or, when {@code rangeSpec} is non-null, an HTTP-style {@code
     * bytes=start-end} window). The stream may hold a connection; the caller closes it.
     */
    protected abstract InputStream readObject(String physicalKey, @Nullable String rangeSpec);

    protected abstract long objectLength(String physicalKey);

    /** {@code false} (not an exception) for a missing object. */
    protected abstract boolean objectPresent(String physicalKey);

    /** Idempotent: removing an object that is not there is not an error. */
    protected abstract void removeObject(String physicalKey);

    /** Tenant ids that have at least one object under {@link #TENANT_KEY_PREFIX}. */
    protected abstract List<String> listTenantIds();

    /** Every object whose physical key starts with {@code physicalPrefix}. */
    protected abstract void listObjects(String physicalPrefix, ObjectListener listener);

    // ---- the isolation rules
    // ---------------------------------------------------------------------

    @Override
    public void put(String key, byte[] content, String contentType) {
        String physicalKey = tenantScopedKey(key);
        try {
            writeObject(physicalKey, content, contentType);
        } catch (BackendException e) {
            throw new FileStorageException("Could not upload object '" + key + "'", e.getCause());
        }
    }

    /**
     * Streams the object rather than materializing it, so the caller MUST close the stream (Spring
     * does for an {@code InputStreamResource} body). A 60 MB video is never held in heap twice.
     */
    @Override
    public InputStream get(String key) {
        verifyTenantOwnership(key);
        String physicalKey = tenantScopedKey(key);
        try {
            return readObject(physicalKey, null);
        } catch (ObjectNotFoundException notFoundAtPhysicalKey) {
            if (physicalKey.equals(key) || !legacyFallbackAllowed(key, "read")) {
                throw new FileStorageException(
                        "No object at key '" + key + "'", notFoundAtPhysicalKey.getCause());
            }
            // Fallback for an object uploaded before tenant-prefix isolation existed - see this
            // class's javadoc, and "The legacy fallback is the cross-tenant edge" for why reaching
            // this line is allowlisted rather than automatic.
            try {
                return readObject(key, null);
            } catch (ObjectNotFoundException stillNotFound) {
                throw new FileStorageException(
                        "No object at key '" + key + "'", stillNotFound.getCause());
            } catch (BackendException e) {
                throw new FileStorageException(
                        "Could not download object '" + key + "'", e.getCause());
            }
        } catch (BackendException e) {
            throw new FileStorageException("Could not download object '" + key + "'", e.getCause());
        }
    }

    /**
     * Deliberately a structural clone of {@link #get} - same {@link #verifyTenantOwnership} first,
     * same tenant-scoped primary lookup, same {@link #legacyFallbackAllowed}-gated bare-key retry.
     * Kept in lockstep with {@code get} on purpose: a size an attacker can measure for an object
     * {@code get} refuses to serve is a (small) cross-tenant disclosure of its own.
     */
    @Override
    public long size(String key) {
        verifyTenantOwnership(key);
        String physicalKey = tenantScopedKey(key);
        try {
            return objectLength(physicalKey);
        } catch (ObjectNotFoundException notFoundAtPhysicalKey) {
            if (physicalKey.equals(key) || !legacyFallbackAllowed(key, "size lookup")) {
                throw new FileStorageException(
                        "No object at key '" + key + "'", notFoundAtPhysicalKey.getCause());
            }
            try {
                return objectLength(key);
            } catch (ObjectNotFoundException stillNotFound) {
                throw new FileStorageException(
                        "No object at key '" + key + "'", stillNotFound.getCause());
            } catch (BackendException e) {
                throw new FileStorageException(
                        "Could not read size of object '" + key + "'", e.getCause());
            }
        } catch (BackendException e) {
            throw new FileStorageException(
                    "Could not read size of object '" + key + "'", e.getCause());
        }
    }

    /**
     * Same lockstep-with-{@link #get} rules as {@link #size}; only the requested window crosses the
     * wire.
     */
    @Override
    public InputStream getRange(String key, long start, long endInclusive) {
        if (start < 0 || endInclusive < start) {
            throw new IllegalArgumentException(
                    "Invalid byte range "
                            + start
                            + "-"
                            + endInclusive
                            + " - start must be >= 0 and end >= start");
        }
        verifyTenantOwnership(key);
        String physicalKey = tenantScopedKey(key);
        String rangeSpec = "bytes=" + start + "-" + endInclusive;
        try {
            return readObject(physicalKey, rangeSpec);
        } catch (ObjectNotFoundException notFoundAtPhysicalKey) {
            if (physicalKey.equals(key) || !legacyFallbackAllowed(key, "ranged read")) {
                throw new FileStorageException(
                        "No object at key '" + key + "'", notFoundAtPhysicalKey.getCause());
            }
            try {
                return readObject(key, rangeSpec);
            } catch (ObjectNotFoundException stillNotFound) {
                throw new FileStorageException(
                        "No object at key '" + key + "'", stillNotFound.getCause());
            } catch (BackendException e) {
                throw new FileStorageException(
                        "Could not download range of object '" + key + "'", e.getCause());
            }
        } catch (BackendException e) {
            throw new FileStorageException(
                    "Could not download range of object '" + key + "'", e.getCause());
        }
    }

    @Override
    public boolean exists(String key) {
        verifyTenantOwnership(key);
        String physicalKey = tenantScopedKey(key);
        if (present(physicalKey)) {
            return true;
        }
        return !physicalKey.equals(key)
                && legacyFallbackAllowed(key, "existence check")
                && present(key);
    }

    private boolean present(String physicalKey) {
        try {
            return objectPresent(physicalKey);
        } catch (BackendException e) {
            throw new FileStorageException(
                    "Could not check existence of object '" + physicalKey + "'", e.getCause());
        }
    }

    /**
     * NOT gated by the legacy allowlist: deletion attempts both the tenant-scoped and the bare key
     * so erasure and cleanup reach pre-prefix data too. Removing a key that is not there is a
     * no-op.
     */
    @Override
    public void delete(String key) {
        verifyTenantOwnership(key);
        String physicalKey = tenantScopedKey(key);
        remove(physicalKey);
        if (!physicalKey.equals(key)) {
            remove(key);
        }
    }

    private void remove(String physicalKey) {
        try {
            removeObject(physicalKey);
        } catch (BackendException e) {
            throw new FileStorageException(
                    "Could not delete object '" + physicalKey + "'", e.getCause());
        }
    }

    @Override
    public List<StoredObject> listAcrossTenants(String logicalPrefix) {
        List<StoredObject> out = new ArrayList<>();
        try {
            for (String tenantId : listTenantIds()) {
                listUnder(
                        TENANT_KEY_PREFIX + tenantId + "/" + logicalPrefix,
                        tenantId,
                        TENANT_KEY_PREFIX + tenantId + "/",
                        out);
            }
            listUnder(logicalPrefix, null, "", out);
        } catch (BackendException e) {
            log.warn(
                    "Cross-tenant object listing for '{}' failed: {}",
                    logicalPrefix,
                    e.getMessage());
        }
        return out;
    }

    private void listUnder(
            String physicalPrefix,
            @Nullable String tenantId,
            String stripPrefix,
            List<StoredObject> out) {
        try {
            listObjects(
                    physicalPrefix,
                    (physicalKey, lastModifiedMillis) ->
                            out.add(
                                    new StoredObject(
                                            tenantId,
                                            physicalKey.substring(stripPrefix.length()),
                                            lastModifiedMillis)));
        } catch (BackendException e) {
            log.warn("Listing '{}' failed: {}", physicalPrefix, e.getMessage());
        }
    }

    private String tenantScopedKey(String key) {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .filter(id -> id != null && !id.isBlank())
                .map(tenantId -> TENANT_KEY_PREFIX + tenantId + "/" + key)
                .orElse(key);
    }

    /**
     * Whether a tenant-prefixed MISS may be retried at the bare, globally shared key - the one
     * place a read can cross a tenant boundary in this class, so it is a decision with a name
     * rather than an implicit {@code catch} branch. See this class's javadoc for the full reasoning
     * and {@link #LEGACY_FALLBACK_PREFIXES} for what is and isn't on the list.
     *
     * <p>Logged at WARN when refused, not silently: a refusal is either a genuinely absent object
     * (harmless, and the caller gets the same "no object at key" it would have got anyway) or
     * somebody reaching for a namespace they have no business reading. Both are worth a line, and
     * neither is frequent enough for this to be noisy - every legitimate object written since
     * 2026-07-27 inside a request resolves at its tenant-prefixed key and never reaches here.
     *
     * @param operation what the caller was doing, for the log line only
     */
    private boolean legacyFallbackAllowed(String key, String operation) {
        if (!properties.isLegacyUnprefixedFallback()) {
            log.warn(
                    "Refused un-prefixed legacy fallback for {} of key '{}' -"
                        + " app.storage.legacy-unprefixed-fallback is off. If this object is real,"
                        + " it predates tenant prefixing and needs a bucket migration.",
                    operation,
                    key);
            return false;
        }
        boolean allowed = LEGACY_FALLBACK_PREFIXES.stream().anyMatch(key::startsWith);
        if (!allowed) {
            log.warn(
                    "Refused un-prefixed legacy fallback for {} of key '{}' - its prefix is not one"
                        + " of the known legacy namespaces. The bare-key namespace is shared by ALL"
                        + " tenants, so this fallback is allowlisted; see TenantScopedFileStorage's"
                        + " javadoc.",
                    operation,
                    key);
        }
        return allowed;
    }

    /**
     * Defense-in-depth: refuses a {@link #get}/{@link #exists}/{@link #delete} call whose
     * CALLER-SUPPLIED key already carries our {@code tenant/<tenantId>/} convention for a DIFFERENT
     * tenant than {@link TenantContext#current()} names. Ordinary callers never trip this - every
     * caller in this codebase holds the bare logical key, never the physical prefixed one - so this
     * only matters if a fully-qualified key somehow crosses a tenant boundary some other way (a
     * bug, a stale reference, etc.). No-ops when the key doesn't match the pattern at all, or when
     * there's no current {@link TenantContext} to check against (e.g. a background job) - it can
     * only block, never substitute for, the tenant-scoped DB-row lookups every caller already does
     * first.
     */
    private void verifyTenantOwnership(String key) {
        Matcher matcher = OWN_TENANT_PREFIX.matcher(key);
        if (!matcher.matches()) {
            return;
        }
        String embeddedTenantId = matcher.group(1);
        TenantContext.current()
                .map(TenantContext::getTenantId)
                .ifPresent(
                        currentTenantId -> {
                            if (currentTenantId != null
                                    && !currentTenantId.isBlank()
                                    && !currentTenantId.equals(embeddedTenantId)) {
                                log.error(
                                        "Blocked cross-tenant object storage access: key '{}' is"
                                            + " scoped to tenant '{}', but the current request's"
                                            + " TenantContext is tenant '{}'",
                                        key,
                                        embeddedTenantId,
                                        currentTenantId);
                                throw new FileStorageException(
                                        "Object key '"
                                                + key
                                                + "' does not belong to the current tenant",
                                        null);
                            }
                        });
    }
}
