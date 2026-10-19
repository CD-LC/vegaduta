package com.agenticai.core.google;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Schema-per-tenant, so - same as TwilioCredentialRepository /
 * MetaWhatsAppCredentialRepository - there is no {@code WHERE tenant_id = ?}
 * anywhere here: the table is a physically different table per tenant schema.
 *
 * <p><b>Since V215 this is no longer a one-row table.</b> {@code
 * google_credential} holds at most one tenant-wide row ({@code subject_key IS
 * NULL} - the legacy shape every pre-V215 tenant already has) plus at most one
 * personal row per end user ({@code subject_key = <Keycloak sub>}), both
 * enforced by partial unique indexes (see the V215 migration header for why
 * they must be partial). That split is why the two legacy positional finders
 * below carry explicit {@link Query} overrides:
 *
 * <p><b>Why the legacy method names survive with changed queries.</b> The
 * pre-V215 derived queries ("oldest row, any subject") became a live defect
 * the moment a second row could exist: {@code connect()} would have silently
 * overwritten the tenant-wide admin credential with the first personal token
 * (repointing every other user's agents at one person's Gmail), and {@code
 * disconnect()} would have revoked whichever row happened to be oldest. The
 * only meaning "the first row" can safely retain post-V215 is "the tenant-wide
 * row", so that is what the {@link Query} overrides pin these names to. The
 * names themselves are kept (rather than renamed to {@code findTenantWide*})
 * because {@code GoogleOAuthServiceTest} - owned by the test workstream, not
 * this one - stubs these exact signatures, and every one of its scenarios is a
 * single-row legacy tenant for which "the first row" and "the tenant-wide row"
 * are the same row. A rename would have broken a test suite this change has no
 * license to touch, for zero behavioral gain.
 *
 * <p>Both overridden queries return at most one row by invariant, not by
 * LIMIT: Spring Data does not apply the {@code First} keyword to an explicit
 * {@link Query}, so these lean on {@code uq_google_credential_tenant_default}
 * (plus V215's quarantine backfill for the theoretical pre-V215 race
 * duplicate) to guarantee the {@link Optional} contract. The {@code ORDER BY}
 * is kept purely so the SQL still reads like its name.
 */
public interface GoogleCredentialRepository extends JpaRepository<GoogleCredential, UUID> {

    /** The tenant-wide row regardless of status - {@code connect(code, null)}'s reconnect-or-create lookup, which must find a REVOKED row to reactivate rather than inserting a competing second one. */
    @Query("select c from GoogleCredential c where c.subjectKey is null order by c.createdAt asc")
    Optional<GoogleCredential> findFirstByOrderByCreatedAtAsc();

    /** The tenant-wide row filtered by status - the fallback leg of every effective-credential resolution, and the whole of admin disconnect's scope. */
    @Query("select c from GoogleCredential c where c.subjectKey is null and c.status = :status order by c.createdAt asc")
    Optional<GoogleCredential> findFirstByStatusOrderByCreatedAtAsc(@Param("status") GoogleCredentialStatus status);

    /**
     * One end user's row regardless of status - the personal reconnect-or-create
     * lookup, unique by {@code uq_google_credential_subject_key}. Quarantined
     * rows (V215's {@code quarantine:*} keys) are unreachable through this by
     * construction: no Keycloak {@code sub} ever carries that prefix.
     */
    Optional<GoogleCredential> findBySubjectKey(String subjectKey);

    /** One end user's row filtered by status - the personal leg of effective-credential resolution and of personal disconnect. */
    Optional<GoogleCredential> findBySubjectKeyAndStatus(String subjectKey, GoogleCredentialStatus status);
}
