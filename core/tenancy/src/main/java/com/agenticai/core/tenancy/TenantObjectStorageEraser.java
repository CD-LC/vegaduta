package com.agenticai.core.tenancy;

import com.agenticai.core.storage.FileStorageService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Erasure strategy for object storage (S3/MinIO): avatars plus every tenant-table column known to
 * carry an object key, and the read-only count twin used by the data-usage stat. Extracted
 * unchanged from {@link TenantDataErasureService}; every deletion is best-effort.
 */
final class TenantObjectStorageEraser {

    private static final Logger log = LoggerFactory.getLogger(TenantObjectStorageEraser.class);

    private final DataSource dataSource;
    private final AppUserRepository appUserRepository;
    private final FileStorageService fileStorageService;

    TenantObjectStorageEraser(
            DataSource dataSource,
            AppUserRepository appUserRepository,
            FileStorageService fileStorageService) {
        this.dataSource = dataSource;
        this.appUserRepository = appUserRepository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Infra audit fix (2026-07-27) - every schema-scoped tenant table known to carry a MinIO/S3
     * object key, enumerated here as {@code {table, column}} pairs rather than one query per table
     * hand-written below. Deliberately a plain {@code List} of raw SQL identifiers (not a
     * dependency on each owning module's entity class) for the exact same reason {@link
     * TenantDataExportService}'s {@code EXPORT_TABLES} is raw JDBC and not per-table repositories:
     * pulling in {@code core.knowledge}, {@code core.imagegen}, {@code core.sarvam}, and {@code
     * core.elevenlabs} as compile dependencies just for this one cleanup step would be a much
     * heavier change than a "query the column, delete the object" pass calls for. {@code
     * chat-attachments/} objects are NOT listed here - see this class's erasure method javadoc for
     * why: they are never persisted to any table in the first place (Stage 4's upload endpoint
     * returns the key directly to the caller; nothing durable ever stores it), so there is no row
     * for this loop to discover them from.
     */
    private static final List<String[]> OBJECT_KEY_TABLES =
            List.of(
                    new String[] {"knowledge_document", "object_key"},
                    new String[] {"generated_image", "object_key"},
                    new String[] {"sarvam_generated_audio", "object_key"},
                    new String[] {"elevenlabs_generated_audio", "object_key"},
                    // Platform-admin "delete tenant completely" control (2026-08-07) -
                    // closes a real gap found while generalizing this class for
                    // arbitrary-tenant deletion: LiveKit egress recordings (voice calls)
                    // were never in this list, so self-service erasure already dropped
                    // the row (schema drop) but left the actual recording object
                    // orphaned in S3/MinIO forever. Same table/column shape as every
                    // other row here - no special handling needed, just a missing entry.
                    new String[] {"voice_call_recording", "object_key"});

    /**
     * Infra audit fix (2026-07-27) - before this existed, erasing a tenant dropped its Postgres
     * schema (and Keycloak users) but never touched MinIO/S3: every chat attachment, KB document,
     * avatar, generated image, and TTS/speech clip the tenant ever uploaded survived their own
     * account-deletion request forever. This enumerates every object key this method can actually
     * discover via a DB query and deletes each one from {@link FileStorageService} - see {@link
     * #OBJECT_KEY_TABLES} and the avatar loop below for exactly what that covers.
     *
     * <p><b>What this deliberately does NOT cover: chat attachments.</b> {@code
     * ChatAttachmentService.upload} (Stage 4, {@code core/agents}) never persists the object key
     * anywhere - it hands the key straight back to the caller, which resends it once on the next
     * chat turn and {@code AgentChatService} reads-and-discards it; session memory only ever stores
     * a text placeholder ("[attached: ...]"), never the key itself. There is therefore no row in
     * any schema this method - or anything else in this codebase - could query to find those keys.
     * This is a real, separate gap (chat-attachment objects are effectively orphaned in object
     * storage the moment their one chat turn completes, tenant erasure or not) that a
     * DB-query-driven cleanup like this one cannot close; it would need its own fix (e.g. a short
     * TTL/lifecycle rule on the {@code chat-attachments/} prefix, the same mechanism this fix
     * already adds for {@code demo-images/}) tracked separately.
     */
    void eraseObjectStorageForTenant(Tenant tenant, String schemaName) {
        // Avatars live in control_plane.app_user, NOT the tenant's own
        // schema (see AppUser's javadoc) - dropping the tenant schema below
        // never touches this table, so it needs its own lookup, scoped by
        // the tenant's real UUID (AppUser.tenantId), not the schema name.
        for (AppUser user : appUserRepository.findByTenantId(tenant.getId())) {
            deleteObjectBestEffort(
                    user.getAvatarUrl(), tenant, "avatar for app_user " + user.getId());
        }

        for (String[] tableAndColumn : OBJECT_KEY_TABLES) {
            eraseObjectKeysFromTable(tenant, schemaName, tableAndColumn[0], tableAndColumn[1]);
        }
    }

    void eraseObjectKeysFromTable(
            Tenant tenant, String schemaName, String table, String column) {
        String sql =
                "SELECT "
                        + quoteIdentifier(column)
                        + " FROM "
                        + quoteIdentifier(schemaName)
                        + "."
                        + quoteIdentifier(table)
                        + " WHERE "
                        + quoteIdentifier(column)
                        + " IS NOT NULL";
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                deleteObjectBestEffort(resultSet.getString(1), tenant, schemaName + "." + table);
            }
        } catch (SQLException e) {
            // A table missing entirely (older/partially-migrated schema, or
            // that module's profile never active for this tenant) must not
            // abort erasure - same "one bad part doesn't sink everything"
            // posture TenantDataExportService#dumpTable already uses for
            // this identical per-table enumeration problem.
            log.warn(
                    "Could not enumerate object-storage keys in {}.{} while erasing tenant '{}' -"
                            + " {}",
                    schemaName,
                    table,
                    tenant.getName(),
                    e.getMessage());
        }
    }

    void deleteObjectBestEffort(String objectKey, Tenant tenant, String description) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            fileStorageService.delete(objectKey);
        } catch (RuntimeException e) {
            // One failed/timed-out S3 delete must never fail the whole
            // erasure request - logged so it's discoverable (a genuinely
            // stuck object can be cleaned up manually), never rethrown.
            log.warn(
                    "Failed to delete object storage key '{}' ({}) while erasing tenant '{}' -"
                            + " continuing",
                    objectKey,
                    description,
                    tenant.getName(),
                    e);
        }
    }

    /**
     * Infra transparency feature (2026-07-27) - {@code GET /api/tenant/data-usage}'s "how many
     * objects does object storage hold for you" liveStat ({@code TenantDataUsageService} in {@code
     * core/app}, which cannot depend on this module's own {@code core.tenancy} the way that
     * controller lives here - rather, {@code core/app} already depends on {@code core.tenancy}, so
     * it calls this public method directly). Read-only twin of {@link
     * #eraseObjectStorageForTenant}: walks the exact same {@link #OBJECT_KEY_TABLES} enumeration
     * (plus {@code app_user.avatar_url}, same as that method's avatar loop) and COUNTs non-null
     * keys instead of deleting them, so "how many objects would show up here" and "how many objects
     * would erasure actually delete" are computed from the identical table/column list and can
     * never silently drift apart.
     */
    long countObjectStorageItems(Tenant tenant) {
        String schemaName = tenant.getSchemaName();
        long total =
                appUserRepository.findByTenantId(tenant.getId()).stream()
                        .map(AppUser::getAvatarUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .count();
        for (String[] tableAndColumn : OBJECT_KEY_TABLES) {
            total += countNonNullColumn(schemaName, tableAndColumn[0], tableAndColumn[1]);
        }
        return total;
    }

    long countNonNullColumn(String schemaName, String table, String column) {
        String sql =
                "SELECT COUNT(*) FROM "
                        + quoteIdentifier(schemaName)
                        + "."
                        + quoteIdentifier(table)
                        + " WHERE "
                        + quoteIdentifier(column)
                        + " IS NOT NULL";
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        } catch (SQLException e) {
            // Same "one bad part doesn't sink everything" posture as
            // eraseObjectKeysFromTable - a table missing entirely must not
            // fail this count, just under-report it slightly.
            log.warn(
                    "Could not count object-storage keys in {}.{} - {}",
                    schemaName,
                    table,
                    e.getMessage());
            return 0L;
        }
    }

    private static String quoteIdentifier(String identifier) {
        return TenantSchemaGuard.quoteIdentifier(identifier);
    }
}
