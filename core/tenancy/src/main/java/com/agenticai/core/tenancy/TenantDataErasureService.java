package com.agenticai.core.tenancy;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - {@code POST
 * /api/tenant/data-deletion}'s implementation: a tenant-admin's own self-service right-to-erasure
 * request for their whole tenant.
 *
 * <p><b>Why this is its own class, not folded into {@link TenantProvisioningService}.</b> The task
 * that produced this service asked for exactly that judgment call, so it's documented here rather
 * than left implicit. {@link TenantProvisioningService} is scoped entirely to the SIGNUP flow (see
 * its own javadoc) - its {@code compensate} method deletes ONE just-created Keycloak user and drops
 * a schema that (at most) got as far as being created, as a rollback for a signup that never
 * finished. Erasure is a different lifecycle event: an admin-triggered teardown of an {@code
 * ACTIVE} tenant that may have run for months, with an arbitrary number of Keycloak users, not just
 * the original signup admin. Reusing {@code compensate} directly isn't possible (it's {@code
 * private} and single-user-shaped) and widening it to handle N users and a non-signup caller would
 * blur that class's one job. What IS reused is the exact same <b>pattern</b> that method
 * established - best-effort compensating actions (delete every Keycloak user for this tenant, then
 * drop the Postgres schema), logged and not rethrown on partial failure - generalized here to
 * "every user this tenant has ever had," not just one.
 *
 * <p><b>The {@link Tenant} row itself is kept, not deleted</b> - {@link TenantStatus#ERASED},
 * {@link Tenant#markErased} - unlike a signup rollback, which removes the row entirely because the
 * tenant never meaningfully existed. An erased tenant DID exist; keeping a minimal terminal record
 * (status + {@code erasedAt}/{@code erasedBy}, no personal data - {@code Tenant} never stored an
 * email or personal name directly, only the org's business name) preserves referential integrity
 * for anything that still references this {@code tenantId} (billing/gateway history in particular -
 * {@code Tenant.gatewaySubscriptionId} etc. are left untouched by this method, since
 * financial-record retention is typically its own legal basis, separate from a personal-data
 * erasure request) and is itself the durable evidence that erasure happened, once the tenant's own
 * schema - and whatever audit trail lived inside it - is gone. See {@link TenantStatus#ERASED}'s
 * javadoc for the fuller reasoning.
 */
@Service
@Profile("security")
public class TenantDataErasureService {

    private static final Logger log = LoggerFactory.getLogger(TenantDataErasureService.class);

    private final TenantRepository tenantRepository;
    private final IdentityUserAdminPort keycloakAdminClient;
    private final DataSource dataSource;
    private final AppUserRepository appUserRepository;
    private final TenantObjectStorageEraser objectStorageEraser;
    private final TenantGraphMemoryEraser graphMemoryEraser;
    private final TenantControlPlanePurger controlPlanePurger;
    private final RedisTenantDataPurgeService redisTenantDataPurgeService;
    private final PlatformTenantDeletionAuditService deletionAuditService;

    public TenantDataErasureService(
            TenantRepository tenantRepository,
            IdentityUserAdminPort keycloakAdminClient,
            DataSource dataSource,
            AppUserRepository appUserRepository,
            FileStorageService fileStorageService,
            RedisTenantDataPurgeService redisTenantDataPurgeService,
            PlatformTenantDeletionAuditService deletionAuditService) {
        this.tenantRepository = tenantRepository;
        this.keycloakAdminClient = keycloakAdminClient;
        this.dataSource = dataSource;
        this.appUserRepository = appUserRepository;
        this.objectStorageEraser =
                new TenantObjectStorageEraser(dataSource, appUserRepository, fileStorageService);
        this.graphMemoryEraser = new TenantGraphMemoryEraser(tenantRepository, dataSource);
        this.controlPlanePurger = new TenantControlPlanePurger(dataSource);
        this.redisTenantDataPurgeService = redisTenantDataPurgeService;
        this.deletionAuditService = deletionAuditService;
    }

    /**
     * Erases whichever tenant {@link TenantContext} names.
     *
     * <p>{@code confirmOrgName} must match {@link Tenant#getName()} exactly (case-sensitive, no
     * trimming leniency beyond a plain equality check) - see {@code TenantDataDeletionRequest}'s
     * javadoc for why this endpoint adds that one extra deliberate step. A mismatch throws {@link
     * IllegalArgumentException} (mapped to a 400 by {@code GlobalExceptionHandler}'s existing
     * generic handler - no new exception type needed for this) and erases nothing.
     *
     * <p>Deliberately NOT wrapped in {@code @Transactional} as one unit - {@code DROP SCHEMA} and
     * the Keycloak admin REST calls are two different systems that no single database transaction
     * can span anyway (same reason {@link TenantProvisioningService#compensate} is plain,
     * uncommitted-transaction JDBC too). Only the final {@link Tenant#markErased} + save is
     * transactional, since that part - and only that part - genuinely is a single-database write.
     *
     * <p>Runs the same audit-before-delete + Valkey purge + erasure sequence as the platform-admin
     * path ({@link #eraseWithAudit}): the tenant's own audit log is dropped with its schema, so the
     * control-plane {@link PlatformTenantDeletionLog} row is the only durable trace that the
     * workspace erased itself, and the tenant's Valkey state (usage counters, rate buckets, dedup
     * markers) must not outlive the data it describes.
     */
    public void eraseCurrentTenant(String confirmOrgName) {
        Tenant tenant = requireCurrentTenant();

        if (!tenant.getName().equals(confirmOrgName)) {
            throw new IllegalArgumentException(
                    "Confirmation text does not match this workspace's name. Nothing was deleted.");
        }

        log.warn(
                "Erasing tenant '{}' (schema '{}') - right-to-erasure request via"
                        + " /api/tenant/data-deletion",
                tenant.getName(),
                tenant.getSchemaName());
        eraseWithAudit(tenant, actorId(), "self-service: ");
    }

    /**
     * Store-compliance account deletion ({@code DELETE /api/account}, the app-store compliance
     * requirements (described inline)) for a caller who is the ONLY member of their workspace -
     * same destructive effect as {@link #eraseCurrentTenant}, i.e. the same as {@code POST
     * /api/tenant/data-deletion}.
     *
     * <p><b>Deliberately skips two gates that {@link #eraseCurrentTenant} has:</b> the {@code
     * confirmOrgName} match and SecurityConfig's {@code PERM_MANAGE_DATA_GOVERNANCE} rule. Those
     * exist to stop one member of a shared organisation from wiping everybody else's data. Here the
     * caller is deleting their OWN single-person workspace (the caller, {@code
     * AccountSelfDeletionService}, has verified via Keycloak that no other user carries this
     * tenant_id) and has already typed the {@code "DELETE"} confirmation required by the contract.
     * App stores require that deletion is reachable in-app by the account holder themselves,
     * without any extra permission.
     *
     * <p>Same audit-before-delete gate and Valkey purge as {@link #eraseCurrentTenant} and {@link
     * #eraseTenantById}, through the one shared {@link #eraseWithAudit} sequence.
     */
    public void eraseCurrentTenantForSoleMemberAccountDeletion() {
        Tenant tenant = requireCurrentTenant();
        // The shared demo sandbox (/try) is one tenant serving every anonymous visitor: a
        // "sole member" of it (e.g. a platform operator's user) must never be able to drop it.
        if (tenantRepository.isDemoSchema(tenant.getSchemaName()).orElse(false)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "The shared demo workspace cannot be deleted through account deletion.");
        }
        log.warn(
                "Erasing tenant '{}' (schema '{}') - sole-member self-service account deletion via"
                        + " DELETE /api/account",
                tenant.getName(),
                tenant.getSchemaName());
        eraseWithAudit(tenant, actorId(), "account self-deletion: ");
    }

    /**
     * Platform-admin "delete tenant completely" control - the admin-facing, arbitrary-target
     * sibling of {@link #eraseCurrentTenant}. Everything that method does (Keycloak users, S3/MinIO
     * object keys, Postgres schema + AGE graph-memory schema drop, {@link Tenant#markErased}) PLUS:
     * a durable control-plane audit row written BEFORE anything is touched (aborts if that write
     * itself fails - the literal "audits before deleting" requirement), and a best-effort Redis
     * purge of every rate-limit/quota/session key this tenant or its users ever produced (see
     * {@link RedisTenantDataPurgeService}).
     *
     * <p>Code-intel (kept in the cross-service orchestration layer for one consistent "everything
     * this tenant touched outside core" story) is deliberately NOT called from here. The caller
     * (the platform-admin orchestrator in {@code core/platform}) is expected to call code-intel
     * itself with {@link TenantErasureResult#schemaName} AFTER this method returns successfully.
     *
     * @throws TenantNotFoundByIdException no tenant with this id
     * @throws TenantAlreadyErasedException the tenant's status is already {@link
     *     TenantStatus#ERASED}
     * @throws IllegalArgumentException {@code confirmTenantName} does not match the tenant's real
     *     name
     */
    public TenantErasureResult eraseTenantById(
            UUID tenantId, String confirmTenantName, String actorId) {
        Tenant tenant =
                tenantRepository
                        .findById(tenantId)
                        .orElseThrow(() -> new TenantNotFoundByIdException(tenantId));
        if (tenant.getStatus() == TenantStatus.ERASED) {
            throw new TenantAlreadyErasedException(tenantId);
        }
        if (!tenant.getName().equals(confirmTenantName)) {
            throw new IllegalArgumentException(
                    "Confirmation text does not match this tenant's name. Nothing was deleted.");
        }

        String schemaName = tenant.getSchemaName();
        // SEC-001: refuse a protected / foreign-graph schema before auditing,
        // purging or reading anything (performErasure re-checks too).
        requireErasableTenantSchema(tenant, schemaName);
        log.warn(
                "Erasing tenant '{}' (schema '{}') - platform-admin request via DELETE"
                        + " /api/platform/tenants/{}",
                tenant.getName(),
                schemaName,
                tenantId);
        return eraseWithAudit(tenant, actorId, "");
    }

    /**
     * Demo-sandbox reclaim (2026-10-09) - {@link #eraseTenantById} for a legacy per-visitor {@code
     * /try} sandbox, WITHOUT the typed-name confirmation (the operator reclaims sandboxes in bulk;
     * there is no name a human would type). That bypass is safe only because of the two checks in
     * {@link #requireReclaimableDemo}, which this method enforces itself rather than trusting its
     * caller:
     *
     * <ul>
     *   <li>the row must be {@code is_demo = true} (V126 - unmapped on {@link Tenant}, read
     *       natively via {@link TenantRepository#isDemoSchema}); no registered tenant can ever
     *       reach this path;
     *   <li>its schema must NOT be the shared demo schema that backs every live {@code /try}
     *       visitor ({@code app.demo.shared-schema}) - that tenant must never be erased.
     * </ul>
     *
     * Everything else - audit-before-delete row, Redis purge, Keycloak/object storage/schema/graph/
     * control-plane cleanup, {@link Tenant#markErased} - is the identical {@link #eraseTenantById}
     * sequence, including the SEC-001 protected-schema refusal.
     *
     * @throws IllegalArgumentException not a demo tenant, or the shared demo tenant
     */
    public TenantErasureResult eraseDemoTenantById(
            UUID tenantId, String sharedDemoSchema, String actorId) {
        Tenant tenant =
                tenantRepository
                        .findById(tenantId)
                        .orElseThrow(() -> new TenantNotFoundByIdException(tenantId));
        if (tenant.getStatus() == TenantStatus.ERASED) {
            throw new TenantAlreadyErasedException(tenantId);
        }
        requireReclaimableDemo(tenant, sharedDemoSchema);
        requireErasableTenantSchema(tenant, tenant.getSchemaName());
        log.warn(
                "Erasing demo sandbox tenant '{}' (schema '{}') - platform-admin demo reclaim",
                tenant.getName(),
                tenant.getSchemaName());
        return eraseWithAudit(tenant, actorId, "demo reclaim: ");
    }

    /**
     * The two guards {@link #eraseDemoTenantById} relies on - public so the reclaim caller can
     * pre-validate a whole batch. Fails closed: a blank {@code sharedDemoSchema} (not configured)
     * refuses everything rather than leaving the shared sandbox unprotected.
     */
    public void requireReclaimableDemo(Tenant tenant, String sharedDemoSchema) {
        String schemaName = tenant.getSchemaName();
        if (sharedDemoSchema == null || sharedDemoSchema.isBlank()) {
            throw new IllegalArgumentException(
                    "The shared demo schema is not configured; refusing to reclaim tenant "
                            + tenant.getId()
                            + ".");
        }
        if (schemaName == null || schemaName.isBlank() || schemaName.equals(sharedDemoSchema)) {
            throw new IllegalArgumentException(
                    "Tenant "
                            + tenant.getId()
                            + " is the shared demo sandbox and can never be reclaimed.");
        }
        if (!tenantRepository.isDemoSchema(schemaName).orElse(false)) {
            throw new IllegalArgumentException(
                    "Tenant "
                            + tenant.getId()
                            + " is not a demo sandbox - use the tenant deletion control instead.");
        }
    }

    private TenantErasureResult eraseWithAudit(Tenant tenant, String actorId, String auditPrefix) {
        UUID tenantId = tenant.getId();
        String schemaName = tenant.getSchemaName();
        // SEC-001: refuse a protected / foreign-graph schema before auditing, purging or reading
        // anything, whichever entry point got here.
        requireErasableTenantSchema(tenant, schemaName);

        // The audit-before-delete gate: this write MUST succeed before any
        // deletion step below runs. A failure here propagates straight out
        // (no try/catch) - the whole point is that an admin cannot delete a
        // tenant's data without first, successfully, recording that they
        // tried.
        PlatformTenantDeletionLog auditRow =
                deletionAuditService.recordInitiated(tenantId, tenant.getName(), actorId);

        // Collect every id/key that some OTHER system's data lives behind,
        // while the rows that name them still exist - all of this is gone
        // the moment the schema drops inside performErasure below.
        List<String> mcpServerGrantIds =
                collectStringColumn(schemaName, "mcp_server_access_grant", "id");
        List<String> workflowRunIds = collectStringColumn(schemaName, "workflow_run", "id");

        try {
            redisTenantDataPurgeService.purgeForTenant(
                    schemaName, mcpServerGrantIds, workflowRunIds);
        } catch (RuntimeException redisFailure) {
            // RedisTenantDataPurgeService already catches per-key-family
            // internally - this is a second, outer safety net so a genuinely
            // unexpected failure there still can't derail the rest of the
            // deletion (none of this state is durable data, see that
            // class's own javadoc).
            log.warn(
                    "Redis purge failed while erasing tenant '{}' - continuing",
                    tenant.getName(),
                    redisFailure);
        }

        ErasureOutcome outcome;
        try {
            outcome = performErasure(tenant, actorId);
        } catch (RuntimeException erasureFailure) {
            deletionAuditService.recordFailed(
                    auditRow.getId(),
                    erasureFailure.getMessage(),
                    auditPrefix + "redis-purge, keycloak-users, object-storage");
            throw erasureFailure;
        }

        // The graph-memory drop is best-effort (see dropGraphMemorySchema) -
        // so this row must not keep claiming "graph-memory-schema" completed
        // when it didn't. The Shyama incident (2026-08-17) is exactly the case
        // where somebody later reading this audit row needs to know a `_kg`
        // schema was left behind and needs manual cleanup.
        deletionAuditService.recordCompleted(
                auditRow.getId(),
                auditPrefix
                        + (outcome.graphMemoryDropped()
                                ? "keycloak-users, object-storage, redis, postgres-schema,"
                                        + " graph-memory-schema"
                                : "keycloak-users, object-storage, redis, postgres-schema"
                                        + " (graph-memory-schema FAILED - needs manual cleanup)")
                        + outcome.controlPlaneSummary());
        return new TenantErasureResult(schemaName);
    }

    /**
     * The actual destructive sequence shared by {@link #eraseCurrentTenant} and {@link
     * #eraseTenantById} - Keycloak users, S3/MinIO object keys, Postgres schema drop (+ its AGE
     * graph-memory schema), {@link Tenant#markErased}. Extracted so the admin path above can wrap
     * it with the audit-before-delete gate and Redis purge without duplicating this logic, and so
     * this method's own exception behavior (a TENANT-schema drop failure is rethrown, never
     * swallowed) stays identical for both callers.
     *
     * @return whether the tenant's AGE graph-memory schema was dropped too, plus which best-effort
     *     control-plane cleanup steps failed. Neither is a failure of the erasure - the tenant's
     *     own schema, its app_user rows and the {@code Tenant} row are already done by then - only
     *     a signal to the admin caller that the audit row should say something was left behind. See
     *     {@link TenantGraphMemoryEraser#dropGraphMemorySchema} for why that step is best-effort.
     */
    private ErasureOutcome performErasure(Tenant tenant, String actorId) {
        String schemaName = tenant.getSchemaName();
        // SEC-001: a tenant row whose schema_name is a platform/AGE schema, or
        // ANOTHER tenant's graph schema (a legacy row planted by the
        // org-name-as-schema attack), must never reach DROP SCHEMA CASCADE.
        // Checked before anything - Keycloak users included - is touched.
        requireErasableTenantSchema(tenant, schemaName);

        // Snapshot every Keycloak user for this tenant BEFORE dropping
        // anything - listUsersByTenant filters on the tenant_id ATTRIBUTE,
        // which TenantProvisioningService#signup sets to the schema name
        // (not the tenant's UUID - see that class's createUserForSignup
        // call), so schemaName is the correct argument here.
        for (KeycloakUserRepresentation user : keycloakAdminClient.listUsersByTenant(schemaName)) {
            try {
                keycloakAdminClient.deleteUser(user.id());
            } catch (RuntimeException deleteFailure) {
                // Best-effort, same posture as TenantProvisioningService#compensate:
                // one orphaned Keycloak user must never block erasing the
                // rest of the tenant, and rethrowing here would leave the
                // schema (which likely contains far more sensitive data
                // than one stray Keycloak account) undropped.
                log.error(
                        "Erasure: failed to delete Keycloak user {} for tenant '{}'",
                        user.id(),
                        tenant.getName(),
                        deleteFailure);
            }
        }

        // Infra audit fix (2026-07-27) - MUST run before the schema drop
        // below: this reads object keys OUT of the tenant's own tables
        // (knowledge documents, generated images/audio) plus this tenant's
        // control-plane app_user rows (avatars), so the rows have to still
        // exist. Every deletion here is individually best-effort (logged and
        // skipped, never rethrown) - a single stuck/timed-out S3 call must
        // never abort the whole erasure request, same posture the Keycloak-
        // user loop above already established.
        objectStorageEraser.eraseObjectStorageForTenant(tenant, schemaName);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schemaName) + " CASCADE");
        } catch (SQLException dropFailure) {
            // Unlike a Keycloak-user cleanup failure above, a schema that
            // failed to drop means real tenant data may still exist -
            // rethrown (not logged-and-swallowed) so the caller gets a 500
            // and Tenant.markErased below never runs, rather than this
            // service reporting "erased" when it demonstrably wasn't. Note
            // this block now covers ONLY the tenant's own schema, so the
            // message below can no longer name the wrong schema - see the
            // graph-memory drop's own error handling for why that mattered.
            throw new IllegalStateException(
                    "Failed to drop schema '"
                            + schemaName
                            + "' while erasing tenant '"
                            + tenant.getName()
                            + "'",
                    dropFailure);
        }

        boolean graphMemoryDropped = graphMemoryEraser.dropGraphMemorySchema(tenant, schemaName);

        // Erasure completeness (2026-10-09): the control-plane rows that name
        // this tenant. app_user (emails, phones, avatar keys) MUST go - a
        // failure here propagates, so the tenant is not reported erased while
        // its people's details survive; the avatar objects were already
        // deleted above, which is why this runs after object storage. The
        // per-table steps are best-effort and only reported in the audit row.
        int appUsersDeleted = appUserRepository.deleteAllByTenantId(tenant.getId());
        List<String> controlPlaneFailures =
                controlPlanePurger.purgeControlPlaneRows(tenant, schemaName);
        log.info(
                "Erasure: removed {} app_user row(s) for tenant '{}'; control-plane cleanup"
                        + " failures: {}",
                appUsersDeleted,
                tenant.getName(),
                controlPlaneFailures.isEmpty() ? "none" : controlPlaneFailures);

        // Deliberately NOT an internal call to a separate @Transactional
        // method - a `this.foo()` self-invocation bypasses Spring's
        // proxy-based AOP entirely, so an @Transactional annotation on a
        // method called this way would be silently ignored (a well-known
        // Spring pitfall, not a hypothetical one). JpaRepository#save is
        // already transactional on its own for this single-row write, so
        // no explicit @Transactional is needed here at all.
        tenant.markErased(actorId);
        tenant.scrubPersonalData();
        tenantRepository.save(tenant);
        return new ErasureOutcome(graphMemoryDropped, controlPlaneFailures);
    }

    /**
     * What {@link #performErasure} reports back for the audit row. {@code controlPlaneFailures}
     * names each best-effort control-plane step that failed (already logged) - an empty list means
     * all of them ran.
     */
    private record ErasureOutcome(boolean graphMemoryDropped, List<String> controlPlaneFailures) {
        String controlPlaneSummary() {
            return controlPlaneFailures.isEmpty()
                    ? ", app-users, control-plane-rows"
                    : ", app-users, control-plane-rows (FAILED: "
                            + String.join(", ", controlPlaneFailures)
                            + " - needs manual cleanup)";
        }
    }

    /**
     * SEC-001 guard for the tenant-schema DROP: refuses protected schemas and any {@code X_kg}
     * whose {@code X} is another tenant's schema (i.e. a foreign knowledge graph). Throws, so
     * {@code markErased} never runs and the admin sees a failure instead of a silent partial
     * erasure.
     */
    private void requireErasableTenantSchema(Tenant tenant, String schemaName) {
        if (TenantSchemaGuard.isProtectedSchema(schemaName)) {
            throw new IllegalStateException(
                    "Refusing to erase tenant '"
                            + tenant.getName()
                            + "': its schema '"
                            + schemaName
                            + "' is a protected platform/system schema");
        }
        if (TenantSchemaGuard.looksLikeGraphSchema(schemaName)) {
            String base =
                    schemaName.substring(
                            0,
                            schemaName.length() - TenantSchemaGuard.GRAPH_SCHEMA_SUFFIX.length());
            Optional<Tenant> graphOwner = tenantRepository.findBySchemaName(base);
            if (graphOwner.isPresent()
                    && !java.util.Objects.equals(graphOwner.get().getId(), tenant.getId())) {
                throw new IllegalStateException(
                        "Refusing to erase tenant '"
                                + tenant.getName()
                                + "': its schema '"
                                + schemaName
                                + "' is tenant '"
                                + graphOwner.get().getName()
                                + "''s knowledge-graph schema");
            }
        }
    }

    /**
     * Raw-JDBC collection of one string-typed column's values from a schema-scoped table, for an
     * ARBITRARY target schema - the same reason {@code eraseObjectKeysFromTable} already has to be
     * raw JDBC rather than a JPA repository call: Hibernate's multi-tenant schema resolution routes
     * every repository query through {@link TenantContext}, which names the CURRENT request's own
     * tenant, not whichever tenant {@link #eraseTenantById} was asked to target. Best-effort - a
     * missing/older table (module never active for this tenant) returns an empty list rather than
     * failing the whole deletion, same posture {@code eraseObjectKeysFromTable} already established
     * for this identical per-table enumeration problem.
     */
    private List<String> collectStringColumn(String schemaName, String table, String column) {
        List<String> values = new ArrayList<>();
        String sql =
                "SELECT "
                        + quoteIdentifier(column)
                        + "::text FROM "
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
                values.add(resultSet.getString(1));
            }
        } catch (SQLException e) {
            log.warn(
                    "Could not enumerate {} from {}.{} while erasing tenant (schema '{}') - {}",
                    column,
                    schemaName,
                    table,
                    schemaName,
                    e.getMessage());
        }
        return values;
    }

    /**
     * Infra transparency feature (2026-07-27) - {@code GET /api/tenant/data-usage}'s "how many
     * objects does object storage hold for you" liveStat. Read-only twin of the object-storage
     * erasure pass (see {@link TenantObjectStorageEraser}): the same table/column list, so the
     * count and what erasure would delete can never drift apart.
     */
    public long countObjectStorageItemsForCurrentTenant() {
        return objectStorageEraser.countObjectStorageItems(requireCurrentTenant());
    }

    private Tenant requireCurrentTenant() {
        String schemaName =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No TenantContext for an authenticated"
                                                        + " /api/tenant/data-deletion request -"
                                                        + " SecurityConfig should have blocked this"));
        return tenantRepository
                .findBySchemaName(schemaName)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No Tenant row for schema '" + schemaName + "'"));
    }

    private String actorId() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    /**
     * Schema/table/column names only, never a value - validated and quoted by {@link
     * TenantSchemaGuard#quoteIdentifier} (the one choke point), so nothing but a plain lowercase
     * identifier ever reaches SQL text.
     */
    private static String quoteIdentifier(String identifier) {
        return TenantSchemaGuard.quoteIdentifier(identifier);
    }
}
