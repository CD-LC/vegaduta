package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantDataErasureService;
import com.agenticai.core.tenancy.TenantNotFoundByIdException;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.demo.DemoSandboxDataPurger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Demo-sandbox reclaim (2026-10-09) - {@code /api/platform/demo-sandboxes}.
 *
 * <p>Before every {@code /try} visitor shared ONE sandbox tenant ({@code app.demo.shared-schema},
 * default {@code demo_shared}), each visitor got a throwaway tenant of their own ("Demo
 * &lt;8hex&gt;", schema {@code demo_<id>}, {@code is_demo = true}). {@code DemoSessionExpiryJob}
 * only ever suspended those, never dropped them, so each still holds a Postgres schema (and an AGE
 * graph) on disk. This lists them and erases them in bounded batches.
 *
 * <p><b>The shared sandbox is never a candidate</b>: the listing query excludes its schema, and
 * every id the operator names is re-checked by {@link
 * TenantDataErasureService#requireReclaimableDemo} (is_demo AND not the shared schema) before
 * anything runs - a request naming any other tenant is refused whole (400), nothing erased. The
 * erasure itself is the regular platform deletion path ({@link
 * PlatformTenantDeletionOrchestrationService#deleteDemoTenant}), so each sandbox gets its own
 * {@code platform_tenant_deletion_log} row written before anything is dropped; afterwards {@link
 * DemoSandboxDataPurger} removes its {@code demo_session}-keyed control-plane rows and media
 * objects.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
@Profile("security")
public class DemoSandboxReclaimService {

    private static final Logger log = LoggerFactory.getLogger(DemoSandboxReclaimService.class);

    /** Erasures per call - each one drops a schema and a graph, so a click must stay bounded. */
    static final int MAX_PER_CALL = 50;

    /** Rows returned by the listing; {@code total} still reports the full count. */
    static final int MAX_LISTED = 500;

    static final String CONFIRM_WORD = "RECLAIM";

    private final DemoSandboxReclaimJdbcRepository repository;
    private final TenantRepository tenantRepository;
    private final TenantDataErasureService erasureService;
    private final PlatformTenantDeletionOrchestrationService orchestrationService;
    private final DemoSandboxDataPurger demoDataPurger;
    private final String sharedDemoSchema;

    public DemoSandboxReclaimService(
            DemoSandboxReclaimJdbcRepository repository,
            TenantRepository tenantRepository,
            TenantDataErasureService erasureService,
            PlatformTenantDeletionOrchestrationService orchestrationService,
            DemoSandboxDataPurger demoDataPurger,
            @Value("${app.demo.shared-schema:demo_shared}") String sharedDemoSchema) {
        this.repository = repository;
        this.tenantRepository = tenantRepository;
        this.erasureService = erasureService;
        this.orchestrationService = orchestrationService;
        this.demoDataPurger = demoDataPurger;
        this.sharedDemoSchema = sharedDemoSchema;
    }

    // ----------------------------------------------------------------- shapes

    public record SharedSandbox(UUID id, String name, String schemaName) {}

    public record ReclaimableSandbox(
            UUID id,
            String name,
            String schemaName,
            String status,
            Instant createdAt,
            Instant lastSessionAt) {}

    public record SandboxListing(
            SharedSandbox shared, List<ReclaimableSandbox> reclaimable, long total) {}

    public record ReclaimFailure(UUID id, String name, String error) {}

    public record ReclaimResult(List<UUID> erased, List<ReclaimFailure> failed, long remaining) {}

    // ------------------------------------------------------------------ reads

    public SandboxListing list() {
        List<SharedSandbox> shared = repository.findShared(sharedDemoSchema);
        List<ReclaimableSandbox> rows = repository.listReclaimable(sharedDemoSchema, MAX_LISTED);
        return new SandboxListing(
                shared.isEmpty() ? null : shared.get(0), rows, countReclaimable());
    }

    long countReclaimable() {
        return repository.countReclaimable(sharedDemoSchema);
    }

    // -------------------------------------------------------------- mutation

    /**
     * @param requestedIds the sandboxes to erase; null/empty = the oldest {@link #MAX_PER_CALL}
     *     reclaimable ones
     * @throws IllegalArgumentException (400) wrong confirmation word, too many ids, an unknown id,
     *     or any id that is not a reclaimable demo sandbox - validated for the whole batch before
     *     the first erasure runs
     */
    public ReclaimResult reclaim(List<UUID> requestedIds, String confirm, String actorId) {
        if (!CONFIRM_WORD.equals(confirm)) {
            throw new IllegalArgumentException(
                    "Type " + CONFIRM_WORD + " to confirm. Nothing was deleted.");
        }
        List<Tenant> targets = resolveTargets(requestedIds);

        List<UUID> erased = new ArrayList<>();
        List<ReclaimFailure> failed = new ArrayList<>();
        for (Tenant tenant : targets) {
            try {
                orchestrationService.deleteDemoTenant(tenant.getId(), sharedDemoSchema, actorId);
            } catch (RuntimeException e) {
                log.warn(
                        "Demo reclaim: erasing sandbox tenant {} ('{}') failed",
                        tenant.getId(),
                        tenant.getName(),
                        e);
                failed.add(
                        new ReclaimFailure(
                                tenant.getId(),
                                tenant.getName(),
                                e.getMessage() == null
                                        ? e.getClass().getSimpleName()
                                        : e.getMessage()));
                continue;
            }
            List<String> leftovers = demoDataPurger.purgeForTenant(tenant.getId());
            if (!leftovers.isEmpty()) {
                log.warn(
                        "Demo reclaim: tenant {} erased, but demo rows left in {} - needs manual"
                                + " cleanup",
                        tenant.getId(),
                        leftovers);
            }
            erased.add(tenant.getId());
        }
        return new ReclaimResult(erased, failed, countReclaimable());
    }

    private List<Tenant> resolveTargets(List<UUID> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            List<UUID> oldest = repository.oldestReclaimableIds(sharedDemoSchema, MAX_PER_CALL);
            List<Tenant> tenants = new ArrayList<>();
            for (UUID id : oldest) {
                tenantRepository.findById(id).ifPresent(tenants::add);
            }
            // Same guard as the explicit path, so the listing query can never be
            // the only thing standing between this loop and a registered tenant.
            tenants.forEach(t -> erasureService.requireReclaimableDemo(t, sharedDemoSchema));
            return tenants;
        }
        LinkedHashSet<UUID> distinct = new LinkedHashSet<>(requestedIds);
        if (distinct.contains(null)) {
            throw new IllegalArgumentException("tenantIds must not contain null.");
        }
        if (distinct.size() > MAX_PER_CALL) {
            throw new IllegalArgumentException(
                    "At most " + MAX_PER_CALL + " sandboxes can be reclaimed per call.");
        }
        List<Tenant> tenants = new ArrayList<>();
        for (UUID id : distinct) {
            Tenant tenant =
                    tenantRepository
                            .findById(id)
                            .orElseThrow(() -> new TenantNotFoundByIdException(id));
            erasureService.requireReclaimableDemo(tenant, sharedDemoSchema);
            tenants.add(tenant);
        }
        return tenants;
    }
}
