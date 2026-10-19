package com.agenticai.core.agents.tools.e2b;

import static com.agenticai.core.agents.tools.e2b.SandboxFileNames.extensionFor;
import static com.agenticai.core.agents.tools.e2b.SandboxFileNames.humanBytes;
import static com.agenticai.core.agents.tools.e2b.SandboxFileNames.uniqueName;

import com.agenticai.core.sdlc.E2bAccessResolver;
import com.agenticai.core.sdlc.E2bCredentialService;
import com.agenticai.core.sdlc.dto.SandboxFile;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Data-analysis sandbox (gap-analysis item 9) - the bridge between chat uploads and the {@code
 * run_code} tool. {@code AgentChatService} registers the data files a turn carried (CSV / XLSX /
 * JSON / PDF / TXT) under {@code agentId + sessionId}; {@link E2bRunCodeToolCallback} reads them
 * back at call time and mounts them in the sandbox working directory, so the model's script can
 * simply {@code pd.read_csv("data.csv")}.
 *
 * <p>In-memory and bounded on purpose: a TTL, a per-session file-count and byte cap, per-TENANT
 * session and byte caps (a tenant over its share evicts only its own oldest sessions), and
 * platform-wide session/byte caps (the heaviest tenant pays first) so uploads cannot grow the heap
 * without limit and one tenant cannot starve the others. Keyed by agent AND session (both
 * server-resolved, agent ids are tenant-scoped) so two tenants reusing a session id can never see
 * each other's files. A restart or TTL expiry simply means the user re-attaches. Gated by {@code
 * app.sandbox.data-analysis.enabled} (default OFF - turn it on only after the E2B template in
 * {@code core/sdlc/e2b-template} has been rebuilt and pushed); when off, {@link #register} is a
 * no-op. {@link #availableFor} is the ONE predicate (flag + agent attachment + tenant E2B
 * key/template) every gate uses.
 *
 * <p>Known limit: this is per-JVM state by default. With {@code
 * app.sandbox.data-analysis.store=redis} every registered file is also written (asynchronously) to
 * a Redis hash per session ({@link SandboxFileRedisMirror}) and {@link #filesFor} reads back any
 * file this replica lacks, so a follow-up turn that lands on another core replica still finds the
 * files. Warnings stay local (they are drained by the next {@code run_code} call of the same turn).
 * Redis failures fall back to the local copy, i.e. to the default behaviour. The Redis copy is
 * bounded (per-session file count, per-tenant and platform byte budgets), deleted when this
 * registry evicts a session it wrote for, and a name already taken by another replica's upload is
 * renamed ({@code name_2.csv}) rather than overwritten; see {@link SandboxFileRedisMirror}.
 */
@Component
public class SandboxFileRegistry {

    private static final Logger log = LoggerFactory.getLogger(SandboxFileRegistry.class);

    // Defaults for the configurable heap bounds (see application.yml).
    static final int DEFAULT_MAX_FILES_PER_SESSION = 6;
    static final int DEFAULT_MAX_SESSIONS = 50;
    static final long DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    static final int DEFAULT_MAX_SESSIONS_PER_TENANT = 10;
    static final long DEFAULT_MAX_TENANT_BYTES = 48L * 1024 * 1024;
    static final String NO_TENANT = "-";

    static final String REASON_CONNECT_E2B =
            "File analysis needs this workspace to connect E2B (API key and code-execution template) "
                    + "on the Providers page; until then attached data files cannot be analysed.";

    /**
     * Platform-managed sandbox is switched on but the platform credential is not set up (and no
     * tenant key).
     */
    static final String REASON_NOT_AVAILABLE_YET =
            "File analysis is not available yet for this workspace. You can connect your own E2B key "
                    + "(API key and code-execution template) on the Providers page to use it now.";

    private final boolean enabled;
    private final int maxFilesPerSession;
    private final long maxFileBytes;
    private final long maxSessionBytes;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final int maxSessions;
    private final long maxTotalBytes;
    private final int maxSessionsPerTenant;
    private final long maxTenantBytes;

    /**
     * Tenant capability probe (setter-injected, see {@link #setCredentialService}). Never set (unit
     * tests) = flag-only behaviour; set but the bean is absent = fail closed.
     */
    private volatile ObjectProvider<E2bCredentialService> credentialService;

    /**
     * Code-execution readiness incl. the platform-managed E2B fallback (setter-injected, see {@link
     * #setAccessResolver}). When the bean is present it replaces the tenant-only credential probe.
     */
    private volatile ObjectProvider<E2bAccessResolver> accessResolver;

    /**
     * {@code memory} (default) or {@code redis}; field-injected so the test-built constructors keep
     * the default.
     */
    @Value("${app.sandbox.data-analysis.store:memory}")
    private String store = "memory";

    private volatile ObjectProvider<StringRedisTemplate> redisProvider;
    private volatile SandboxFileRedisMirror mirror;

    /** Why a file was not mounted, surfaced in the next run_code result. */
    private final SandboxWarningLog warnings = new SandboxWarningLog();

    private final Map<String, SandboxSessionEntry> sessions = new LinkedHashMap<>();

    @Autowired
    public SandboxFileRegistry(
            @Value("${app.sandbox.data-analysis.enabled:false}") boolean enabled,
            @Value("${app.sandbox.data-analysis.max-files:6}") int maxFilesPerSession,
            @Value("${app.sandbox.data-analysis.max-file-bytes:10485760}") long maxFileBytes,
            @Value("${app.sandbox.data-analysis.max-session-bytes:20971520}") long maxSessionBytes,
            @Value("${app.sandbox.data-analysis.ttl-minutes:60}") long ttlMinutes,
            @Value("${app.sandbox.data-analysis.max-sessions:50}") int maxSessions,
            @Value("${app.sandbox.data-analysis.max-total-bytes:134217728}") long maxTotalBytes,
            @Value("${app.sandbox.data-analysis.max-sessions-per-tenant:10}")
                    int maxSessionsPerTenant,
            @Value("${app.sandbox.data-analysis.max-tenant-bytes:50331648}") long maxTenantBytes) {
        this(
                enabled,
                maxFilesPerSession,
                maxFileBytes,
                maxSessionBytes,
                ttlMinutes * 60_000L,
                System::currentTimeMillis,
                maxSessions,
                maxTotalBytes,
                maxSessionsPerTenant,
                maxTenantBytes);
    }

    /** Convenience for tests that do not care about the platform/tenant bounds (defaults apply). */
    public SandboxFileRegistry(
            boolean enabled,
            int maxFilesPerSession,
            long maxFileBytes,
            long maxSessionBytes,
            long ttlMinutes) {
        this(
                enabled,
                maxFilesPerSession,
                maxFileBytes,
                maxSessionBytes,
                ttlMinutes * 60_000L,
                System::currentTimeMillis);
    }

    SandboxFileRegistry(
            boolean enabled,
            int maxFilesPerSession,
            long maxFileBytes,
            long maxSessionBytes,
            long ttlMillis,
            LongSupplier clock) {
        this(
                enabled,
                maxFilesPerSession,
                maxFileBytes,
                maxSessionBytes,
                ttlMillis,
                clock,
                DEFAULT_MAX_SESSIONS,
                DEFAULT_MAX_TOTAL_BYTES,
                DEFAULT_MAX_SESSIONS_PER_TENANT,
                DEFAULT_MAX_TENANT_BYTES);
    }

    SandboxFileRegistry(
            boolean enabled,
            int maxFilesPerSession,
            long maxFileBytes,
            long maxSessionBytes,
            long ttlMillis,
            LongSupplier clock,
            int maxSessions,
            long maxTotalBytes,
            int maxSessionsPerTenant,
            long maxTenantBytes) {
        this.enabled = enabled;
        this.maxFilesPerSession = maxFilesPerSession;
        this.maxFileBytes = maxFileBytes;
        this.maxSessionBytes = maxSessionBytes;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.maxSessions = Math.max(1, maxSessions);
        this.maxTotalBytes = Math.max(1, maxTotalBytes);
        this.maxSessionsPerTenant = Math.max(1, maxSessionsPerTenant);
        this.maxTenantBytes = Math.max(1, maxTenantBytes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Setter-injected so the many test-built constructors stay unchanged. */
    @Autowired
    public void setCredentialService(ObjectProvider<E2bCredentialService> provider) {
        this.credentialService = provider;
    }

    /** Setter-injected for the same reason as {@link #setCredentialService}. */
    @Autowired
    public void setAccessResolver(ObjectProvider<E2bAccessResolver> provider) {
        this.accessResolver = provider;
    }

    /**
     * Setter-injected and optional for the same reason; only consulted when {@code store=redis}.
     */
    @Autowired(required = false)
    public void setRedisProvider(ObjectProvider<StringRedisTemplate> provider) {
        this.redisProvider = provider;
    }

    /** Test seam: use this mirror regardless of the store property. */
    void useMirror(SandboxFileRedisMirror mirror) {
        this.mirror = mirror;
    }

    /**
     * The Redis mirror when store=redis and a template exists, else null (per-JVM registry only).
     */
    private SandboxFileRedisMirror mirror() {
        SandboxFileRedisMirror ready = mirror;
        if (ready != null) {
            return ready;
        }
        if (!enabled || !"redis".equalsIgnoreCase(store == null ? "" : store.strip())) {
            return null;
        }
        ObjectProvider<StringRedisTemplate> provider = redisProvider;
        StringRedisTemplate template = provider == null ? null : provider.getIfAvailable();
        if (template == null) {
            return null;
        }
        synchronized (this) {
            if (mirror == null) {
                mirror =
                        new SandboxFileRedisMirror(
                                template,
                                Duration.ofMillis(Math.max(1L, ttlMillis)),
                                maxFilesPerSession,
                                maxTenantBytes,
                                maxTotalBytes);
            }
            return mirror;
        }
    }

    /**
     * THE single predicate for the data-analysis sandbox, used by the upload gate, the chat-turn
     * mounting gate and the modalities view so a file can never be accepted that cannot reach the
     * sandbox: platform flag on, the agent has Code execution attached, and (per tenant) an ACTIVE
     * E2B key with a code-execution template id. Fails closed if the tenant check cannot be made.
     * (Rebuilding the E2B template itself is an ops step this code cannot verify or perform.)
     */
    public boolean availableFor(boolean agentCodeExecutionEnabled) {
        return unavailableReason(agentCodeExecutionEnabled) == null;
    }

    /** Null when available; otherwise a short user-facing reason (what to fix). */
    public String unavailableReason(boolean agentCodeExecutionEnabled) {
        if (!enabled) {
            return "The data-analysis sandbox is not enabled on this platform.";
        }
        if (!agentCodeExecutionEnabled) {
            return "Turn on Code execution for this agent (Capabilities) to analyse attached files.";
        }
        var provider = credentialService;
        var resolverProvider = accessResolver;
        if (provider == null && resolverProvider == null) {
            return null; // not wired (unit-built): flag-only behaviour
        }
        try {
            E2bAccessResolver resolver =
                    resolverProvider == null ? null : resolverProvider.getIfAvailable();
            if (resolver != null) {
                // Tenant key first, else the platform-managed fallback when it is switched on.
                if (resolver.codeExecutionReady()) {
                    return null;
                }
                return resolver.platformFallbackEnabled()
                        ? REASON_NOT_AVAILABLE_YET
                        : REASON_CONNECT_E2B;
            }
            if (provider == null) {
                return REASON_CONNECT_E2B; // resolver bean absent and no credential probe: fail
                // closed
            }
            E2bCredentialService creds = provider.getIfAvailable();
            if (creds == null || !creds.isActive() || !creds.hasCodeExecutionTemplate()) {
                return REASON_CONNECT_E2B;
            }
            return null;
        } catch (RuntimeException e) {
            log.warn(
                    "Data-analysis sandbox tenant check failed - treating as unavailable: {}",
                    e.getMessage());
            return "File analysis is temporarily unavailable for this workspace.";
        }
    }

    /** True if {@code mimeType} (parameters ignored) is a data type this registry will mount. */
    public static boolean isMountable(String mimeType) {
        return extensionFor(mimeType) != null;
    }

    /**
     * Registers one file for the session. Returns false (never throws) for anything disabled,
     * empty, non-data, over the per-file cap, or beyond the per-session caps - a bad attachment can
     * never fail the chat turn. The reason is kept as a warning ({@link #drainWarnings}).
     */
    public synchronized boolean register(
            UUID agentId, String sessionId, String fileName, String mimeType, byte[] bytes) {
        return tryRegister(agentId, sessionId, fileName, mimeType, bytes) == null;
    }

    /**
     * As {@link #register} but returns null on success or a short human-readable reason on refusal,
     * and records the reason as a warning the next {@code run_code} result carries - so a dropped
     * file is never silent.
     */
    public synchronized String tryRegister(
            UUID agentId, String sessionId, String fileName, String mimeType, byte[] bytes) {
        if (!enabled || agentId == null || sessionId == null) {
            return "the data-analysis sandbox is unavailable";
        }
        String label =
                (fileName == null || fileName.isBlank())
                        ? "an attached file"
                        : "'" + fileName + "'";
        String reason = null;
        if (bytes == null || bytes.length == 0) {
            reason = label + " is empty";
        } else if (bytes.length > maxFileBytes) {
            reason = label + " exceeds the " + humanBytes(maxFileBytes) + " per-file limit";
        }
        String ext = extensionFor(mimeType);
        if (reason == null && ext == null) {
            reason = label + " is not a data type the sandbox can read";
        }
        long now = clock.getAsLong();
        String key = agentId + ":" + sessionId;
        if (reason == null) {
            evictExpired(now);
            String why = admit(key, now, ext, fileName, bytes);
            if (why != null) {
                reason = label + " " + why;
            }
        }
        if (reason != null) {
            warnings.add(key, now, "Not mounted: " + reason + ".");
        }
        return reason;
    }

    /** Null on success (file added), else the reason fragment. */
    private String admit(String key, long now, String ext, String fileName, byte[] bytes) {
        String tenant = currentTenant();
        SandboxSessionEntry entry = sessions.get(key);
        if (entry == null) {
            entry = newEntry(key, tenant);
        }
        if (entry.files.size() >= maxFilesPerSession) {
            return "was not added: this conversation already has "
                    + maxFilesPerSession
                    + " files mounted";
        }
        if (entry.totalBytes + bytes.length > maxSessionBytes) {
            return "was not added: it would exceed the "
                    + humanBytes(maxSessionBytes)
                    + " per-conversation limit";
        }
        String name =
                uniqueName(entry.files, sanitizeName(fileName, ext, entry.files.size() + 1));
        entry.files.add(new SandboxFile(name, bytes));
        SandboxFileRedisMirror shared = mirror();
        if (shared != null) {
            // Asynchronous: never waits on Redis while holding this monitor. If another replica
            // already put a file of
            // this name into the session's Redis hash, the writer picks the next free name and
            // renames the local copy.
            shared.putAsync(tenant, key, name, bytes, (from, to) -> renameLocal(key, from, to));
            entry.wroteRemote = true;
            entry.remoteBytes += bytes.length;
        }
        entry.totalBytes += bytes.length;
        entry.touchedAt = now;
        enforceByteBudgets(key, tenant);
        return null;
    }

    /**
     * Admits a NEW session: per-tenant then platform-wide session caps (evicting only as the caps
     * require), then registers an empty entry. Shared by {@link #admit} and {@link #adopt} so a
     * session adopted from Redis is held to the same session-count caps as one registered by an
     * upload.
     */
    private SandboxSessionEntry newEntry(String key, String tenant) {
        // Per-tenant session cap: evict only THIS tenant's oldest session.
        while (SandboxSessionEntry.countSessions(sessions.values(), tenant) >= maxSessionsPerTenant
                && evictOldestOf(tenant, null)) {
            // dropped one of this tenant's own sessions
        }
        // Platform-wide cap: the tenant holding the most sessions pays, so a quiet tenant is
        // never displaced by a busy one.
        while (sessions.size() >= maxSessions
                && evictOldestOf(
                        SandboxSessionEntry.heaviestTenantBySessions(sessions.values()), null)) {
            // dropped one session of the heaviest tenant
        }
        SandboxSessionEntry entry = new SandboxSessionEntry();
        entry.tenantId = tenant;
        sessions.put(key, entry);
        return entry;
    }

    /**
     * The Redis writer found the name taken (another replica's upload): make the local copy carry
     * the Redis name so every replica agrees on which file is which. Skipped if the session or the
     * old name is gone, or the new name is already used locally.
     */
    private synchronized void renameLocal(String key, String from, String to) {
        SandboxSessionEntry entry = sessions.get(key);
        if (entry == null) {
            return;
        }
        int index = -1;
        for (int i = 0; i < entry.files.size(); i++) {
            String held = entry.files.get(i).name();
            if (held.equals(to)) {
                return;
            }
            if (held.equals(from)) {
                index = i;
            }
        }
        if (index >= 0) {
            entry.files.set(index, new SandboxFile(to, entry.files.get(index).content()));
        }
    }

    private static String currentTenant() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .filter(t -> t != null && !t.isBlank())
                .orElse(NO_TENANT);
    }

    /**
     * Removes the oldest (insertion order) session of {@code tenant} other than {@code keepKey};
     * false if none.
     */
    private boolean evictOldestOf(String tenant, String keepKey) {
        if (tenant == null) {
            return false;
        }
        var it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            var next = it.next();
            if (tenant.equals(next.getValue().tenantId) && !next.getKey().equals(keepKey)) {
                it.remove();
                dropRemoteCopy(next.getKey(), next.getValue());
                return true;
            }
        }
        return false;
    }

    /**
     * A session evicted for capacity: delete the Redis hash this replica wrote for it (and give its
     * reserved bytes back) so Redis does not keep GBs the registry already dropped. Asynchronous;
     * only for sessions this replica wrote (a session merely adopted from another replica is that
     * replica's to delete). A TTL expiry is left to Redis.
     */
    private void dropRemoteCopy(String key, SandboxSessionEntry entry) {
        if (!entry.wroteRemote) {
            return;
        }
        SandboxFileRedisMirror shared = mirror();
        if (shared != null) {
            shared.deleteAsync(entry.tenantId, key, entry.remoteBytes);
        }
    }

    /**
     * Tenant byte cap first (own sessions only), then the platform total (heaviest tenant pays).
     */
    private void enforceByteBudgets(String keepKey, String tenant) {
        while (SandboxSessionEntry.tenantBytes(sessions.values(), tenant) > maxTenantBytes
                && evictOldestOf(tenant, keepKey)) {
            // dropped an older session of this tenant
        }
        while (SandboxSessionEntry.totalBytes(sessions.values()) > maxTotalBytes
                && evictOldestOf(
                        SandboxSessionEntry.heaviestTenantByBytes(sessions.values()), keepKey)) {
            // dropped an older session of the heaviest tenant
        }
    }

    /** Returns and clears the warnings recorded for this session (empty when none). */
    public synchronized List<String> drainWarnings(UUID agentId, String sessionId) {
        if (agentId == null || sessionId == null) {
            return List.of();
        }
        return warnings.drain(agentId + ":" + sessionId);
    }

    /**
     * Records a warning for a file that could not even be read (used by the chat-turn mounting
     * step).
     */
    public synchronized void noteWarning(UUID agentId, String sessionId, String message) {
        if (enabled && agentId != null && sessionId != null) {
            warnings.add(agentId + ":" + sessionId, clock.getAsLong(), message);
        }
    }

    /**
     * The session's mounted files (empty when none / expired / disabled). Refreshes the TTL. With
     * {@code store=redis} this also adopts any file the session has in Redis that this replica does
     * not hold yet (one tiny HKEYS round trip on the run_code path, no lock held during the Redis
     * call).
     */
    public List<SandboxFile> filesFor(UUID agentId, String sessionId) {
        List<SandboxFile> local = filesForLocal(agentId, sessionId);
        SandboxFileRedisMirror shared =
                enabled && agentId != null && sessionId != null ? mirror() : null;
        if (shared == null) {
            return local;
        }
        String key = agentId + ":" + sessionId;
        Set<String> have = new java.util.HashSet<>();
        for (SandboxFile file : local) {
            have.add(file.name());
        }
        List<SandboxFile> missing = shared.loadMissing(currentTenant(), key, have);
        if (missing == null || missing.isEmpty()) {
            return local;
        }
        return adopt(key, missing);
    }

    /**
     * Adds files read back from Redis to the local entry (per-session caps still apply) and returns
     * the full list.
     */
    private synchronized List<SandboxFile> adopt(String key, List<SandboxFile> remote) {
        long now = clock.getAsLong();
        String tenant = currentTenant();
        SandboxSessionEntry entry = sessions.get(key);
        if (entry == null) {
            entry = newEntry(key, tenant);
        }
        for (SandboxFile file : remote) {
            boolean present = false;
            for (SandboxFile held : entry.files) {
                if (held.name().equals(file.name())) {
                    present = true;
                    break;
                }
            }
            if (present
                    || entry.files.size() >= maxFilesPerSession
                    || entry.totalBytes + file.content().length > maxSessionBytes) {
                continue;
            }
            entry.files.add(file);
            entry.totalBytes += file.content().length;
        }
        entry.touchedAt = now;
        enforceByteBudgets(key, tenant);
        SandboxSessionEntry kept = sessions.get(key);
        return kept == null ? List.of() : List.copyOf(kept.files);
    }

    private synchronized List<SandboxFile> filesForLocal(UUID agentId, String sessionId) {
        if (!enabled || agentId == null || sessionId == null) {
            return List.of();
        }
        long now = clock.getAsLong();
        evictExpired(now);
        SandboxSessionEntry entry = sessions.get(agentId + ":" + sessionId);
        if (entry == null) {
            return List.of();
        }
        entry.touchedAt = now;
        return List.copyOf(entry.files);
    }

    private void evictExpired(long now) {
        sessions.values().removeIf(e -> now - e.touchedAt > ttlMillis);
        warnings.evictExpired(now, ttlMillis);
    }

    /** See {@link SandboxFileNames#sanitizeName}; kept here for existing callers and tests. */
    static String sanitizeName(String fileName, String ext, int ordinal) {
        return SandboxFileNames.sanitizeName(fileName, ext, ordinal);
    }

    /** See {@link SandboxFileNames#suffixed}; also used by the Redis writer. */
    static String suffixed(String candidate, int n) {
        return SandboxFileNames.suffixed(candidate, n);
    }
}
