package com.agenticai.core.tenancy.demo;

import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * THE CONVERSION SEAM: what happens to a demo sandbox's work when its
 * visitor registers for real.
 *
 * <h2>Status of this class, stated without spin</h2>
 * The bookkeeping half is IMPLEMENTED, live, AND WIRED: {@link
 * #linkConversion} durably records demo-tenant &rarr; real-tenant on the
 * {@code control_plane.demo_session} row, which is what makes the copy
 * replayable and auditable later - and both registration paths now call it
 * (via {@link #recordRegistrationConversion}): {@code TenantSignupController}
 * after {@code TenantProvisioningService#signup}, and {@code
 * OnboardingController} after {@code onboardExistingUser} (the social-login
 * path). The artifact-copy half, {@link #copyDemoArtifacts}, is now
 * IMPLEMENTED for agents - it clones the demo tenant's agent(s) into the new
 * real tenant via the {@link DemoArtifactCopier} seam (whose implementation
 * reuses the community-template snapshot+create flow); see that method's
 * javadoc for exactly what is and is not carried over.
 *
 * <h2>How the demo token reaches signup</h2>
 * The {@code /try} page appends {@code ?demo=<token>} to its
 * "Register to keep your work" link. The signup/onboarding pages carry that
 * value and post it as the optional {@code demoToken} field on {@code
 * SignupRequest}/{@code OnboardingRequest}; each controller then calls
 * {@link #recordRegistrationConversion} AFTER its provisioning call returned
 * a real tenant. Best-effort by contract: a junk token, an already-converted
 * session, or an outright exception in here must never fail a registration
 * that has already succeeded - the account is strictly more valuable than
 * the analytics row.
 *
 * <h2>What the copy has to get right (the honest hard parts)</h2>
 * Both schemas are structurally identical (same {@code db/migration/tenant}
 * scripts), so this is a same-shape, cross-schema copy - but a naive
 * {@code INSERT INTO real.x SELECT * FROM demo.x} is wrong for four
 * reasons, and these are what the snapshot+create approach answers:
 * <ol>
 *   <li><b>Primary keys and cross-table references.</b> Ids are UUIDs, so
 *       they can be carried over verbatim ONLY if the target tables are
 *       empty. They are not: {@code TenantProvisioningService} activates a
 *       tenant that may already have seeded rows. Safe approach is a
 *       per-table old-id &rarr; new-id remap, applied in FK dependency
 *       order: {@code agent} first, then {@code agent_allowed_tool},
 *       {@code prompt_version}, {@code knowledge_collection} &rarr;
 *       {@code knowledge_document} &rarr; {@code knowledge_chunks}, and so on.</li>
 *   <li><b>What NOT to copy.</b> A demo tenant cannot have BYOK provider
 *       credentials or channel secrets (it never had a way to enter them),
 *       but the copy must still explicitly EXCLUDE those tables rather than
 *       rely on them being empty - a future demo feature that adds one
 *       would otherwise silently start migrating encrypted secrets between
 *       tenants. Also excluded: audit log, LLM call log, tool call log, RL
 *       episodes. Those are records of what the DEMO tenant did; copying
 *       them into a real tenant's audit trail would be a fabrication.</li>
 *   <li><b>Provider/model rewriting.</b> Every seeded demo agent is
 *       {@code PLATFORM_HOSTED} / {@code "platform-pool"} because the demo
 *       tenant is FREE. If the visitor registers onto a paid plan, the
 *       copied agent should be left on the platform pool (correct and
 *       working) rather than guessed onto a BYOK provider the new tenant has
 *       not configured yet.</li>
 *   <li><b>Idempotency.</b> Signup can be retried; a webhook can redeliver.
 *       {@code converted_tenant_id} being already set must make the copy a
 *       no-op, not a duplicate. {@link #linkConversion} returns false in
 *       exactly that case, and a real {@link #copyDemoArtifacts} must
 *       short-circuit on it.</li>
 * </ol>
 * Session-scoped chat history is deliberately out of scope even for the full
 * implementation: it lives in Redis under the demo session id, expires on
 * its own, and is the least valuable thing in the sandbox. The agent - its
 * name, system prompt, tuning and tool access - is what the visitor actually
 * built and what carrying over is worth doing.
 */
@Service
public class DemoConversionService {

    private static final Logger log = LoggerFactory.getLogger(DemoConversionService.class);

    private final DemoSessionRepository demoSessionRepository;
    private final DemoSessionRecorder recorder;
    private final TenantRepository tenantRepository;
    private final Optional<DemoArtifactCopier> artifactCopier;

    DemoConversionService(
        DemoSessionRepository demoSessionRepository,
        DemoSessionRecorder recorder,
        TenantRepository tenantRepository,
        Optional<DemoArtifactCopier> artifactCopier
    ) {
        this.demoSessionRepository = demoSessionRepository;
        this.recorder = recorder;
        this.tenantRepository = tenantRepository;
        this.artifactCopier = artifactCopier;
    }

    /**
     * The one call registration flows make - {@link #linkConversion} plus the
     * {@link #copyDemoArtifacts} seam, wrapped so the caller cannot get the
     * error handling wrong: NOTHING escapes this method. It runs strictly
     * AFTER the new tenant is provisioned and ACTIVE, and a conversion-
     * bookkeeping failure must never un-succeed a succeeded signup.
     *
     * <p>Ordering doubles as the idempotency guard for the copy: {@link
     * #copyDemoArtifacts} only runs when {@link #linkConversion} just recorded
     * a FRESH conversion, so a signup retry or webhook redelivery (which finds
     * {@code converted_tenant_id} already set) can never duplicate agents.
     */
    public void recordRegistrationConversion(String rawDemoToken, UUID realTenantId) {
        try {
            if (!linkConversion(rawDemoToken, realTenantId)) {
                return; // no/junk token, or already converted - nothing to do, nothing to log
            }
            // linkConversion returned true, so the session exists - re-resolved
            // here only because linkConversion's public signature (token in,
            // boolean out) predates this wrapper and is kept stable on purpose.
            demoSessionRepository.findByTokenHash(DemoTokens.hash(rawDemoToken.trim())).ifPresent(session -> {
                CopyResult copy = copyDemoArtifacts(session.getTenantId(), session.getId(), realTenantId);
                // Honest on both branches: the copy's real outcome (including
                // copied=false failures) logged next to the conversion it belongs to.
                log.info(
                    "Demo conversion {} -> {}: artifact copy result: copied={}, agents={}, detail='{}'",
                    session.getTenantId(), realTenantId, copy.copied(), copy.copiedAgents(), copy.detail()
                );
            });
        } catch (RuntimeException e) {
            log.warn(
                "Demo conversion bookkeeping failed for new tenant {} - registration itself is unaffected",
                realTenantId, e
            );
        }
    }

    /**
     * IMPLEMENTED. Durably records that {@code rawDemoToken}'s sandbox became
     * {@code realTenantId}.
     *
     * <p>Safe to call with a junk/absent token (a visitor who reached signup
     * without ever trying the sandbox) - it simply returns false. Also
     * returns false if this session was already converted, which is what
     * makes the whole flow retry-safe.
     *
     * @return true if a fresh conversion was recorded
     */
    public boolean linkConversion(String rawDemoToken, UUID realTenantId) {
        if (rawDemoToken == null || rawDemoToken.isBlank() || realTenantId == null) {
            return false;
        }
        Optional<DemoSession> found = demoSessionRepository.findByTokenHash(DemoTokens.hash(rawDemoToken.trim()));
        if (found.isEmpty()) {
            return false;
        }
        DemoSession session = found.get();
        if (session.getConvertedTenantId() != null) {
            return false; // already converted - see idempotency note in the class javadoc
        }
        recorder.recordConversion(session.getId(), realTenantId, Instant.now());
        log.info(
            "Demo sandbox {} (tenant {}) converted to real tenant {}",
            session.getId(), session.getTenantId(), realTenantId
        );
        return true;
    }

    /**
     * IMPLEMENTED (agent-only slice) - clones the demo tenant's agent(s) into
     * the real tenant through {@link DemoArtifactCopier}, whose {@code
     * core/templates} implementation reuses the community-template install
     * flow: {@code TemplateSnapshotService#snapshotAgent} (the allow-list
     * sanitiser - never credentials, channel secrets, tenant-scoped ids or
     * {@code createdBy}) followed by {@code AgentService#create} (so the
     * target plan's agent cap, FREE-tier provider coercion and validation
     * all apply). That covers the class javadoc's four hard parts for
     * agents: fresh ids come from {@code create} (1), the allow-list is the
     * exclusion list (2), a FREE-coerced provider stays on the platform pool
     * (3), and idempotency (4) is guaranteed by the only caller, {@link
     * #recordRegistrationConversion}, which reaches here strictly after
     * {@link #linkConversion} recorded a FRESH conversion - a retry/redelivery
     * finds {@code converted_tenant_id} already set and never gets this far.
     *
     * <p>Deliberately agent-only: the agent (name, system prompt, tuning,
     * provider selection) is what the visitor actually built. Chat history is
     * out of scope by design (see the class javadoc), channel bindings and
     * credentials are excluded on purpose, and knowledge documents/workflows
     * are not part of the demo sandbox's build surface today.
     *
     * <p>Best-effort like everything on this path: any failure logs and
     * returns {@code copied=false} rather than throwing - and the one caller
     * additionally wraps this in its own catch-everything.
     *
     * @param demoTenantId the (now SHARED) demo tenant whose schema holds
     *     every visitor's work
     * @param demoSessionId THIS visitor's session - required so the copy can
     *     be scoped to {@code created_by = "demo:" + demoSessionId}, never
     *     "every agent in the schema" (which since the shared-tenant change
     *     would be every other live demo visitor's agents too)
     * @param realTenantId the tenant {@code /api/signup} just provisioned
     */
    public CopyResult copyDemoArtifacts(UUID demoTenantId, UUID demoSessionId, UUID realTenantId) {
        String demoSchema = tenantRepository.findById(demoTenantId).map(Tenant::getSchemaName).orElse(null);
        String realSchema = tenantRepository.findById(realTenantId).map(Tenant::getSchemaName).orElse(null);
        if (demoSchema == null || realSchema == null) {
            log.warn(
                "copyDemoArtifacts({} -> {}): could not resolve both schemas ({} -> {}) - nothing copied",
                demoTenantId, realTenantId, demoSchema, realSchema
            );
            return new CopyResult(false, 0, "Demo or target tenant schema could not be resolved.");
        }
        if (artifactCopier.isEmpty()) {
            log.warn(
                "copyDemoArtifacts({} -> {}): no DemoArtifactCopier on the classpath - nothing copied",
                demoTenantId, realTenantId
            );
            return new CopyResult(false, 0, "Artifact copier is not available in this deployment.");
        }
        try {
            String demoActorSubject = DemoSessionService.DEMO_ACTOR_PREFIX + demoSessionId;
            DemoArtifactCopier.Outcome outcome = artifactCopier.get().copyAgents(demoSchema, realSchema, demoActorSubject);
            if (outcome.totalAgents() == 0) {
                return new CopyResult(false, 0, "The demo workspace had no agents to copy.");
            }
            boolean copied = outcome.copiedAgents() > 0;
            String detail = outcome.failedAgents() == 0
                ? "Copied " + outcome.copiedAgents() + " agent(s)."
                : "Copied " + outcome.copiedAgents() + " of " + outcome.totalAgents()
                    + " agent(s); " + outcome.failedAgents() + " failed (see logs).";
            return new CopyResult(copied, outcome.copiedAgents(), detail);
        } catch (RuntimeException e) {
            // e.g. the demo schema was already purged by DemoSessionExpiryJob -
            // the conversion itself must survive this (see the caller's contract).
            log.warn(
                "copyDemoArtifacts({} -> {}) failed - the registration is unaffected, nothing copied",
                demoTenantId, realTenantId, e
            );
            return new CopyResult(false, 0, "Artifact copy failed: " + e.getMessage());
        }
    }

    /**
     * @param copied true only when at least one agent was actually created in
     *     the real tenant - never for a no-op (empty demo workspace, missing
     *     schema/copier) or a whole-copy failure. {@code detail} says which.
     */
    public record CopyResult(boolean copied, int copiedAgents, String detail) {
    }
}
