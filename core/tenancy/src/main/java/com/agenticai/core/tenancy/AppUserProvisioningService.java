package com.agenticai.core.tenancy;

import com.agenticai.core.admin.MemberAccountProvisioner;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates / repairs the {@code control_plane.app_user} row of a tenant member (B10).
 *
 * <p>Only the self-service signup path used to create that row, so a member added through
 * {@code POST /api/admin/users} had none and {@code GET /api/account} threw. This service
 * is called both from the admin create-user path (eagerly) and from {@link AccountController}
 * (lazily, to repair a member who predates the fix), so it must be idempotent and race-safe:
 * the insert runs in its own transaction and a unique-constraint loss (another request
 * inserted the same user first) is resolved by re-reading, never by failing.
 */
@Service
public class AppUserProvisioningService implements MemberAccountProvisioner {

    private static final Logger log = LoggerFactory.getLogger(AppUserProvisioningService.class);

    private final AppUserRepository appUserRepository;
    private final TenantRepository tenantRepository;
    private final TransactionTemplate requiresNew;

    public AppUserProvisioningService(
        AppUserRepository appUserRepository, TenantRepository tenantRepository,
        PlatformTransactionManager transactionManager
    ) {
        this.appUserRepository = appUserRepository;
        this.tenantRepository = tenantRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void ensureMemberAccount(String tenantSchema, String keycloakUserId, String email, boolean tenantAdmin) {
        ensure(tenantSchema, keycloakUserId, email, tenantAdmin);
    }

    /** @return the existing or newly created row for this Keycloak user. */
    public AppUser ensure(String tenantSchema, String keycloakUserId, String email, boolean tenantAdmin) {
        Optional<AppUser> existing = appUserRepository.findByKeycloakUserId(keycloakUserId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (tenantSchema == null || tenantSchema.isBlank()) {
            throw new IllegalStateException("Cannot create an app_user row without a tenant.");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalStateException(
                "Cannot create an app_user row for '" + keycloakUserId + "': no email on the account.");
        }
        UUID tenantId = tenantRepository.findBySchemaName(tenantSchema)
            .map(Tenant::getId)
            .orElseThrow(() -> new IllegalStateException("No tenant for schema '" + tenantSchema + "'."));
        UserRole role = tenantAdmin ? UserRole.TENANT_ADMIN : UserRole.DELEGATED_USER;
        try {
            return requiresNew.execute(status ->
                appUserRepository.saveAndFlush(new AppUser(tenantId, keycloakUserId, email, role)));
        } catch (DataIntegrityViolationException e) {
            // Lost a race on keycloak_user_id, or a stale row holds (tenant, email) from an
            // earlier Keycloak account with the same address: resolve by re-reading.
            Optional<AppUser> winner = appUserRepository.findByKeycloakUserId(keycloakUserId);
            if (winner.isPresent()) {
                return winner.get();
            }
            Optional<AppUser> stale = appUserRepository.findByTenantIdAndEmail(tenantId, email);
            if (stale.isPresent()) {
                log.warn("Re-linking stale app_user {} to Keycloak user {} (same tenant and email).",
                    stale.get().getId(), keycloakUserId);
                return requiresNew.execute(status -> {
                    AppUser row = appUserRepository.findById(stale.get().getId()).orElseThrow();
                    row.relinkKeycloakUser(keycloakUserId);
                    return appUserRepository.saveAndFlush(row);
                });
            }
            throw e;
        }
    }
}
