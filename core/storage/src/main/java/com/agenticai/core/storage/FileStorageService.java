package com.agenticai.core.storage;

import java.io.InputStream;
import org.jspecify.annotations.Nullable;

/**
 * S3-API object storage, abstracted behind this interface so every caller (Sarvam's generated audio
 * today; chat file uploads and knowledge-base multi-modal ingestion next) depends on this contract,
 * never on the AWS SDK directly - swapping the implementation (or just the backing endpoint, via
 * {@link StorageProperties}) never touches a caller. See {@link S3FileStorageService}'s javadoc for
 * the one production implementation.
 */
public interface FileStorageService {

    /**
     * Uploads {@code content} under {@code key} (a caller-chosen path such as {@code
     * "sarvam-audio/<uuid>.wav"} - this service does not invent or namespace keys itself, since
     * different callers want different conventions).
     */
    void put(String key, byte[] content, String contentType);

    /**
     * @return the object's bytes as a stream - the caller is responsible for closing it.
     */
    InputStream get(String key);

    /**
     * The object's total size in bytes (S3 {@code HeadObject} content length), resolved through
     * exactly the same tenant-prefixing and legacy-fallback rules as {@link #get} - so a key {@code
     * get} would serve always has a measurable size, and a key {@code get} would refuse is refused
     * here too rather than leaking "how big is another tenant's object" as a side channel.
     *
     * <p>Added (2026-08-04, edge-capabilities plan item X-2) so HTTP callers can honour
     * open-ended/suffix {@code Range} requests, which need the total length for a correct {@code
     * Content-Range} - see {@code VideoGenerationController}.
     *
     * @throws FileStorageException if no object exists at this key, or the lookup fails
     */
    long size(String key);

    /**
     * Streams {@code [start, endInclusive]} of the object (an HTTP-style closed byte interval,
     * matching {@code Range: bytes=start-end}) via a ranged S3 {@code GetObject} - only the
     * requested window crosses the wire, unlike {@link #get} + skip/truncate client-side. Same
     * tenant-prefixing and legacy-fallback rules as {@link #get}; the caller is responsible for
     * closing the returned stream.
     *
     * <p>Callers should derive {@code start}/{@code endInclusive} from {@link #size} - a range
     * starting at or beyond the object's end is an S3-level error surfaced as a {@link
     * FileStorageException}, not an empty stream.
     *
     * @throws IllegalArgumentException if {@code start} is negative or {@code endInclusive < start}
     *     (a caller bug, not a storage outcome)
     * @throws FileStorageException if no object exists at this key, the range is not satisfiable,
     *     or the download fails
     */
    InputStream getRange(String key, long start, long endInclusive);

    /**
     * One stored object as seen by {@link #listAcrossTenants}: the owning tenant id (null for an
     * un-prefixed legacy object), the LOGICAL key (what callers pass to get/delete inside that
     * tenant's context) and the last-modified time.
     */
    record StoredObject(@Nullable String tenantId, String key, long lastModifiedMillis) {}

    /**
     * Lists every object whose logical key starts with {@code logicalPrefix}, across ALL tenants.
     * Maintenance-only (startup sweeps of expiring artifacts) - not for request paths. Default:
     * empty (implementations that cannot list simply opt out of the sweep).
     */
    default java.util.List<StoredObject> listAcrossTenants(String logicalPrefix) {
        return java.util.List.of();
    }

    /** True if an object exists at this key. */
    boolean exists(String key);

    /**
     * No-op (not an error) if nothing exists at this key - matches {@code
     * java.io.File#delete}-adjacent "deleting something already gone is fine" semantics.
     */
    void delete(String key);
}
