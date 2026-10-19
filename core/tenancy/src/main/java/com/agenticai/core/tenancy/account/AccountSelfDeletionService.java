package com.agenticai.core.tenancy.account;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.AppUser;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.TenantDataErasureService;
import com.agenticai.core.tenancy.aireport.AiContentReportRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code DELETE /api/account} - the signed-in user deletes their own account (Google Play
 * account-deletion policy + Apple 5.1.1(v)). Contract: the app-store compliance requirements
 * (described inline).
 *
 * <p><b>Sole member of the workspace</b> (no OTHER Keycloak user carries this tenant_id): the whole
 * workspace is erased through {@link
 * TenantDataErasureService#eraseCurrentTenantForSoleMemberAccountDeletion()} - the same destructive
 * sequence as {@code POST /api/tenant/data-deletion}, but WITHOUT that endpoint's org-name
 * confirmation and {@code PERM_MANAGE_DATA_GOVERNANCE} gate: those protect a shared organisation
 * from one of its members, and here the caller is deleting their own single-person workspace after
 * typing "DELETE". That erasure already deletes every Keycloak user of the tenant (i.e. the
 * caller); the explicit delete afterwards is a best-effort backstop.
 *
 * <p><b>Shared workspace</b>: only what is attributable to the caller is removed - see {@link
 * #deleteOwnDataInSharedWorkspace}. Tenant-owned assets the caller created (agents, workflows,
 * knowledge, etc.) stay with the organisation, as the in-app copy and the public /account-deletion
 * page say.
 *
 * <p>Platform admins cannot self-delete through this path (403) - removing a platform operator is a
 * deliberate human action, not an app-store flow.
 */
@Service
@Profile("security")
public class AccountSelfDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountSelfDeletionService.class);

    static final String CONFIRM_TOKEN = "DELETE";

    private final IdentityUserAdminPort keycloakAdminClient;
    private final TenantDataErasureService tenantDataErasureService;
    private final AppUserRepository appUserRepository;
    private final FileStorageService fileStorageService;
    private final AiContentReportRepository aiContentReportRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    /** Max age of the JWT {@code auth_time}; zero or negative disables the check. */
    private final Duration maxAuthAge;

    /** Legacy/test constructor: recent-authentication check disabled. */
    public AccountSelfDeletionService(
            IdentityUserAdminPort keycloakAdminClient,
            TenantDataErasureService tenantDataErasureService,
            AppUserRepository appUserRepository,
            FileStorageService fileStorageService,
            AiContentReportRepository aiContentReportRepository,
            AuditLogService auditLogService,
            ApplicationEventPublisher eventPublisher) {
        this(
                keycloakAdminClient,
                tenantDataErasureService,
                appUserRepository,
                fileStorageService,
                aiContentReportRepository,
                auditLogService,
                eventPublisher,
                Duration.ZERO);
    }

    @Autowired
    public AccountSelfDeletionService(
            IdentityUserAdminPort keycloakAdminClient,
            TenantDataErasureService tenantDataErasureService,
            AppUserRepository appUserRepository,
            FileStorageService fileStorageService,
            AiContentReportRepository aiContentReportRepository,
            AuditLogService auditLogService,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.account.deletion.max-auth-age:PT15M}") Duration maxAuthAge) {
        this.maxAuthAge = maxAuthAge == null ? Duration.ZERO : maxAuthAge;
        this.keycloakAdminClient = keycloakAdminClient;
        this.tenantDataErasureService = tenantDataErasureService;
        this.appUserRepository = appUserRepository;
        this.fileStorageService = fileStorageService;
        this.aiContentReportRepository = aiContentReportRepository;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    /** Result body: {@code {"deleted": true, "tenantDeleted": true|false}}. */
    public record Result(boolean deleted, boolean tenantDeleted) {}

    public Result deleteCurrentAccount(String confirm) {
        if (!CONFIRM_TOKEN.equals(confirm)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Type DELETE to confirm account deletion. Nothing was deleted.");
        }
        TenantContext ctx =
                TenantContext.current()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED,
                                                "Sign in to delete your account."));
        String userId = ctx.getSubject();
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Sign in to delete your account.");
        }
        if (ctx.hasRole("ROLE_platform-admin")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Platform admin accounts cannot be self-deleted here. Ask another platform"
                            + " admin.");
        }
        requireRecentAuthentication();
        String tenantId = ctx.getTenantId();

        // Tenant-less account (e.g. a social sign-in that never finished
        // onboarding): nothing tenant-scoped exists, only the identity.
        if (tenantId == null || tenantId.isBlank()) {
            deleteAppUserRow(userId);
            aiContentReportRepository.anonymiseReporter(userId);
            keycloakAdminClient.deleteUser(userId);
            log.warn("Account self-deletion: tenant-less user {} deleted", userId);
            return new Result(true, false);
        }

        if (isSoleMember(tenantId, userId)) {
            // The erasure deletes every avatar object of the tenant's app_user
            // rows (it reads their keys first), every Keycloak user of the
            // tenant, and drops the schema. It leaves control_plane.app_user
            // rows in place, so the caller's row (email/phone) is removed here.
            tenantDataErasureService.eraseCurrentTenantForSoleMemberAccountDeletion();
            appUserRepository.findByKeycloakUserId(userId).ifPresent(appUserRepository::delete);
            aiContentReportRepository.anonymiseReporter(userId);
            deleteKeycloakUserBestEffort(userId);
            log.warn(
                    "Account self-deletion: sole member {} of tenant '{}' deleted with the whole"
                            + " workspace",
                    userId,
                    tenantId);
            return new Result(true, true);
        }

        deleteOwnDataInSharedWorkspace(tenantId, userId);
        return new Result(true, false);
    }

    /**
     * Destructive and irreversible, so a stolen or long-lived access token alone must not be
     * enough: the JWT {@code auth_time} (Keycloak: time of the last interactive login) has to be
     * within {@code app.account.deletion.max-auth-age}. Missing claim counts as stale. The client
     * answers {@code reauth_required} by redirecting to the IdP with {@code prompt=login&max_age=0}
     * and retrying.
     */
    private void requireRecentAuthentication() {
        if (maxAuthAge.isZero() || maxAuthAge.isNegative()) {
            return;
        }
        Instant authTime = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            try {
                authTime = jwt.getClaimAsInstant("auth_time");
            } catch (RuntimeException malformed) {
                authTime = null;
            }
        }
        if (authTime == null || authTime.isBefore(Instant.now().minus(maxAuthAge))) {
            throw new ReauthRequiredException(maxAuthAge.toSeconds());
        }
    }

    /**
     * Shared-workspace branch. Deleted:
     *
     * <ul>
     *   <li>every row a {@link AccountDeletionRequested} listener owns for this user - today {@code
     *       mobile_sentinel_event} (core/devices) and {@code study_plan} (core/agents tutor);
     *   <li>the caller's avatar object in object storage and their {@code control_plane.app_user}
     *       row (email, phone number, role);
     *   <li>the reporter link on any AI-content reports they filed (the report itself is kept as a
     *       moderation record, anonymised);
     *   <li>the Keycloak user (sign-in identity, credentials, linked social logins, sessions) -
     *       last, and NOT best-effort: if it fails the request fails and can be retried, every
     *       earlier step is idempotent.
     * </ul>
     *
     * The tenant audit log records the deletion (the tenant survives here).
     */
    private void deleteOwnDataInSharedWorkspace(String tenantId, String userId) {
        eventPublisher.publishEvent(new AccountDeletionRequested(tenantId, userId));
        deleteAppUserRow(userId);
        int anonymisedReports = aiContentReportRepository.anonymiseReporter(userId);
        keycloakAdminClient.deleteUser(userId);
        try {
            auditLogService.record(
                    userId,
                    "ACCOUNT_SELF_DELETED",
                    "USER",
                    userId,
                    AuditOutcome.SUCCESS,
                    "Self-service account deletion (DELETE /api/account) in a shared workspace:"
                            + " sentinel events, app_user row, avatar and Keycloak user deleted; "
                            + anonymisedReports
                            + " AI-content report(s) anonymised. Workspace-owned assets retained.");
        } catch (RuntimeException auditFailure) {
            // The deletion already happened; a failed audit write must not turn
            // it into an error response the client would retry against a user
            // that no longer exists.
            log.error(
                    "Account self-deletion: audit write failed for user {} in tenant '{}'",
                    userId,
                    tenantId,
                    auditFailure);
        }
        log.warn(
                "Account self-deletion: user {} removed from shared tenant '{}'", userId, tenantId);
    }

    /**
     * True when no Keycloak user OTHER than the caller carries this tenant_id. A Keycloak failure
     * propagates (500) - guessing "sole member" on an error could erase a whole shared
     * organisation.
     */
    private boolean isSoleMember(String tenantId, String userId) {
        List<KeycloakUserRepresentation> members = keycloakAdminClient.listUsersByTenant(tenantId);
        return members.stream().noneMatch(member -> !userId.equals(member.id()));
    }

    private void deleteAppUserRow(String userId) {
        Optional<AppUser> appUser = appUserRepository.findByKeycloakUserId(userId);
        if (appUser.isEmpty()) {
            return;
        }
        String avatarKey = appUser.get().getAvatarUrl();
        if (avatarKey != null && !avatarKey.isBlank()) {
            try {
                fileStorageService.delete(avatarKey);
            } catch (RuntimeException e) {
                log.warn(
                        "Account self-deletion: could not delete avatar object for user {} -"
                                + " continuing",
                        userId,
                        e);
            }
        }
        appUserRepository.delete(appUser.get());
    }

    private void deleteKeycloakUserBestEffort(String userId) {
        try {
            keycloakAdminClient.deleteUser(userId);
        } catch (RuntimeException alreadyGoneOrFailed) {
            // Normal case: the tenant erasure above already deleted every
            // Keycloak user of the tenant, so this 404s.
            log.info(
                    "Account self-deletion: explicit Keycloak delete for {} after tenant erasure"
                        + " did not succeed ({}) - expected when the erasure already removed it",
                    userId,
                    alreadyGoneOrFailed.getMessage());
        }
    }
}
