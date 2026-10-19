package com.agenticai.core.common.studioapp;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.share.AgentShareService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.share.WorkflowShareService;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner and runtime logic for published Phone-App-Studio apps.
 *
 * <p>Two caller shapes, exactly like the share services:
 * <ul>
 *   <li><b>Owner paths</b> ({@link #publish}, {@link #listApps}, ...) run in a
 *       normal authenticated request - {@code TenantContext} is already the
 *       caller's own schema.</li>
 *   <li><b>Public paths</b> ({@link #resolvePublicApp} and everything after
 *       it) start from an anonymous slug: resolve slug -> schema through
 *       {@link TenantResolutionService}, set {@link TenantContext} by hand,
 *       load the app row there. Same cross-tenant sequence {@code
 *       AgentShareController#withResolvedShare} established; the controller
 *       owns clearing the context afterwards.</li>
 * </ul>
 *
 * <p>Publishing auto-mints the underlying agent/workflow share when the
 * builder connected one in the studio but never minted a link - the share IS
 * the authorization for the public call, so {@code publish} verifies each
 * connection's target is one the caller may share ({@code requireOwnedAgent}
 * / {@code requireOwnedWorkflow}, the same owner-or-admin bar).
 */
@Service
public class StudioAppService {

    private static final Logger log = LoggerFactory.getLogger(StudioAppService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder();
    private static final Pattern SLUG_SHAPE = Pattern.compile("[A-Za-z0-9_-]{16,24}");
    private static final Pattern ALIAS_SHAPE = Pattern.compile("[a-z][a-z0-9_-]{0,39}");
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^@\\s]{1,64}@[^@\\s]{1,190}");
    private static final int MAX_HTML_BYTES = 400_000;
    private static final int MAX_CONNECTIONS = 12;

    private final StudioAppRepository appRepository;
    private final StudioAppConnectionRepository connectionRepository;
    private final StudioAppUserRepository userRepository;
    private final StudioAppTokenService tokenService;
    private final AgentShareService agentShareService;
    private final WorkflowShareService workflowShareService;
    private final TenantResolutionService tenantResolutionService;

    public StudioAppService(
        StudioAppRepository appRepository,
        StudioAppConnectionRepository connectionRepository,
        StudioAppUserRepository userRepository,
        StudioAppTokenService tokenService,
        AgentShareService agentShareService,
        WorkflowShareService workflowShareService,
        TenantResolutionService tenantResolutionService
    ) {
        this.appRepository = appRepository;
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.agentShareService = agentShareService;
        this.workflowShareService = workflowShareService;
        this.tenantResolutionService = tenantResolutionService;
    }

    // ------------------------------------------------------------------
    // Owner side
    // ------------------------------------------------------------------

    public record ConnectionSpec(
        String alias, String kind, String targetId, String shareId, String displayName,
        String requestTemplate, String responsePath, Boolean requiresAuth
    ) {
    }

    public record PublishRequest(
        String appId, String name, String html, String authMode,
        Boolean aiEnabled, String aiSystemPrompt, Integer aiMaxTokens,
        List<ConnectionSpec> connections
    ) {
    }

    /** Create-or-update publish. {@code appId} continues an existing app (same slug, same end users); absent mints a fresh slug. */
    @Transactional
    public StudioApp publish(PublishRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new StudioAppException("name_required");
        }
        if (req.html() == null || req.html().isBlank()) {
            throw new StudioAppException("html_required");
        }
        if (req.html().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
            throw new StudioAppException("app_too_large");
        }
        StudioApp.AuthMode authMode = parseAuthMode(req.authMode());
        List<ConnectionSpec> specs = req.connections() == null ? List.of() : req.connections();
        if (specs.size() > MAX_CONNECTIONS) {
            throw new StudioAppException("too_many_connections");
        }
        if (authMode != StudioApp.AuthMode.NONE && specs.isEmpty() && !Boolean.TRUE.equals(req.aiEnabled())) {
            // Legal but almost certainly not what the builder meant - a
            // login-gated app with nothing inside worth gating. Warn-level:
            // allowed, the client confirms it.
            log.info("Publishing an app with auth but no connections and no AI block");
        }

        StudioApp app;
        if (req.appId() != null && !req.appId().isBlank()) {
            app = appRepository.findById(parseUuid(req.appId(), "app"))
                .orElseThrow(() -> new StudioAppException("app_not_found"));
            app.setPublishedVersion(app.getPublishedVersion() + 1);
        } else {
            app = new StudioApp();
            app.setSlug(newSlug());
            app.setTokenSecret(newSecret());
            app.setCreatedBy(currentSubject());
        }
        app.setName(req.name().trim());
        app.setHtml(req.html());
        app.setAuthMode(authMode);
        app.setAiEnabled(Boolean.TRUE.equals(req.aiEnabled()));
        app.setAiSystemPrompt(trimToNull(req.aiSystemPrompt(), 4000));
        app.setAiMaxTokens(req.aiMaxTokens() == null ? 700 : Math.max(64, Math.min(4096, req.aiMaxTokens())));
        app.setStatus(StudioApp.Status.LIVE);
        app.setUpdatedAt(Instant.now());

        // Verify + normalize every connection BEFORE the app row commits, so
        // a bad spec publishes nothing.
        List<StudioAppConnection> normalized = specs.stream().map(s -> normalizeConnection(app, s)).toList();

        try {
            appRepository.saveAndFlush(app);
        } catch (DataIntegrityViolationException e) {
            // Slug collision is a one-in-2^96 event - retry once with a fresh slug.
            app.setSlug(newSlug());
            appRepository.saveAndFlush(app);
        }
        connectionRepository.deleteByAppId(app.getId());
        connectionRepository.flush();
        connectionRepository.saveAll(normalized);
        tenantResolutionService.link(ExternalAccountProvider.STUDIO_APP, app.getSlug(), currentTenantSchema());
        return app;
    }

    private StudioAppConnection normalizeConnection(StudioApp app, ConnectionSpec s) {
        if (s == null || s.alias() == null || !ALIAS_SHAPE.matcher(s.alias()).matches()) {
            throw new StudioAppException("bad_alias");
        }
        StudioAppConnection.Kind kind = switch (s.kind() == null ? "" : s.kind().toUpperCase(java.util.Locale.ROOT)) {
            case "AGENT" -> StudioAppConnection.Kind.AGENT;
            case "WORKFLOW" -> StudioAppConnection.Kind.WORKFLOW;
            default -> throw new StudioAppException("bad_kind");
        };
        UUID targetId = parseUuid(s.targetId(), "target");
        // The share link IS the public authorization: mint one when the
        // builder never did (requireOwned* enforces the owner-or-admin bar),
        // and verify an existing one is still live and points at this target.
        String shareId;
        if (kind == StudioAppConnection.Kind.AGENT) {
            Agent agent = agentShareService.requireOwnedAgent(targetId);
            shareId = agentShareService.createOrGet(agent.getId()).shareId();
        } else {
            WorkflowDefinition wf = workflowShareService.requireOwnedWorkflow(targetId);
            shareId = workflowShareService.createOrGet(wf.getId()).shareId();
        }
        StudioAppConnection c = new StudioAppConnection();
        c.setAppId(app.getId());
        c.setAlias(s.alias().trim());
        c.setKind(kind);
        c.setTargetId(targetId);
        c.setShareId(shareId);
        c.setDisplayName(trimToNull(s.displayName(), 120));
        c.setRequestTemplate(trimToNull(s.requestTemplate(), 2000));
        c.setResponsePath(trimToNull(s.responsePath(), 160));
        c.setRequiresAuth(Boolean.TRUE.equals(s.requiresAuth()));
        return c;
    }

    @Transactional(readOnly = true)
    public List<StudioApp> listApps() {
        return appRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<StudioApp> findOwned(UUID appId) {
        return appRepository.findById(appId);
    }

    @Transactional(readOnly = true)
    public List<StudioAppConnection> connectionsOf(UUID appId) {
        return connectionRepository.findByAppIdOrderBySortOrder(appId);
    }

    /** Stops the public surface resolving without deleting end-user rows; re-publish revives the same slug. */
    @Transactional
    public void setStatus(UUID appId, StudioApp.Status status) {
        StudioApp app = appRepository.findById(appId)
            .orElseThrow(() -> new StudioAppException("app_not_found"));
        app.setStatus(status);
        app.setUpdatedAt(Instant.now());
        if (status == StudioApp.Status.DISABLED) {
            tenantResolutionService.unlink(ExternalAccountProvider.STUDIO_APP, app.getSlug());
        } else {
            tenantResolutionService.link(ExternalAccountProvider.STUDIO_APP, app.getSlug(), currentTenantSchema());
        }
    }

    @Transactional
    public void delete(UUID appId) {
        StudioApp app = appRepository.findById(appId)
            .orElseThrow(() -> new StudioAppException("app_not_found"));
        tenantResolutionService.unlink(ExternalAccountProvider.STUDIO_APP, app.getSlug());
        appRepository.delete(app);
    }

    @Transactional(readOnly = true)
    public List<StudioAppUser> listUsers(UUID appId) {
        return userRepository.findByAppIdOrderByCreatedAtDesc(appId);
    }

    @Transactional(readOnly = true)
    public long countUsers(UUID appId) {
        return userRepository.countByAppId(appId);
    }

    @Transactional
    public void setUserStatus(UUID appId, UUID userId, StudioAppUser.Status status) {
        StudioAppUser user = userRepository.findByIdAndAppId(userId, appId)
            .orElseThrow(() -> new StudioAppException("user_not_found"));
        user.setStatus(status);
    }

    @Transactional
    public void deleteUser(UUID appId, UUID userId) {
        StudioAppUser user = userRepository.findByIdAndAppId(userId, appId)
            .orElseThrow(() -> new StudioAppException("user_not_found"));
        userRepository.delete(user);
    }

    // ------------------------------------------------------------------
    // Public side - every method here expects resolvePublicApp to have set
    // TenantContext to the owning schema first.
    // ------------------------------------------------------------------

    /**
     * slug -> owning schema -> TenantContext -> LIVE app row. Empty for
     * unknown, malformed or disabled - deliberately indistinguishable, same
     * "this link is done" posture as the share surfaces. The CALLER owns
     * {@code TenantContext.clear()} on a present return (see {@code
     * AgentShareService#resolveActiveShare}'s javadoc for the boundary
     * reasoning).
     */
    public Optional<StudioApp> resolvePublicApp(String slug) {
        if (slug == null || !SLUG_SHAPE.matcher(slug).matches()) {
            return Optional.empty();
        }
        Optional<String> schema = tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.STUDIO_APP, slug);
        if (schema.isEmpty()) {
            return Optional.empty();
        }
        TenantContext.set(TenantContext.of(schema.get(), "system:studio-app:" + slug, java.util.Set.of()));
        Optional<StudioApp> app = appRepository.findBySlug(slug);
        if (app.isEmpty() || app.get().getStatus() != StudioApp.Status.LIVE) {
            TenantContext.clear();
            return Optional.empty();
        }
        return app;
    }

    public List<StudioAppConnection> publicConnections(StudioApp app) {
        return connectionRepository.findByAppIdOrderBySortOrder(app.getId());
    }

    /** Signup - creates an end user for THIS app only. Returns the fresh session token. */
    @Transactional
    public AuthResult signup(StudioApp app, String email, String password, String displayName) {
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail == null || !EMAIL_SHAPE.matcher(normalizedEmail).matches()) {
            throw new StudioAppException("bad_email");
        }
        if (password == null || password.length() < 8 || password.length() > 128
            || exceedsBcryptLimit(password)) {
            throw new StudioAppException("weak_password");
        }
        if (userRepository.findByAppIdAndEmail(app.getId(), normalizedEmail).isPresent()) {
            throw new StudioAppException("email_taken");
        }
        StudioAppUser user = new StudioAppUser();
        user.setAppId(app.getId());
        user.setEmail(normalizedEmail);
        user.setDisplayName(trimToNull(displayName, 120));
        user.setPasswordHash(PASSWORDS.encode(password));
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        return new AuthResult(tokenService.issue(app, user), user);
    }

    /**
     * BCrypt only uses the first 72 bytes, and Spring Security 6.3+ throws on
     * anything longer - so a 128-char multi-byte password passed the length
     * check and then 500'd inside encode()/matches(). Signup can never store
     * such a password, so at login it is simply a wrong one.
     */
    private static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72;
    }

    @Transactional
    public AuthResult login(StudioApp app, String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        Optional<StudioAppUser> found = normalizedEmail == null
            ? Optional.<StudioAppUser>empty()
            : userRepository.findByAppIdAndEmail(app.getId(), normalizedEmail);
        // Same failure for unknown email and wrong password - an app slug is
        // public, so a distinct "no such user" is an enumeration oracle.
        if (found.isEmpty()
            || found.get().getStatus() != StudioAppUser.Status.ACTIVE
            || password == null
            || exceedsBcryptLimit(password)
            || !PASSWORDS.matches(password, found.get().getPasswordHash())) {
            throw new StudioAppException("invalid_credentials");
        }
        StudioAppUser user = found.get();
        user.setLastLoginAt(Instant.now());
        return new AuthResult(tokenService.issue(app, user), user);
    }

    /** Verifies a Bearer token against this app and loads a still-ACTIVE user. Empty for any failure - one 401 shape for all of them. */
    @Transactional(readOnly = true)
    public Optional<StudioAppUser> authenticate(StudioApp app, String authorizationHeader) {
        String token = bearer(authorizationHeader);
        if (token == null) {
            return Optional.empty();
        }
        return tokenService.verify(app, token)
            .flatMap(p -> userRepository.findByIdAndAppId(p.userId(), app.getId()))
            .filter(u -> u.getStatus() == StudioAppUser.Status.ACTIVE);
    }

    /** Whether this call needs a signed-in end user: the app requires it globally, or this connection opts in. */
    public boolean authRequired(StudioApp app, StudioAppConnection connection) {
        return app.getAuthMode() == StudioApp.AuthMode.REQUIRED
            || (connection != null && connection.isRequiresAuth());
    }

    public record AuthResult(String token, StudioAppUser user) {
    }

    // ------------------------------------------------------------------

    private String currentTenantSchema() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException("StudioAppService requires a tenant context"));
    }

    private String currentSubject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String t = header.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String trimToNull(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static UUID parseUuid(String raw, String what) {
        try {
            return UUID.fromString(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw new StudioAppException("bad_" + what + "_id");
        }
    }

    private static StudioApp.AuthMode parseAuthMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return StudioApp.AuthMode.NONE;
        }
        try {
            return StudioApp.AuthMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new StudioAppException("bad_auth_mode");
        }
    }

    /** 12 bytes -> 16 URL-safe chars (96 bits) - comfortably unguessable while keeping share links short. */
    private static String newSlug() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Domain exception - the controllers map its {@code code} onto a stable error body. */
    public static class StudioAppException extends RuntimeException {
        public StudioAppException(String code) {
            super(code);
        }
    }
}
