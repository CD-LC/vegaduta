package com.agenticai.core.tenancy;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.SignupRequest;
import com.agenticai.core.tenancy.dto.SignupResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Phase 0 Stage 5 - {@code POST /api/signup}'s orchestration: a new business/individual gets a
 * fully provisioned, isolated tenant with zero manual steps, automating exactly what the Stage 4
 * verification walkthrough (README) had a human do by hand for {@code tenant_b}.
 *
 * <p><b>Deviation from the original build-plan text, stated explicitly:</b> the Phase 0 Build
 * Plan's Stage 5 section (written before Stage 1's "Open design question" was resolved) describes
 * provisioning "the tenant's realm/ client/roles" - i.e., one Keycloak realm per tenant. That
 * question was resolved during Stage 4's follow-up work toward a single shared {@code agentic-ai}
 * realm instead (see {@code Tenant.java}'s javadoc and migration {@code V8}). This service follows
 * the resolved architecture, not the stale plan text: it creates one new user in the existing
 * shared realm, with a {@code tenant_id} attribute and the {@code tenant-admin} role, not a new
 * realm.
 *
 * <p><b>Ordering and compensation:</b> Postgres row (status {@code PROVISIONING}) &rarr; Keycloak
 * user + role &rarr; Postgres schema + Flyway migration &rarr; Postgres row flips to {@code ACTIVE}
 * + first {@link AppUser} row created, all in one final transaction (see {@link
 * TenantProvisioningRecorder}). Any failure after the Keycloak user is created triggers best-effort
 * compensation (delete the Keycloak user, drop the schema if it got as far as being created) and
 * marks the tenant row {@link TenantStatus#PROVISIONING_FAILED} - never left silently as {@code
 * PROVISIONING} or, worse, {@code ACTIVE} with missing pieces.
 *
 * <p>Gated behind the {@code security} profile, same as {@link IdentityUserAdminPort} itself -
 * self-service signup is meaningless without Keycloak-backed auth in the first place.
 */
@Service
@Profile("security")
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private static final String TENANT_ADMIN_ROLE = "tenant-admin";

    // SEC-001: bounded retries for the (astronomically unlikely, 130-bit)
    // case a freshly generated schema name is already taken.
    private static final int MAX_SCHEMA_NAME_ATTEMPTS = 5;

    private final TenantRepository tenantRepository;
    private final TenantProvisioningRecorder recorder;
    private final IdentityUserAdminPort keycloakAdminClient;
    private final DataSource dataSource;
    private final TenantSchemaMigrator schemaMigrator;
    private final DisposableEmailDomainService disposableEmailDomains;
    private final String keycloakRealm;

    public TenantProvisioningService(
            TenantRepository tenantRepository,
            TenantProvisioningRecorder recorder,
            IdentityUserAdminPort keycloakAdminClient,
            DataSource dataSource,
            TenantSchemaMigrator schemaMigrator,
            DisposableEmailDomainService disposableEmailDomains,
            @Value("${app.keycloak.realm}") String keycloakRealm) {
        this.tenantRepository = tenantRepository;
        this.recorder = recorder;
        this.keycloakAdminClient = keycloakAdminClient;
        this.dataSource = dataSource;
        this.schemaMigrator = schemaMigrator;
        this.disposableEmailDomains = disposableEmailDomains;
        this.keycloakRealm = keycloakRealm;
    }

    public SignupResponse signup(SignupRequest request) {
        String orgName = request.orgName().trim();

        // Cheap, no-side-effect checks first - a duplicate here is a clean
        // 409 with nothing to compensate, not a partial provisioning attempt.
        // Disposable-email blocklist (E1, PRICING-MONETIZATION-AUDIT-2026-07-31.md
        // §7 item 1) runs first of all: purely in-memory (no DB/Keycloak round
        // trip), and a throwaway address should never even reach the duplicate
        // checks. 400 via DisposableEmailException (-> GlobalExceptionHandler's
        // IllegalArgumentException handler). Deliberately NOT applied to
        // onboardExistingUser below - that email came from a federated IdP
        // (Google etc.), not free-text signup input.
        if (disposableEmailDomains.isDisposable(request.adminEmail())) {
            throw new DisposableEmailException(
                    "Disposable email addresses are not accepted - please sign up with a permanent"
                            + " email address.");
        }
        // B4: a retry after a failed attempt must not be told "already registered" by that
        // attempt's leftovers.
        reclaimFailedSignupAttempt(orgName, request.adminEmail());
        if (tenantRepository.existsByName(orgName)) {
            throw new DuplicateTenantException(
                    "An organization named '" + orgName + "' is already registered.");
        }
        if (keycloakAdminClient.findByEmail(request.adminEmail()).isPresent()) {
            throw new DuplicateTenantException(
                    "An account with email '" + request.adminEmail() + "' already exists.");
        }

        // SEC-001: server-generated, never derived from the org name.
        String schemaName = allocateNewSchemaName();
        // #970 - Subscription Plan & GTM Strategy doc ("Free tier as growth
        // loop" - every new signup starts free, no card required, and
        // upgrades in-product once it hits a plan-gated limit or feature).
        // This endpoint only ever provisions TenantPlan.FREE (there is no
        // plan selector in SignupRequest) - previously it still ran every
        // signup through a Razorpay trial-subscription/checkout step below
        // regardless, which contradicted the "no card required" signup copy
        // and left every fresh FREE tenant holding an unused Razorpay
        // subscription. Fixed here: signup no longer talks to the payment
        // gateway at all. A real gateway subscription is created later,
        // on-demand, only when a tenant self-serve upgrades off Free (see
        // TenantUpgradeService + Tenant.recordGatewaySubscription's javadoc).
        Tenant tenant =
                recorder.createProvisioningTenant(
                        orgName,
                        TenantPlan.FREE,
                        schemaName,
                        keycloakRealm,
                        request.accountTypeOrDefault(),
                        request.experienceModeOrDefault(),
                        request.newsOptInOrDefault());

        String keycloakUserId = null;
        boolean schemaCreatedByUs = false;
        try {
            // #958 - orgName was being captured by the signup form (see
            // DetailsForm's "org" field) and correctly persisted into the
            // Tenant row (recorder.createProvisioningTenant above, backing
            // Tenant.name/TenantSettingsResponse.organizationName), but this
            // Keycloak-side attribute map only ever carried tenant_id - the
            // organization name itself was silently dropped from the
            // identity provider. That's a real gap for anything that reads
            // org context straight off the Keycloak token/user record
            // (rather than round-tripping through this app's own tenant
            // table), and it left Keycloak's own admin console/user export
            // with no human-readable org label next to a user, only the
            // opaque schema-name tenant_id. Fixed by adding organization_name
            // alongside tenant_id here - same wire format (a plain
            // Map<String,String>, fanned out to Keycloak's multi-valued
            // attribute shape by createUserForSignup below), and covered by
            // the same #1013 patchUserFields follow-up PUT that already
            // makes attribute persistence at creation time reliable, so no
            // separate reliability fix is needed for this one extra key.
            KeycloakUserRepresentation kcUser =
                    keycloakAdminClient.createUserForSignup(
                            request.adminEmail(),
                            request.adminEmail(),
                            request.firstName(),
                            request.lastName(),
                            request.adminPassword(),
                            Map.of("tenant_id", schemaName, "organization_name", orgName));
            keycloakUserId = kcUser.id();
            keycloakAdminClient.replaceUserRealmRoles(keycloakUserId, Set.of(TENANT_ADMIN_ROLE));

            createSchema(schemaName);
            schemaCreatedByUs = true;
            schemaMigrator.migrate(schemaName);

            // No gateway subscription for a FREE-tier signup - activate()
            // tolerates null gateway args fine (Tenant.recordGatewaySubscription
            // is a plain field assignment, no null checks), so this tenant's
            // paymentGateway/gatewaySubscriptionId/subscriptionStatus/
            // trialEndsAt columns simply stay null until (if ever) they
            // upgrade. AbandonedSignupCleanupJob's query is scoped to
            // subscriptionStatus = 'created' specifically, so a null-status
            // FREE tenant is naturally never matched by it - no change
            // needed there.
            recorder.activate(
                    tenant.getId(), keycloakUserId, request.adminEmail(), null, null, null, null);

            return new SignupResponse(tenant.getId(), orgName, schemaName, null, null, null);
        } catch (RuntimeException e) {
            log.error(
                    "Signup provisioning failed for org '{}' (schema {}) - rolling back",
                    orgName,
                    schemaName,
                    e);
            compensate(schemaName, keycloakUserId, schemaCreatedByUs);
            recorder.markFailed(tenant.getId());
            throw new TenantProvisioningException(
                    "Signup could not be completed for '"
                            + orgName
                            + "'. Nothing was left partially set up - please try again.",
                    e);
        }
    }

    /**
     * Onboarding for an ALREADY-AUTHENTICATED user who has no tenant yet - the social-login (e.g.
     * Google) path. Unlike {@link #signup}, the Keycloak user already exists (created by the IdP
     * first-login flow), so this only provisions the tenant (row + schema + first {@link AppUser})
     * and links the existing user to it (tenant_id/organization_name attributes + tenant-admin
     * role). On failure it drops the half-made schema and marks the tenant failed, but NEVER
     * deletes the Keycloak user - that is the person's real federated identity, not something this
     * flow created.
     *
     * <p><b>Guards against provisioning a SECOND tenant for a user who already has one.</b> {@link
     * OnboardingController} rejects a caller whose TOKEN carries {@code tenant_id}, which is the
     * right check but not a sufficient one: the token is a snapshot, so a user whose Keycloak
     * record is already linked can still arrive here holding a claim-less token - minted before the
     * link, or minted after a re-login that could not complete normally. Re-posting the onboarding
     * form then quietly created a whole extra tenant (row + schema) and re-pointed the Keycloak
     * user's {@code tenant_id} at it, stranding everything in the first one. So the authoritative
     * source - the Keycloak user record itself - is consulted here before anything is provisioned.
     * A genuinely new federated user has no {@code tenant_id} attribute, so the social-login happy
     * path is untouched; only the re-submit case changes, from a silent duplicate to a 409 telling
     * the person to sign in again.
     */
    public SignupResponse onboardExistingUser(
            String keycloakUserId,
            String email,
            String firstName,
            String lastName,
            String orgName,
            boolean newsOptIn) {
        String org = orgName.trim();
        // Cheap, no-side-effect checks first, same posture as signup() above -
        // a rejection here has nothing to compensate.
        String linkedTenant = existingTenantAttribute(keycloakUserId);
        if (linkedTenant != null) {
            throw new DuplicateTenantException(
                    "This account is already linked to an organization. Please sign out and sign in"
                            + " again to continue.");
        }
        if (tenantRepository.existsByName(org)) {
            throw new DuplicateTenantException(
                    "An organization named '" + org + "' is already registered.");
        }
        String schemaName = allocateNewSchemaName();
        Tenant tenant =
                recorder.createProvisioningTenant(
                        org,
                        TenantPlan.FREE,
                        schemaName,
                        keycloakRealm,
                        AccountType.B2C,
                        ExperienceMode.SIMPLE,
                        newsOptIn);
        boolean schemaCreatedByUs = false;
        try {
            keycloakAdminClient.setUserAttributes(
                    keycloakUserId, Map.of("tenant_id", schemaName, "organization_name", org));
            keycloakAdminClient.replaceUserRealmRoles(keycloakUserId, Set.of(TENANT_ADMIN_ROLE));
            createSchema(schemaName);
            schemaCreatedByUs = true;
            schemaMigrator.migrate(schemaName);
            recorder.activate(tenant.getId(), keycloakUserId, email, null, null, null, null);
            return new SignupResponse(tenant.getId(), org, schemaName, null, null, null);
        } catch (RuntimeException e) {
            log.error(
                    "Onboarding provisioning failed for org '{}' (schema {}) - rolling back"
                            + " (keeping the federated user)",
                    org,
                    schemaName,
                    e);
            compensateSchemaOnly(schemaName, schemaCreatedByUs);
            recorder.markFailed(tenant.getId());
            throw new TenantProvisioningException(
                    "Onboarding could not be completed for '" + org + "'. Please try again.", e);
        }
    }

    /**
     * The {@code tenant_id} already on the Keycloak user record, or null if there is none. Reads
     * the same attribute {@link
     * com.agenticai.core.admin.keycloak.KeycloakAdminClient#setUserAttributes} writes and the token
     * mapper turns into the {@code tenant_id} claim, so "is this user already onboarded?" is
     * answered by the one field that actually routes them to a schema, not by a second bookkeeping
     * table that could drift from it.
     */
    private String existingTenantAttribute(String keycloakUserId) {
        KeycloakUserRepresentation user = keycloakAdminClient.getUserById(keycloakUserId);
        Map<String, List<String>> attributes = user.attributes();
        if (attributes == null) {
            return null;
        }
        return attributes.getOrDefault("tenant_id", List.of()).stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * Onboarding rollback: only drops the half-made schema - never touches the federated Keycloak
     * user (cf. {@link #compensate}, which does delete it because signup created it). SEC-001: and
     * only when THIS request created that schema.
     */
    private void compensateSchemaOnly(String schemaName, boolean schemaCreatedByUs) {
        if (schemaCreatedByUs) {
            dropSchemaWeCreated(schemaName, "Onboarding rollback");
        }
    }

    /**
     * Undoes a fully-{@code activate}d tenant that never completed Razorpay's authorization step
     * within the abandoned-checkout window - called only by {@code AbandonedSignupCleanupJob}
     * (Stage 3). Package- private on purpose, same visibility posture as {@link #compensate}, since
     * only that scheduled job should ever call this.
     */
    void rollbackAbandonedCheckout(Tenant tenant, String keycloakUserId) {
        log.warn(
                "Tenant '{}' (schema {}) never completed Razorpay checkout within the"
                        + " abandoned-checkout window - rolling back",
                tenant.getName(),
                tenant.getSchemaName());
        deleteKeycloakUserQuietly(keycloakUserId);
        dropExistingTenantSchemaGuarded(tenant.getSchemaName());
        recorder.rollbackActivated(tenant.getId());
    }

    /**
     * SEC-001: every new tenant schema is a server-generated {@code t_<random>} name - never
     * derived from the org name (which stays a display name only; it previously became the schema
     * name via SchemaNameSanitizer, which let an anonymous signup pick {@code <victim>_kg} or
     * {@code ag_catalog}). Checked against both the tenant table and {@code pg_namespace}, so an
     * existing schema of ANY origin can never be picked.
     */
    private String allocateNewSchemaName() {
        for (int attempt = 0; attempt < MAX_SCHEMA_NAME_ATTEMPTS; attempt++) {
            String candidate = TenantSchemaGuard.generateNewTenantSchemaName();
            if (TenantSchemaGuard.isProtectedSchema(candidate)
                    || tenantRepository.findBySchemaName(candidate).isPresent()
                    || schemaExists(candidate)) {
                continue;
            }
            return candidate;
        }
        throw new IllegalStateException(
                "Could not allocate an unused tenant schema name after "
                        + MAX_SCHEMA_NAME_ATTEMPTS
                        + " attempts");
    }

    private boolean schemaExists(String schemaName) {
        try (Connection connection = dataSource.getConnection()) {
            return TenantSchemaGuard.schemaExists(connection, schemaName);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not check pg_namespace for schema '" + schemaName + "'", e);
        }
    }

    /**
     * Creates the (strictly-named) schema with a plain {@code CREATE SCHEMA} - deliberately no
     * {@code IF NOT EXISTS}: if the name was taken between the pre-check and here, this fails and
     * the caller's created-by-us flag stays false, so the rollback never drops a schema someone
     * else owns.
     */
    private void createSchema(String schemaName) {
        TenantSchemaGuard.requireNewTenantSchemaName(schemaName);
        try (Connection connection = dataSource.getConnection()) {
            if (TenantSchemaGuard.schemaExists(connection, schemaName)) {
                throw new IllegalStateException(
                        "Schema '" + schemaName + "' already exists - refusing to adopt it");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + quoteIdentifier(schemaName));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not create tenant schema '" + schemaName + "'", e);
        }
    }

    /**
     * Clears what an earlier FAILED signup attempt left behind for this organization name / email
     * so a retry can proceed (B4: the failure message promises "nothing was left partially set up",
     * and a retry used to be refused with a 409 by the failed attempt's own tenant row and Keycloak
     * user).
     *
     * <p>Scope is deliberately narrow: only a tenant in {@code PROVISIONING_FAILED} is discarded,
     * and a Keycloak user is deleted only when its {@code tenant_id} attribute names such a failed
     * tenant. A live account, or an attempt still in flight, is never touched. Best effort: any
     * failure here is logged and the ordinary duplicate checks that follow decide the outcome.
     */
    private void reclaimFailedSignupAttempt(String orgName, String adminEmail) {
        try {
            Optional<KeycloakUserRepresentation> leftoverUser =
                    keycloakAdminClient.findByEmail(adminEmail);
            if (leftoverUser.isPresent()) {
                List<String> tenantIds =
                        leftoverUser.get().attributes() == null
                                ? List.of()
                                : leftoverUser
                                        .get()
                                        .attributes()
                                        .getOrDefault("tenant_id", List.of());
                for (String schema : tenantIds) {
                    Optional<Tenant> failed =
                            tenantRepository
                                    .findBySchemaName(schema)
                                    .filter(t -> t.getStatus() == TenantStatus.PROVISIONING_FAILED);
                    if (failed.isPresent()) {
                        keycloakAdminClient.deleteUser(leftoverUser.get().id());
                        recorder.discardFailedTenant(failed.get().getId());
                        log.info(
                                "Signup retry: removed the leftovers of failed attempt for schema"
                                        + " {}",
                                schema);
                    }
                }
            }
            tenantRepository
                    .findByName(orgName)
                    .filter(t -> t.getStatus() == TenantStatus.PROVISIONING_FAILED)
                    .ifPresent(t -> recorder.discardFailedTenant(t.getId()));
        } catch (RuntimeException e) {
            log.warn(
                    "Signup retry: could not clear a previous failed attempt for '{}': {}",
                    orgName,
                    e.toString());
        }
    }

    /**
     * Best-effort rollback - failures here are logged, not rethrown (rethrowing would replace the
     * real provisioning-failure exception with a cleanup-failure one, hiding what actually went
     * wrong). A rare orphaned Keycloak user or Postgres schema left behind by a cleanup failure is
     * a known, accepted residual risk, not silently pretended away - an operator can find either
     * via {@code Tenant.status == PROVISIONING_FAILED} plus a manual Keycloak/psql check.
     *
     * <p>SEC-001: the schema is dropped ONLY when this request created it ({@code
     * schemaCreatedByUs}) and it has the strict new-tenant shape.
     */
    private void compensate(String schemaName, String keycloakUserId, boolean schemaCreatedByUs) {
        deleteKeycloakUserQuietly(keycloakUserId);
        if (schemaCreatedByUs) {
            dropSchemaWeCreated(schemaName, "Rollback");
        }
    }

    private void deleteKeycloakUserQuietly(String keycloakUserId) {
        if (keycloakUserId != null) {
            try {
                keycloakAdminClient.deleteUser(keycloakUserId);
            } catch (RuntimeException cleanupFailure) {
                log.error(
                        "Rollback: failed to delete orphaned Keycloak user {}",
                        keycloakUserId,
                        cleanupFailure);
            }
        }
    }

    private void dropSchemaWeCreated(String schemaName, String logPrefix) {
        if (!TenantSchemaGuard.isNewTenantSchemaName(schemaName)
                || TenantSchemaGuard.isProtectedSchema(schemaName)) {
            log.error(
                    "{}: refusing to drop schema '{}' - not a strictly-named schema created by this"
                            + " request",
                    logPrefix,
                    schemaName);
            return;
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schemaName) + " CASCADE");
        } catch (SQLException cleanupFailure) {
            log.error(
                    "{}: failed to drop orphaned schema {}", logPrefix, schemaName, cleanupFailure);
        }
    }

    /**
     * Abandoned-checkout rollback targets a schema recorded on an existing tenant row, which may be
     * a legacy (org-name-derived) name. Still never drops a protected schema ({@code public},
     * {@code control_plane}, {@code ag_catalog}, {@code pg_*}, ...) or anything shaped like an AGE
     * {@code _kg} graph schema - a legacy row poisoned by the SEC-001 attack must not become a
     * second way to reach the same DROP.
     */
    private void dropExistingTenantSchemaGuarded(String schemaName) {
        if (TenantSchemaGuard.isProtectedSchema(schemaName)
                || TenantSchemaGuard.looksLikeGraphSchema(schemaName)) {
            log.error(
                    "Rollback: refusing to drop protected/graph-shaped schema '{}' - manual cleanup"
                            + " needed",
                    schemaName);
            return;
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(schemaName) + " CASCADE");
        } catch (SQLException cleanupFailure) {
            log.error("Rollback: failed to drop orphaned schema {}", schemaName, cleanupFailure);
        }
    }

    /**
     * The one path every identifier this service interpolates into DDL takes. Each caller already
     * applies its own SEC-001 guard ({@link #createSchema}: strict new-tenant shape; {@link
     * #dropSchemaWeCreated} / {@link #dropExistingTenantSchemaGuarded}: not protected, not
     * graph-shaped); this re-checks the invariant common to all of them - a plain identifier that is
     * not a protected schema - so no future caller can reach the DDL without it.
     */
    private static String quoteIdentifier(String identifier) {
        if (TenantSchemaGuard.isProtectedSchema(identifier)) {
            throw new IllegalArgumentException(
                    "Refusing to use '" + identifier + "' as a tenant schema identifier");
        }
        return TenantSchemaGuard.quoteIdentifier(identifier);
    }
}
