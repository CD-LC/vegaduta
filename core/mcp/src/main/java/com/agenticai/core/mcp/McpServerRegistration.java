package com.agenticai.core.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Records an MCP server the platform knows about, whether it's enabled, and (#424, "Dynamic MCP
 * servers... how client can configure and use it during runtime") optionally the real connection
 * config (command/args/encrypted env) needed to actually connect to it at runtime.
 *
 * <p><b>Two ways a registration's tools become real, both still supported:</b>
 *
 * <ol>
 *   <li><b>Static (Stage 4 MVP, unchanged):</b> the transport connection is configured in {@code
 *       application-mcp.yml} via Spring AI's autoconfigured MCP client starter at JVM startup -
 *       this table's {@code name} matches the connection name used there (e.g. "filesystem").
 *       {@code command}/{@code args}/{@code encryptedEnv} are null for these rows ({@code
 *       connectionStatus = STATIC}); {@code enabled} is just the on/off switch over that
 *       already-running, already-connected toolset.
 *   <li><b>Dynamic (new):</b> {@code command}/{@code args}/{@code encryptedEnv} are populated (via
 *       {@link com.agenticai.core.mcp.McpRegistryService#register}), and {@link
 *       com.agenticai.core.agents} module's {@code DynamicMcpConnectionManager} connects a real MCP
 *       client to it at runtime the moment it's saved (or on the next server start) - no YAML edit,
 *       no restart. {@code connectionStatus}/{@code connectionError}/{@code connectedAt} track that
 *       live connection's real state, since "the admin wants this on" and "it's actually reachable
 *       right now" are different things once the server is a real subprocess that can fail (bad API
 *       key, no npx/Node, network down, upstream server crash).
 * </ol>
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(
        name = "mcp_server_registration",
        uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class McpServerRegistration extends McpRegistrationGovernance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private McpServerType type;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    // Dynamic connection config (#424) - null on every STATIC row (see class
    // javadoc). command/args are never secret (e.g. "npx", ["-y",
    // "@modelcontextprotocol/server-brave-search"]) so they're stored plain;
    // encryptedEnv is the server's env vars (API keys, tokens, connection
    // strings) as one AES-256-GCM-encrypted JSON blob - see
    // McpRegistryService for the encrypt/decrypt boundary (this entity never
    // touches EncryptionService directly, same "entity stays a dumb
    // persistence shape" posture as every other credential entity in this
    // schema, e.g. TwilioCredential).
    @Column(length = 255)
    private String command;

    @Column(columnDefinition = "TEXT")
    private String args;

    @Column(name = "encrypted_env", columnDefinition = "TEXT")
    private String encryptedEnv;

    // Remote (registry) connection target - a hosted MCP endpoint reached over
    // SSE / streamable-HTTP, with no local subprocess. Null on STATIC rows and
    // on dynamic STDIO rows (which use command/args instead); non-null only for
    // a remote registration. See configureRemote / isDynamic below.
    @Column(length = 2048)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false, length = 32)
    private McpConnectionStatus connectionStatus = McpConnectionStatus.NOT_CONFIGURED;

    @Column(name = "connection_error", columnDefinition = "TEXT")
    private String connectionError;

    // MCP-DOCTOR (2026-07-25) - the structured half of connectionError: WHICH
    // step failed (SPAWN/HTTP/HANDSHAKE/INIT_TIMEOUT) and an actionable hint
    // ("this server requires credentials - add Authorization = Bearer <token>
    // to its env entries"), so the UI can render more than the SDK's generic
    // "Client failed to initialize by explicit API call". Null whenever
    // connectionError is null, and on legacy rows written before this column.
    @Enumerated(EnumType.STRING)
    @Column(name = "connection_phase", length = 32)
    private McpConnectionPhase connectionPhase;

    @Column(name = "connection_hint", columnDefinition = "TEXT")
    private String connectionHint;

    // MCP-SELF-HEAL persistence (2026-07-26, migration V135). Previously the
    // self-healer's suggestion (McpStdioSelfHealer, core/agents) only ever
    // lived in the ephemeral POST /{id}/test response - never here - so a
    // registration-time connect failure (the actual "Add from catalog" path,
    // McpRegistryController#register -> DynamicMcpConnectionManager#connect)
    // had NOTHING to show but the raw error, no path forward besides
    // manually editing the command. Persisting it here means BOTH the
    // register-time failure and every subsequent /list poll can offer
    // "Apply suggested fix" - not just a freshly-clicked /test call.
    // suggestedFixArgs is JSON-encoded text (Jackson), same convention this
    // entity's own `args` column already uses - no new serialization pattern.
    @Column(name = "suggested_fix_command", length = 255)
    private String suggestedFixCommand;

    @Column(name = "suggested_fix_args", columnDefinition = "TEXT")
    private String suggestedFixArgs;

    @Column(name = "suggested_fix_reason", columnDefinition = "TEXT")
    private String suggestedFixReason;

    // MCP-DOCTOR readiness gate - see McpUsageStatus. UNTESTED on create and
    // after every config change; READY/FAILED written by the auto-test that
    // runs on enable/update and by POST /api/mcp/registrations/{id}/test;
    // ACKNOWLEDGED only by an explicit admin override. Agents only receive
    // tools from READY/ACKNOWLEDGED registrations.
    @Enumerated(EnumType.STRING)
    @Column(name = "usage_status", nullable = false, length = 32)
    private McpUsageStatus usageStatus = McpUsageStatus.UNTESTED;

    @Column(name = "connected_at")
    private Instant connectedAt;

    // Structural marker (2026-08-15) for a registration whose live
    // connection headers come from a real OAuth flow rather than this row's
    // own encryptedEnv - see DynamicMcpConnectionManager#remoteHeaders and
    // V204's migration comment for why this is a dedicated column instead
    // of keying off `name` (per-tenant, user-editable, unreliable). Null
    // for every registration that isn't OAuth-backed (the overwhelming
    // majority). Set exactly once, by the owning OAuth service itself (e.g.
    // AtlassianOAuthService), never by McpRegistryService#register's normal
    // request-driven path.
    @Column(name = "oauth_provider", length = 32)
    private String oauthProvider;

    // Decrypt-failure visibility (2026-08-05) - see McpRegistryService's
    // decryptEnv javadoc. Previously a decrypt/parse failure was silently
    // indistinguishable from "this tenant never configured any env vars" -
    // both just produced an empty map. Set (and cleared) alongside every real
    // connect/test attempt via DynamicMcpConnectionManager, since that is the
    // one place a fresh McpDynamicConfig (and therefore a fresh decrypt
    // outcome) is resolved. Reset to false by configureDynamic/configureRemote
    // below - a freshly-saved env deserves a fresh read, not a stale flag.
    @Column(name = "credentials_corrupted", nullable = false)
    private boolean credentialsCorrupted;

    // Edge tool-access consent system (2026-08-06) - direct report: "I dont
    // want anything going wrong allowing directly without user or end user
    // approval." An MCP registration's schema is tenant-supplied and was
    // never reviewed the way a built-in platform tool was (the exact
    // reasoning the original, now-removed, EdgeToolExecutionController
    // whitelist stated) - so a registration is NOT usable from an edge
    // (on-device) session by default, even once it's fully enabled/connected
    // for hosted use. This is the OWNING TENANT's own admin decision (a
    // narrower, separate policy question - "may this already-approved tool
    // run from a browser tab" - from whatever gate got it approved to run at
    // all, e.g. the STDIO review queue), set via
    // McpRegistryController#setEdgeEligible, defaulting to false so nothing
    // is silently edge-usable on upgrade. This is ONLY layer 1 of 2 - see
    // AgentToolProvider's edge-resolution javadoc for layer 2, the
    // end-user's own per-tool consent, which is enforced entirely
    // client-side and has no server-side representation.
    @Column(name = "edge_eligible", nullable = false)
    private boolean edgeEligible;

    /**
     * Distinct from {@link #connectedAt} (null on any failure) and {@link #updatedAt} (also touched
     * by unrelated edits like {@link #setEnabled} or a config change) - stamped by every mutator
     * below that represents a REAL connect/test attempt (markConnecting, markConnected, markError,
     * markTestPassed), so "when did this last actually try to connect" is answerable even when the
     * attempt failed and connectedAt stayed null.
     */
    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected McpServerRegistration() {
        // JPA
    }

    public McpServerRegistration(
            String name, McpServerType type, String description, boolean enabled) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.enabled = enabled;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @Override
    protected void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    /**
     * Sets/replaces this registration's dynamic STDIO connection config (local subprocess) - see
     * class javadoc.
     */
    public void configureDynamic(String command, String argsJson, String encryptedEnv) {
        this.command = command;
        this.args = argsJson;
        this.encryptedEnv = encryptedEnv;
        this.url = null;
        this.connectionStatus = McpConnectionStatus.NOT_CONFIGURED;
        this.connectionError = null;
        this.connectionPhase = null;
        this.connectionHint = null;
        this.suggestedFixCommand = null;
        this.suggestedFixArgs = null;
        this.suggestedFixReason = null;
        // Any config change invalidates the last verification - the readiness
        // gate must re-prove the NEW command/args/env, not trust the old ones.
        this.usageStatus = McpUsageStatus.UNTESTED;
        this.connectedAt = null;
        // A freshly-saved env deserves a fresh decrypt attempt, not a stale
        // "your credentials could not be read" carried over from the PREVIOUS
        // (possibly corrupted) ciphertext - see credentialsCorrupted's field
        // javadoc.
        this.credentialsCorrupted = false;
        this.updatedAt = Instant.now();
    }

    /**
     * Sets/replaces this registration's dynamic REMOTE connection config (a hosted SSE /
     * streamable-HTTP endpoint, no local subprocess) - the registry-integration counterpart to
     * {@link #configureDynamic}. {@code encryptedEnv} may still carry a bearer token / header value
     * the remote endpoint needs; {@code command}/{@code args} are cleared since there's nothing to
     * spawn.
     */
    public void configureRemote(String url, String encryptedEnv) {
        this.url = url;
        this.command = null;
        this.args = null;
        this.encryptedEnv = encryptedEnv;
        this.connectionStatus = McpConnectionStatus.NOT_CONFIGURED;
        this.connectionError = null;
        this.connectionPhase = null;
        this.connectionHint = null;
        this.suggestedFixCommand = null;
        this.suggestedFixArgs = null;
        this.suggestedFixReason = null;
        // Same reason as configureDynamic - a changed URL/env must be re-verified.
        this.usageStatus = McpUsageStatus.UNTESTED;
        this.connectedAt = null;
        this.credentialsCorrupted = false;
        this.updatedAt = Instant.now();
    }

    public void markConnecting() {
        this.connectionStatus = McpConnectionStatus.CONNECTING;
        this.connectionError = null;
        this.connectionPhase = null;
        this.connectionHint = null;
        this.suggestedFixCommand = null;
        this.suggestedFixArgs = null;
        this.suggestedFixReason = null;
        this.lastAttemptedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markConnected() {
        this.connectionStatus = McpConnectionStatus.CONNECTED;
        this.connectionError = null;
        this.connectionPhase = null;
        this.connectionHint = null;
        this.suggestedFixCommand = null;
        this.suggestedFixArgs = null;
        this.suggestedFixReason = null;
        // A successful real connect (initialize + listTools answered) IS the
        // readiness test passing - see McpUsageStatus.READY.
        this.usageStatus = McpUsageStatus.READY;
        this.connectedAt = Instant.now();
        this.lastAttemptedAt = Instant.now();
        // Success resets the auto-retry backoff state completely - a
        // registration that just worked owes nothing to whatever streak of
        // failures preceded it (see McpRegistrationGovernance's retry fields).
        resetRetryState();
        this.updatedAt = Instant.now();
    }

    public void markError(String message) {
        markError(message, null, null);
    }

    /**
     * Structured-diagnosis variant (MCP-DOCTOR) - see connectionPhase/connectionHint field javadoc.
     * Also fails the readiness gate.
     */
    public void markError(String message, McpConnectionPhase phase, String hint) {
        markError(message, phase, hint, null, null, null);
    }

    /**
     * Same as the 3-arg overload, plus a persisted self-heal suggestion (MCP-SELF-HEAL, migration
     * V135) - see the {@code suggestedFix*} fields' javadoc. All three suggestion args are null
     * together or not at all (there's no case where a fix has a command but no reason).
     *
     * <p><b>Bounded auto-retry with backoff (2026-08-05):</b> every real connect/test failure that
     * reaches this method also advances the retry schedule - {@link #backoffFor(int)} is computed
     * from the CURRENT (pre-increment) {@code retryAttemptCount}, then the count is incremented;
     * once it reaches {@link #MAX_AUTO_RETRY_ATTEMPTS} the registration is marked {@code
     * retryExhausted} instead (no further {@code nextRetryAt} is scheduled - see {@code
     * DynamicMcpConnectionManager#retryErroredConnections}, which only ever picks up rows with a
     * due, non-null {@code nextRetryAt}). A manual "Try again" click goes through this exact same
     * method (via {@code connect()} calling {@code markErrorDiagnosis} on failure) - there is
     * deliberately no separate "this failure was automatic" bit, since a registration that is
     * currently failing is currently failing regardless of who clicked the button, and {@link
     * #markConnected}/{@link #markTestPassed} always zero this state out the moment it actually
     * works again.
     */
    public void markError(
            String message,
            McpConnectionPhase phase,
            String hint,
            String suggestedFixCommand,
            String suggestedFixArgsJson,
            String suggestedFixReason) {
        this.connectionStatus = McpConnectionStatus.ERROR;
        this.connectionError = message;
        this.connectionPhase = phase;
        this.connectionHint = hint;
        this.suggestedFixCommand = suggestedFixCommand;
        this.suggestedFixArgs = suggestedFixArgsJson;
        this.suggestedFixReason = suggestedFixReason;
        this.usageStatus = McpUsageStatus.FAILED;
        this.connectedAt = null;
        this.lastAttemptedAt = Instant.now();
        scheduleNextRetryOrGiveUp();
        this.updatedAt = Instant.now();
    }

    /**
     * The approval-gate refusal path (MCP-STDIO-SANDBOXING option (d)) - deliberately NOT {@link
     * #markError}: a PENDING/REJECTED registration will keep refusing to connect until a human acts
     * on it, so scheduling an automatic backoff retry for it (as markError would) is pure churn -
     * nothing about the outcome can change on its own. Leaves the retry fields exactly as they
     * were.
     */
    public void markConnectionRefusedByReview(String hint) {
        this.connectionStatus = McpConnectionStatus.ERROR;
        this.connectionError = "Connection blocked: platform review required.";
        this.connectionPhase = McpConnectionPhase.SPAWN;
        this.connectionHint = hint;
        this.suggestedFixCommand = null;
        this.suggestedFixArgs = null;
        this.suggestedFixReason = null;
        this.usageStatus = McpUsageStatus.FAILED;
        this.connectedAt = null;
        this.lastAttemptedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * A bounded connection TEST passed (MCP-DOCTOR) - clears any stale diagnosis and marks the
     * registration READY for agent use, WITHOUT claiming a live connection is being held (that's
     * {@link #markConnected}, which the real connect path calls and which also implies READY).
     */
    public void markTestPassed() {
        this.connectionError = null;
        this.connectionPhase = null;
        this.connectionHint = null;
        this.suggestedFixCommand = null;
        this.suggestedFixArgs = null;
        this.suggestedFixReason = null;
        this.usageStatus = McpUsageStatus.READY;
        this.lastAttemptedAt = Instant.now();
        // A passed test is real, current proof the server works - same
        // "nothing owed to a prior failure streak" reasoning as markConnected.
        resetRetryState();
        this.updatedAt = Instant.now();
    }

    /**
     * Admin escape hatch for the readiness gate: "I know the last test failed / it was never tested
     * - let agents use it anyway." No-op when already READY (nothing to override). See
     * McpUsageStatus.ACKNOWLEDGED.
     */
    public void acknowledgeUsage() {
        if (this.usageStatus != McpUsageStatus.READY) {
            this.usageStatus = McpUsageStatus.ACKNOWLEDGED;
            this.updatedAt = Instant.now();
        }
    }

    public void markDisabled() {
        this.connectionStatus = McpConnectionStatus.DISABLED;
        this.connectedAt = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Decrypt-failure visibility flag - see field javadoc. Deliberately does NOT stamp {@code
     * updatedAt}: this is a side-observation surfaced alongside a connect/test attempt (which
     * already stamps its own timestamps), not a user-facing state change in its own right.
     */
    public void setCredentialsCorrupted(boolean credentialsCorrupted) {
        this.credentialsCorrupted = credentialsCorrupted;
    }

    /**
     * Tenant-admin's own explicit "this MAY run from an edge session" decision - see field javadoc.
     * Stamps {@code updatedAt}: unlike credentialsCorrupted, this IS a user-facing policy change in
     * its own right.
     */
    public void setEdgeEligible(boolean edgeEligible) {
        this.edgeEligible = edgeEligible;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public McpServerType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Null for STATIC (YAML-configured) rows - see class javadoc. */
    public String getCommand() {
        return command;
    }

    /** JSON array string, or null - see class javadoc. */
    public String getArgs() {
        return args;
    }

    /**
     * AES-256-GCM ciphertext (base64), or null - decrypt via EncryptionService, never expose to a
     * frontend response.
     */
    public String getEncryptedEnv() {
        return encryptedEnv;
    }

    /**
     * Remote endpoint URL for a SSE / streamable-HTTP registration, or null - see class javadoc.
     */
    public String getUrl() {
        return url;
    }

    public McpConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public String getConnectionError() {
        return connectionError;
    }

    public McpConnectionPhase getConnectionPhase() {
        return connectionPhase;
    }

    public String getConnectionHint() {
        return connectionHint;
    }

    public String getSuggestedFixCommand() {
        return suggestedFixCommand;
    }

    public String getSuggestedFixArgs() {
        return suggestedFixArgs;
    }

    public String getSuggestedFixReason() {
        return suggestedFixReason;
    }

    public McpUsageStatus getUsageStatus() {
        return usageStatus;
    }

    /**
     * The readiness gate itself: only READY (test passed) or ACKNOWLEDGED (admin override)
     * registrations may contribute tools to an agent run.
     */
    public boolean isUsableByAgents() {
        return usageStatus == McpUsageStatus.READY || usageStatus == McpUsageStatus.ACKNOWLEDGED;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    /**
     * A live per-tenant connection this platform manages itself - a local STDIO subprocess ({@code
     * command}) or a remote endpoint ({@code url}) - as opposed to a STATIC row that only
     * references an application-mcp.yml connection.
     */
    public boolean isDynamic() {
        return command != null || url != null;
    }

    /**
     * True for a dynamic registration reached over the network ({@code url} set), false for a local
     * STDIO subprocess or a STATIC row.
     */
    public boolean isRemote() {
        return url != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getOauthProvider() {
        return oauthProvider;
    }

    /**
     * Deliberately does NOT stamp {@code updatedAt} - same "internal marker, not a user-facing
     * state change" posture as {@link #setCredentialsCorrupted}. Called exactly once per
     * connection, immediately after the OAuth service upserts this row via {@code
     * McpRegistryService#register}.
     */
    public void setOauthProvider(String oauthProvider) {
        this.oauthProvider = oauthProvider;
    }

    public boolean isCredentialsCorrupted() {
        return credentialsCorrupted;
    }

    public boolean isEdgeEligible() {
        return edgeEligible;
    }

    public Instant getLastAttemptedAt() {
        return lastAttemptedAt;
    }
}
