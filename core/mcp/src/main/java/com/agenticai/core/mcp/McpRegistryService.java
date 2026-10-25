package com.agenticai.core.mcp;

import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class McpRegistryService {

    private static final Logger log = LoggerFactory.getLogger(McpRegistryService.class);

    private final McpServerRegistrationRepository repository;
    // MCP-DOCTOR hardening (2026-07-25) - save-time halves of the SSRF guard
    // and STDIO launcher allowlist; DynamicMcpConnectionManager (core/agents)
    // re-checks the same guards at connect/spawn time, so a row that predates
    // the guards (or was written around this service) still can't connect.
    private final McpRemoteUrlGuard remoteUrlGuard;
    private final McpStdioCommandGuard stdioCommandGuard;
    // Env encryption/merge/decrypt and vault expansion - see McpRegistrationEnv.
    private final McpRegistrationEnv envCodec;
    // 2026-07-27 billing audit fix: this module had zero plan-awareness - see
    // McpConnectorPlanLimits and McpRegistrationPlanGate.
    private final McpRegistrationPlanGate planGate;
    // The "ready catalog entry whose shared static connection isn't servable" repair - see
    // McpStaticReferenceUpgrader's javadoc. A bean in this module: it owns the catalog spec +
    // static-availability lookup + env codec that path needs.
    private final McpStaticReferenceUpgrader staticReferenceUpgrader;

    public McpRegistryService(
            McpServerRegistrationRepository repository,
            EncryptionService encryptionService,
            McpRemoteUrlGuard remoteUrlGuard,
            McpStdioCommandGuard stdioCommandGuard,
            TenantLimits tenantLimits,
            McpCatalogService catalogService,
            SecretReferenceResolver secretReferenceResolver,
            McpStaticReferenceUpgrader staticReferenceUpgrader) {
        this.repository = repository;
        this.remoteUrlGuard = remoteUrlGuard;
        this.stdioCommandGuard = stdioCommandGuard;
        this.envCodec = new McpRegistrationEnv(encryptionService, secretReferenceResolver);
        this.planGate =
                new McpRegistrationPlanGate(repository, tenantLimits, catalogService, envCodec);
        this.staticReferenceUpgrader = staticReferenceUpgrader;
    }

    /**
     * Registers a new server or updates an existing one by name. When {@code request.command()} is
     * present, this also (re)configures its dynamic connection - see {@link
     * McpServerRegistrationRequest}'s javadoc for the static-vs-dynamic distinction. Deliberately
     * does NOT attempt the actual connection itself (that would make this leaf,
     * spring-ai-mcp-client-free module depend on the MCP client SDK) - the caller
     * (McpRegistryController) kicks off {@code DynamicMcpConnectionManager.connect(id)} afterward
     * when a dynamic config was just saved, same "this module owns the data, the agents module owns
     * the live connection" split described in {@link McpServerRegistration}'s javadoc.
     */
    @Transactional
    public McpServerRegistrationResponse register(McpServerRegistrationRequest request) {
        Optional<McpServerRegistration> existingRegistration =
                repository.findByName(request.name());
        boolean wasUpdate = existingRegistration.isPresent();
        // SEC-002: catalog trust is decided on the EXACT launch spec (command,
        // args incl. package + pinned version, env-key subset, url), never on
        // the name alone - see McpCatalogService#findStrictMatch. Computed
        // against the EFFECTIVE env this save will persist (an update merges
        // the stored env), before anything is mutated.
        boolean catalogSourced =
                planGate.isCatalogSourced(request, existingRegistration.orElse(null), wasUpdate);
        if (!wasUpdate) {
            // Only a brand-new name adds a connector beyond what this tenant
            // already has - re-registering/reconfiguring an existing one (the
            // upsert-by-name branch below) never needs to re-check the cap,
            // same "gate on create, not on update" posture as AgentService's
            // maxAgents check.
            planGate.enforceForNewConnector(request, catalogSourced);
        } else if (StringUtils.hasText(request.command()) || StringUtils.hasText(request.url())) {
            // SEC-002: an update that (re)configures the launch spec is re-checked
            // against the custom-server plan gate - otherwise a FREE tenant could
            // create the exact catalog spec and then swap in any package.
            planGate.enforceCustomServerGate(catalogSourced);
        }

        McpServerRegistration entity =
                existingRegistration
                        .map(
                                existing -> {
                                    existing.setEnabled(request.enabled());
                                    return existing;
                                })
                        .orElseGet(
                                () -> {
                                    McpServerRegistration created =
                                            new McpServerRegistration(
                                                    request.name(),
                                                    request.type(),
                                                    request.description(),
                                                    request.enabled());
                                    // MCP-STDIO-SANDBOXING review queue's "requester" column -
                                    // captured once, at creation, from the ambient request's
                                    // subject (never re-stamped on a later update/reconfigure).
                                    created.setRequestedBy(
                                            TenantContext.current()
                                                    .map(TenantContext::getSubject)
                                                    .orElse(null));
                                    return created;
                                });

        List<String> warnings = new ArrayList<>();
        if (StringUtils.hasText(request.command())) {
            // Fail-closed BEFORE anything is persisted - a rejected launcher
            // never becomes a row the connect path then has to distrust.
            // Security audit 2026-07-29 (§2 item 2): env is checked here too.
            // It is real process environment for a STDIO child, and
            // NODE_OPTIONS=--import data:text/javascript,<payload> is arbitrary
            // code execution with none of the command/args rules touched - so a
            // registration whose env carries a runtime-control variable must
            // never become a stored row either. (The remote branch below
            // deliberately does NOT get this check: there, env entries become
            // HTTP request headers, not a process environment - see
            // DynamicMcpConnectionManager#remoteHeaders.)
            stdioCommandGuard.requireAllowed(request.command(), request.args(), request.env());
            entity.configureDynamic(
                    request.command(),
                    McpRegistrationEnv.writeJson(request.args() == null ? List.of() : request.args()),
                    envCodec.resolveForUpdate(entity, request.env(), wasUpdate));
            // MCP-STDIO-SANDBOXING (2026-08-05): a curated catalog entry was
            // already vetted by the platform team - that IS the review. Every
            // other STDIO source (hand-typed, or "Browse registry") is
            // equally unvetted and starts PENDING - see McpReviewStatus's
            // javadoc for why this is a server-verified match, never a
            // client-supplied "trust me, this is from the catalog" claim.
            // SEC-002: the match is on the exact launch spec (see
            // McpCatalogService#findStrictMatch), not the name - reusing a
            // catalog name with any other package/args/env lands PENDING.
            //
            // Deliberately recomputed on EVERY save, not just at creation:
            // register() is also the upsert-by-name reconfigure path (a
            // tenant editing an already-APPROVED custom server's command),
            // and an approval was only ever for the PREVIOUS command/args/env
            // - the same "any config change invalidates the last
            // verification" posture configureDynamic already takes with
            // usageStatus just above. A no-op re-save of an unchanged custom
            // command does re-enter the queue too (this method has no way to
            // diff "identical resubmission" from "quietly swapped the
            // package" without decrypting and comparing the old env, which
            // is more machinery than a review-reset - the safe-by-default
            // direction here is "review again", not "trust it's unchanged").
            // toggling enabled on/off (PATCH, McpRegistryService#setEnabled)
            // is a SEPARATE method that never touches reviewStatus, so an
            // APPROVED server survives being switched off and back on.
            if (catalogSourced) {
                entity.markReviewNotRequired();
            } else {
                entity.markReviewPending();
            }
            warnings.addAll(planGate.missingRequiredEnvWarnings(request, catalogSourced));
        } else if (StringUtils.hasText(request.url())) {
            // Remote (registry) server - a hosted SSE / streamable-HTTP endpoint
            // with no local subprocess. env may still carry a bearer/header
            // secret the endpoint needs, so it's encrypted the same way.
            // SSRF guard first, same fail-closed posture as the stdio branch.
            remoteUrlGuard.requireAllowed(request.url());
            entity.configureRemote(
                    request.url(), envCodec.resolveForUpdate(entity, request.env(), wasUpdate));
            // Different threat model entirely (network-only, already
            // SSRF-guarded) - never subject to the STDIO review gate.
            entity.markReviewNotRequired();
        } else {
            // Name-only request (the catalog's one-click "Activate" for a `ready` server): a pure
            // reference to a statically wired connection. When this deployment can't serve that
            // shared connection it silently connected nothing before - the upgrade turns the
            // registration into a real per-tenant dynamic one the caller then connects.
            staticReferenceUpgrader.upgradeIfUnserved(entity);
        }

        if (wasUpdate) {
            // Duplicate-name warning (2026-08-05): register() has always
            // silently upserted by name with no signal that "Add" actually
            // overwrote an existing connector's command/args/env - surface it
            // so the frontend can show "you updated an existing registration"
            // instead of a plain "added" confirmation.
            warnings.add(
                    "A registration named '"
                            + request.name()
                            + "' already existed - this updated it rather than creating a new"
                            + " one.");
        }

        McpServerRegistration saved = repository.save(entity);
        return McpServerRegistrationResponse.from(saved, wasUpdate, warnings);
    }

    @Transactional(readOnly = true)
    public List<McpServerRegistrationResponse> listAll() {
        return repository.findAll().stream().map(McpServerRegistrationResponse::from).toList();
    }

    @Transactional
    public McpServerRegistrationResponse setEnabled(UUID id, boolean enabled) {
        McpServerRegistration entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerRegistrationNotFoundException(id));
        entity.setEnabled(enabled);
        if (enabled) {
            // Same dead-reference repair as register()'s name-only branch: a static-reference row
            // enabled before this fix (or before the static wiring went away) upgrades to its
            // catalog launch spec here too, so re-enabling actually connects.
            staticReferenceUpgrader.upgradeIfUnserved(entity);
        }
        if (!enabled && entity.isDynamic()) {
            // Real connection teardown is DynamicMcpConnectionManager's job
            // (it holds the live McpSyncClient) - this just records that no
            // connection should be held, so a stale CONNECTED status doesn't
            // outlive the toggle if that teardown call is ever missed.
            entity.markDisabled();
        }
        return McpServerRegistrationResponse.from(repository.save(entity));
    }

    /**
     * The tenant admin's own layer-1 edge-eligibility decision (see {@code
     * McpServerRegistration#edgeEligible}'s javadoc) - independent of {@link #setEnabled}, so an
     * admin can flip this on/off without touching the registration's hosted-use availability at
     * all.
     */
    @Transactional
    public McpServerRegistrationResponse setEdgeEligible(UUID id, boolean edgeEligible) {
        McpServerRegistration entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerRegistrationNotFoundException(id));
        entity.setEdgeEligible(edgeEligible);
        return McpServerRegistrationResponse.from(repository.save(entity));
    }

    /**
     * Removes a registration outright - previously the only way to get rid of a
     * bad/duplicate/unwanted server was disabling it forever (no delete endpoint existed at all).
     * 404s the same way every other by-id method here does if the id doesn't exist in the current
     * tenant schema. Deliberately does NOT touch the live connection itself - same division of
     * responsibility {@link #setEnabled} already uses (this module holds the row, {@code
     * DynamicMcpConnectionManager} in core/agents holds the live {@code McpSyncClient}) - the
     * caller ({@link McpRegistryController}) calls {@link McpDynamicConnector#disconnect} first,
     * same as the disable-via-toggle path.
     */
    @Transactional
    public void delete(UUID id) {
        McpServerRegistration entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerRegistrationNotFoundException(id));
        repository.delete(entity);
    }

    /**
     * Whether at least one registered MCP server is enabled - the coarse, MVP-level gate
     * AgentToolProvider checks.
     */
    public boolean anyServerEnabled() {
        return HotpathLookups.tenant(
                "mcp.any-enabled", () -> !repository.findByEnabledTrue().isEmpty());
    }

    /**
     * Every enabled, dynamically-configured (command != null) registration - what
     * DynamicMcpConnectionManager iterates to (re)connect on startup or on demand.
     */
    @Transactional(readOnly = true)
    public List<McpServerRegistration> enabledDynamicRegistrations() {
        return repository.findByEnabledTrue().stream()
                .filter(McpServerRegistration::isDynamic)
                .toList();
    }

    /**
     * Decrypts one registration's full connection config for {@code DynamicMcpConnectionManager} to
     * actually connect with. Empty if the registration isn't dynamic or doesn't exist.
     *
     * <p><b>Vault expansion happens here</b>, at the single point where a registration becomes a
     * connectable config, so that both downstream carriers get real values from one change: {@code
     * stdioParamsFor}'s env map and {@code remoteHeaders}' HTTP headers. Putting it further down
     * would mean doing it twice and risking the two drifting apart.
     *
     * <p><b>Only TENANT-scoped secrets resolve here, and that is correct.</b> An MCP connection is
     * established per tenant and pooled - it is not per-run and has no initiating end user, so
     * {@code null} is passed as the user id. {@code findResolvableByName} then matches TENANT rows
     * only (a USER row requires {@code ownerUserId = :userId}, which never holds for null). A
     * personal secret silently becoming a shared server's credential would be a privilege
     * escalation; getting nothing instead is the safe outcome, and the unresolved reference stays
     * visibly in place.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<McpDynamicConfig> dynamicConfigFor(UUID id) {
        return repository
                .findById(id)
                .filter(McpServerRegistration::isDynamic)
                .map(
                        entity -> {
                            McpRegistrationEnv.DecryptedEnv decrypted =
                                    envCodec.decryptDetailed(entity.getEncryptedEnv());
                            return new McpDynamicConfig(
                                    entity.getId(),
                                    entity.getName(),
                                    entity.getType(),
                                    entity.getCommand(),
                                    McpRegistrationEnv.readJsonList(entity.getArgs()),
                                    envCodec.expandVaultReferences(
                                            decrypted.env(), entity.getName()),
                                    entity.getUrl(),
                                    decrypted.corrupted(),
                                    entity.getReviewStatus(),
                                    entity.getReviewRejectionReason(),
                                    entity.getOauthProvider());
                        });
    }

    /**
     * Persists the decrypt-failure visibility flag - called by {@code DynamicMcpConnectionManager}
     * right after resolving a fresh {@link McpDynamicConfig}, since that is the one place a fresh
     * decrypt outcome exists. A no-op write skip when the stored value already matches, so a
     * healthy registration's routine connect attempts don't churn a row on every poll.
     */
    @Transactional
    public void setCredentialsCorrupted(UUID id, boolean corrupted) {
        repository
                .findById(id)
                .ifPresent(
                        e -> {
                            if (e.isCredentialsCorrupted() != corrupted) {
                                e.setCredentialsCorrupted(corrupted);
                                repository.save(e);
                            }
                        });
    }

    /**
     * Stamps the structural OAuth-provider marker (see {@link
     * McpServerRegistration#getOauthProvider}'s field javadoc) - called exactly once by the owning
     * OAuth service (e.g. {@code AtlassianOAuthService}) right after upserting the registration row
     * via {@link #register}, never by the normal request-driven registration path itself.
     */
    @Transactional
    public void setOAuthProvider(UUID id, String oauthProvider) {
        repository
                .findById(id)
                .ifPresent(
                        e -> {
                            e.setOauthProvider(oauthProvider);
                            repository.save(e);
                        });
    }

    /**
     * Stamps {@code marker} on every REMOTE (SSE / streamable-HTTP) registration of the current
     * tenant whose URL {@code urlMatches} and that is not already owned by ANOTHER OAuth flow
     * (Atlassian, a provider-registry marker): a registration with no marker, or already carrying
     * {@code marker}, qualifies. Used by the generic remote-MCP OAuth flow ({@code
     * McpOAuthRegistrationLinker}) right after a successful connect.
     *
     * @return the ids of the registrations now carrying {@code marker}
     */
    @Transactional
    public List<UUID> stampOAuthMarkerOnRemote(
            java.util.function.Predicate<String> urlMatches, String marker) {
        List<UUID> stamped = new java.util.ArrayList<>();
        for (McpServerRegistration e : repository.findAll()) {
            boolean remote =
                    e.getType() == McpServerType.SSE
                            || e.getType() == McpServerType.STREAMABLE_HTTP;
            String current = e.getOauthProvider();
            if (!remote || e.getUrl() == null || !urlMatches.test(e.getUrl())) {
                continue;
            }
            if (current != null && !current.isBlank() && !current.equals(marker)) {
                continue;
            }
            if (!marker.equals(current)) {
                e.setOauthProvider(marker);
                repository.save(e);
            }
            stamped.add(e.getId());
        }
        return stamped;
    }

    /**
     * Registrations carrying the given structural OAuth marker - the provider registry ({@code
     * OAuthProviderMcpLinker}) uses it to drop the live connections of a provider when a tenant
     * disconnects it.
     */
    @Transactional(readOnly = true)
    public List<McpServerRegistrationResponse> listByOAuthProvider(String oauthProvider) {
        return repository.findByOauthProvider(oauthProvider).stream()
                .map(McpServerRegistrationResponse::from)
                .toList();
    }

    /**
     * The approval-gate refusal path (MCP-STDIO-SANDBOXING) - see {@link
     * McpServerRegistration#markConnectionRefusedByReview(String)}'s javadoc for why this is
     * deliberately NOT routed through {@link #markErrorDiagnosis}.
     */
    @Transactional
    public void markConnectionBlockedByReview(UUID id, String hint) {
        repository
                .findById(id)
                .ifPresent(
                        e ->
                                repository.save(
                                        applyAndReturn(
                                                e,
                                                entity ->
                                                        entity.markConnectionRefusedByReview(
                                                                hint))));
    }

    @Transactional
    public void markConnecting(UUID id) {
        repository
                .findById(id)
                .ifPresent(
                        e ->
                                repository.save(
                                        applyAndReturn(e, McpServerRegistration::markConnecting)));
    }

    @Transactional
    public void markConnected(UUID id) {
        repository
                .findById(id)
                .ifPresent(
                        e ->
                                repository.save(
                                        applyAndReturn(e, McpServerRegistration::markConnected)));
    }

    @Transactional
    public void markError(UUID id, String message) {
        repository
                .findById(id)
                .ifPresent(
                        e ->
                                repository.save(
                                        applyAndReturn(e, entity -> entity.markError(message))));
    }

    /**
     * A bounded connection test passed - marks READY without claiming a live connection (see entity
     * javadoc).
     */
    @Transactional
    public void markTestPassed(UUID id) {
        repository
                .findById(id)
                .ifPresent(
                        e ->
                                repository.save(
                                        applyAndReturn(e, McpServerRegistration::markTestPassed)));
    }

    /**
     * Structured-diagnosis variant (MCP-DOCTOR) - persists phase + hint alongside the message and
     * fails the readiness gate.
     */
    @Transactional
    public void markErrorDiagnosis(UUID id, String message, McpConnectionPhase phase, String hint) {
        markErrorDiagnosis(id, message, phase, hint, null);
    }

    /**
     * Same as the 4-arg overload, plus persisting a self-heal suggestion (MCP-SELF-HEAL, migration
     * V135) so it survives past the single /test response that computed it - see {@code
     * McpServerRegistration}'s {@code suggestedFix*} field javadoc for why this matters for the
     * register-time failure path specifically.
     */
    @Transactional
    public void markErrorDiagnosis(
            UUID id,
            String message,
            McpConnectionPhase phase,
            String hint,
            com.agenticai.core.mcp.dto.McpConnectionTestResult.SuggestedFix suggestedFix) {
        repository
                .findById(id)
                .ifPresent(
                        e ->
                                repository.save(
                                        applyAndReturn(
                                                e,
                                                entity -> {
                                                    if (suggestedFix == null) {
                                                        entity.markError(message, phase, hint);
                                                    } else {
                                                        String argsJson =
                                                                McpRegistrationEnv.toJsonOrEmpty(
                                                                        suggestedFix.args());
                                                        entity.markError(
                                                                message,
                                                                phase,
                                                                hint,
                                                                suggestedFix.command(),
                                                                argsJson,
                                                                suggestedFix.reason());
                                                    }
                                                })));
    }

    /**
     * Readiness-gate escape hatch: admin explicitly allows agents to use a FAILED/UNTESTED
     * registration anyway. No-op if already READY.
     */
    @Transactional
    public McpServerRegistrationResponse acknowledge(UUID id) {
        McpServerRegistration entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerRegistrationNotFoundException(id));
        entity.acknowledgeUsage();
        return McpServerRegistrationResponse.from(repository.save(entity));
    }

    /**
     * The readiness gate's read side: ids of enabled dynamic registrations whose usageStatus is
     * READY or ACKNOWLEDGED - the ONLY registrations allowed to contribute tool callbacks to an
     * agent run. See {@code DynamicMcpConnectionManager#resolveDynamicToolCallbacks}.
     */
    @Transactional(readOnly = true)
    public java.util.Set<UUID> usableDynamicRegistrationIds() {
        return repository.findByEnabledTrue().stream()
                .filter(McpServerRegistration::isDynamic)
                .filter(McpServerRegistration::isUsableByAgents)
                .map(McpServerRegistration::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Edge tool-access consent system (2026-08-06) - the admin/platform tier of the two-tier gate
     * (see {@code McpServerRegistration#edgeEligible}'s javadoc). Ids of enabled, USABLE (same
     * readiness gate as {@link #usableDynamicRegistrationIds}) dynamic registrations a tenant admin
     * has ALSO explicitly marked edge-eligible. {@code DynamicMcpConnectionManager}'s edge
     * resolution path intersects its live-connection map against this set instead of the plain
     * usable set - a registration failing either gate contributes zero tools to an edge session,
     * even though it may still be perfectly usable for a hosted turn.
     */
    @Transactional(readOnly = true)
    public java.util.Set<UUID> edgeEligibleDynamicRegistrationIds() {
        return repository.findByEnabledTrue().stream()
                .filter(McpServerRegistration::isDynamic)
                .filter(McpServerRegistration::isUsableByAgents)
                .filter(McpServerRegistration::isEdgeEligible)
                .map(McpServerRegistration::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    // --- MCP-STDIO-SANDBOXING review queue (2026-08-05) ------------------------
    //
    // Every method below is deliberately TENANT-SCOPED ONLY (same as every
    // other method in this leaf-module service) - it reads/writes whatever
    // schema the ambient TenantContext currently points at, exactly like
    // enabledDynamicRegistrations()/usableDynamicRegistrationIds() above.
    // core/mcp has no dependency on core/tenancy (see this module's pom.xml
    // description - "still no dependency on any other core module") and
    // therefore no way to iterate every tenant's schema itself. The CROSS-
    // tenant admin surface (the actual "list every PENDING registration
    // platform-wide" queue a platform-staff reviewer needs) lives in
    // core/agents' McpRegistryReviewService instead, which already depends on
    // both this module and TenantSchemaIterator (the same "no dependency
    // added to a leaf module" boundary DynamicMcpConnectionManager's own
    // package-info/pom comment already draws for the identical reason) - it
    // calls forEachActiveTenantSchema and, for each schema, delegates the
    // actual read/write down to these tenant-scoped methods.

    /**
     * Every PENDING self-service STDIO registration for the CURRENT tenant - see class-level
     * comment above for the cross-tenant caller.
     */
    @Transactional(readOnly = true)
    public List<McpServerRegistration> pendingReview() {
        return repository.findAll().stream()
                .filter(e -> e.getReviewStatus() == McpReviewStatus.PENDING)
                .toList();
    }

    /**
     * Platform-staff approval - unblocks the NEXT connect/test attempt (does not itself trigger
     * one). 404 if the id doesn't exist in the current tenant schema.
     */
    @Transactional
    public McpServerRegistrationResponse approveReview(UUID id) {
        McpServerRegistration entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerRegistrationNotFoundException(id));
        entity.approveReview();
        return McpServerRegistrationResponse.from(repository.save(entity));
    }

    /**
     * Platform-staff rejection - {@code reason} is optional and surfaced back to the tenant via the
     * connect refusal hint. 404 if the id doesn't exist in the current tenant schema.
     */
    @Transactional
    public McpServerRegistrationResponse rejectReview(UUID id, String reason) {
        McpServerRegistration entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerRegistrationNotFoundException(id));
        entity.rejectReview(reason);
        return McpServerRegistrationResponse.from(repository.save(entity));
    }

    // --- Bounded auto-retry with backoff (2026-08-05) ---------------------------

    /**
     * ERROR'd, enabled, dynamic registrations whose {@code nextRetryAt} is due and which have not
     * given up ({@code retryExhausted}) - what {@code
     * DynamicMcpConnectionManager#retryErroredConnections} picks up on its scheduled tick, per
     * tenant. Deliberately excludes anything the review gate is blocking (PENDING/REJECTED) - see
     * {@link McpServerRegistration#markConnectionRefusedByReview}'s javadoc: nothing about a
     * review-blocked registration's outcome can change on its own, so retrying it is pure churn,
     * not a real attempt.
     */
    @Transactional(readOnly = true)
    public List<McpServerRegistration> registrationsEligibleForRetry(Instant now) {
        return repository.findByEnabledTrue().stream()
                .filter(McpServerRegistration::isDynamic)
                .filter(e -> e.getConnectionStatus() == McpConnectionStatus.ERROR)
                .filter(e -> !e.isRetryExhausted())
                .filter(e -> !e.isReviewBlocked())
                .filter(e -> e.getNextRetryAt() != null && !e.getNextRetryAt().isAfter(now))
                .toList();
    }

    private McpServerRegistration applyAndReturn(
            McpServerRegistration entity,
            java.util.function.Consumer<McpServerRegistration> mutation) {
        mutation.accept(entity);
        return entity;
    }
}
