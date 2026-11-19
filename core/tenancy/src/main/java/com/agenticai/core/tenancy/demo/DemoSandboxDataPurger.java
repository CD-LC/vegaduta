package com.agenticai.core.tenancy.demo;

import com.agenticai.core.storage.FileStorageService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Demo-sandbox reclaim (2026-10-09) - removes the CONTROL-PLANE footprint of one legacy per-visitor
 * {@code /try} sandbox tenant ("Demo &lt;8hex&gt;", schema {@code demo_<id>}): its {@code
 * demo_session} rows and everything keyed by those sessions, plus the generated-media objects those
 * rows point at. The tenant's own schema/graph/Keycloak/Redis cleanup is NOT here - that is {@code
 * TenantDataErasureService#eraseDemoTenantById}, which the reclaim orchestration runs first.
 *
 * <p>What is keyed how (verified against the migrations):
 *
 * <ul>
 *   <li>{@code demo_session.tenant_id} - the sandbox tenant's UUID (V126);
 *   <li>{@code demo_chat_message.session_id} (V146), {@code demo_generated_image.session_id}
 *       (V127), {@code demo_generated_video.session_id} (V142), {@code
 *       demo_generated_music.session_id} (V263) - no FK, so deleted explicitly;
 *   <li>{@code demo_channel_link.demo_session_id} / {@code demo_channel_message.demo_session_id}
 *       (V128) - no FK either;
 *   <li>{@code demo_magic_workflow} (V132) and {@code demo_studio_workflow}/ {@code
 *       demo_studio_run} (V339) - {@code ON DELETE CASCADE} from {@code demo_session}, so the final
 *       session delete removes them.
 * </ul>
 *
 * <p>Raw SQL via {@link DemoSandboxDataJdbcRepository} (not the JPA repositories) because these are set-based deletes by
 * tenant across seven tables, and because the operator's request runs with no tenant context. Every
 * step is best-effort: logged and reported, never thrown - by the time this runs the sandbox tenant
 * itself is already erased, and a leftover transcript row must not turn a successful reclaim into a
 * reported failure the operator retries against an ERASED tenant.
 */
@Component
public class DemoSandboxDataPurger {

    private static final Logger log = LoggerFactory.getLogger(DemoSandboxDataPurger.class);

    /**
     * {@code {table, session-id column}} - media tables first so their object keys are read before
     * the rows go.
     */
    private static final List<String[]> MEDIA_TABLES =
            List.of(
                    new String[] {"demo_generated_image", "session_id"},
                    new String[] {"demo_generated_video", "session_id"},
                    new String[] {"demo_generated_music", "session_id"});

    private static final List<String[]> SESSION_KEYED_TABLES =
            List.of(
                    new String[] {"demo_generated_image", "session_id"},
                    new String[] {"demo_generated_video", "session_id"},
                    new String[] {"demo_generated_music", "session_id"},
                    new String[] {"demo_chat_message", "session_id"},
                    new String[] {"demo_channel_message", "demo_session_id"},
                    new String[] {"demo_channel_link", "demo_session_id"});

    private final DemoSandboxDataJdbcRepository repository;
    private final FileStorageService fileStorageService;

    public DemoSandboxDataPurger(
            DemoSandboxDataJdbcRepository repository, FileStorageService fileStorageService) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * @return labels of the steps that failed (already logged); empty when everything ran.
     */
    public List<String> purgeForTenant(UUID tenantId) {
        List<String> failures = new ArrayList<>();

        for (String[] media : MEDIA_TABLES) {
            List<String> keys;
            try {
                keys = repository.findObjectKeys(media[0], media[1], tenantId);
            } catch (RuntimeException e) {
                failures.add(media[0] + ".object_key");
                log.warn(
                        "Demo reclaim: could not read object keys from {} for tenant {} - {}",
                        media[0],
                        tenantId,
                        e.getMessage());
                continue;
            }
            for (String key : keys) {
                try {
                    fileStorageService.delete(key);
                } catch (RuntimeException e) {
                    // One stuck object must not abort the rest; logged so it can be found.
                    log.warn(
                            "Demo reclaim: failed to delete object '{}' ({}) for tenant {} -"
                                    + " continuing",
                            key,
                            media[0],
                            tenantId,
                            e);
                }
            }
        }

        for (String[] table : SESSION_KEYED_TABLES) {
            runStep(
                    failures,
                    table[0],
                    () -> repository.deleteBySessions(table[0], table[1], tenantId),
                    tenantId);
        }
        // Cascades demo_magic_workflow, demo_studio_workflow and demo_studio_run.
        runStep(
                failures,
                "demo_session",
                () -> repository.deleteSessions(tenantId),
                tenantId);
        return failures;
    }

    private void runStep(
            List<String> failures, String label, IntSupplier step, UUID tenantId) {
        try {
            int rows = step.getAsInt();
            if (rows > 0) {
                log.info(
                        "Demo reclaim: removed {} row(s) from control_plane.{} for tenant {}",
                        rows,
                        label,
                        tenantId);
            }
        } catch (RuntimeException e) {
            failures.add(label);
            log.warn(
                    "Demo reclaim: cleanup of control_plane.{} failed for tenant {} - {}",
                    label,
                    tenantId,
                    e.getMessage());
        }
    }
}
