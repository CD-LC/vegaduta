package com.agenticai.core.settings;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every change to a stored setting goes through here: scope and lock checks, the caller's role, validation by type,
 * bounds and enum, the typed reason for risky keys, the stricter-only rule for security keys, the new version, the
 * immutable history row, the audit entry (actor, scope, old value, new value, reason) and the cache eviction.
 *
 * <p>Registered by {@link SettingsConfiguration}.
 *
 * <p>Who may write what: TENANT scope needs a tenant admin of the calling thread's tenant; AGENT scope needs EDITOR on
 * the agent ({@link SettingsAgentAccess}) and, for a key whose {@code minRole} is TENANT_ADMIN (every security key),
 * a tenant admin too. A tenant can only reach its own rows: they live in that tenant's schema, and an agent id of
 * another tenant is simply not found.
 */
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    public static final String ACTION_UPDATED = "SETTING_UPDATED";
    public static final String ACTION_RESET = "SETTING_RESET";
    public static final String ACTION_REJECTED = "SETTING_CHANGE_REJECTED";
    public static final String AUDIT_TARGET = "setting";

    static final String ROLE_TENANT_ADMIN = "ROLE_tenant-admin";
    static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";

    /** What a change did; {@code version} is null for a no-op. */
    public record Applied(String key, SettingScope scope, Integer version, boolean unchanged, SettingValue effective) {}

    private final SettingsRegistry registry;
    private final SettingsStore store;
    private final List<SettingBackend> backends;
    private final DefaultSettingsResolver resolver;
    private final AuditLogService audit;
    private final ObjectProvider<SettingsAgentAccess> agentAccess;
    private final SettingsProperties properties;

    public SettingsService(
            SettingsRegistry registry,
            SettingsStore store,
            List<SettingBackend> backends,
            DefaultSettingsResolver resolver,
            AuditLogService audit,
            ObjectProvider<SettingsAgentAccess> agentAccess,
            SettingsProperties properties) {
        this.registry = registry;
        this.store = store;
        this.backends = backends == null ? List.of() : List.copyOf(backends);
        this.resolver = resolver;
        this.audit = audit;
        this.agentAccess = agentAccess;
        this.properties = properties;
    }

    public SettingsRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------------------------------ reads

    /** Every catalog key resolved for the current tenant, and for {@code agentId} when given (VIEWER on it). */
    public Map<String, SettingValue> effective(UUID agentId) {
        if (agentId != null) {
            access().requireViewer(agentId);
        }
        return resolver.resolveAll(SettingContext.forAgent(agentId));
    }

    @Transactional(readOnly = true)
    public List<SettingHistoryEntry> history(SettingScope scope, UUID agentId, String key, Integer limit) {
        SettingDefinition def = registry.require(key);
        authorize(def, scope, agentId, false);
        int cap = properties.getHistoryMaxLimit();
        int n = limit == null ? properties.getHistoryDefaultLimit() : Math.min(Math.max(1, limit), cap);
        return store.history(scope, scopeId(scope, agentId), def.key(), n);
    }

    /** One page of the bulk history ({@link #historyPage}): the rows, how many match in all, and where the page sits. */
    public record HistoryPage(List<SettingHistoryEntry> entries, long total, int offset, int limit) {

        public boolean hasMore() {
            return offset + entries.size() < total;
        }
    }

    /**
     * The history of every key at once, newest first, optionally only rows at or after {@code since}. Same authority as
     * the per-key {@link #history}: {@code scope} TENANT needs a tenant admin; AGENT with {@code agentId} needs EDITOR on
     * that agent (and an agent of another tenant is a 404); AGENT without an agent id is every agent's rows, and
     * {@code scope} null the whole workspace (tenant rows and every agent's) - both need a tenant admin, because they
     * show overrides of agents the caller may not edit.
     * Rows live in the caller's tenant schema, so another tenant's history is unreachable by construction.
     */
    @Transactional(readOnly = true)
    public HistoryPage historyPage(SettingScope scope, UUID agentId, Instant since, Integer limit, Integer offset) {
        TenantContext ctx = TenantContext.current().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "A workspace is required to read settings history."));
        String scopeId = null;
        if (scope == SettingScope.AGENT && agentId != null) {
            access().requireEditor(agentId);
            scopeId = agentId.toString();
        } else {
            if (agentId != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is only allowed with agent scope.");
            }
            // The workspace's own rows, every agent's rows, or both: all of them are the workspace admin's to read.
            requireTenantAdmin(ctx);
            if (scope == SettingScope.TENANT) {
                scopeId = "";
            }
        }
        int cap = properties.getBulkHistoryMaxLimit();
        int n = limit == null ? Math.min(properties.getBulkHistoryDefaultLimit(), cap) : Math.min(Math.max(1, limit), cap);
        int from = offset == null ? 0 : Math.min(Math.max(0, offset), 1_000_000_000);
        return new HistoryPage(store.historyPage(scope, scopeId, since, n, from), store.historyCount(scope, scopeId, since), from, n);
    }

    // ------------------------------------------------------------------------------------------ writes

    // noRollbackFor: the refused-weakening audit row (auditRejected) is written in this transaction before the
    // exception is thrown, and must survive it. Nothing else has been written when SettingInvalidException is thrown.
    @Transactional(noRollbackFor = SettingInvalidException.class)
    public Applied set(SettingScope scope, UUID agentId, String key, Object rawValue, String reason) {
        SettingDefinition def = registry.require(key);
        authorize(def, scope, agentId, true);
        checkWritable(def, scope);
        Object value = def.normalize(rawValue);
        String cleanReason = cleanReason(reason);
        if (def.risk().requiresReason() && cleanReason == null) {
            throw new SettingInvalidException(def.key(), "A reason is required to change this setting (" + def.risk().json() + " risk).");
        }
        SettingContext ctx = SettingContext.forAgent(agentId);
        Object inherited = resolver.resolveBelow(def, scope, ctx).value();
        if (!def.isAtLeastAsStrict(value, inherited)) {
            auditRejected(def, scope, agentId, value, inherited, cleanReason);
            throw new SettingInvalidException(
                    def.key(),
                    "This is a security setting: it can only be made stricter than the value it inherits ("
                            + inherited + "), never weaker.",
                    def.num(def.min()), def.num(def.max()), def.enumValues());
        }
        String scopeId = scopeId(scope, agentId);
        // The version read takes the key's write lock until commit, so read the current value only after it: a second
        // writer waits here and then sees the first one's result instead of a stale "before".
        int version = nextVersion(scope, scopeId, def.key());
        Object before = current(def, scope, agentId);
        if (before != null && Objects.equals(before, value)) {
            return new Applied(def.key(), scope, null, true, effectiveNow(def, agentId));
        }
        String actor = actor();
        Instant now = Instant.now();
        SettingBackend backend = backendFor(def.key());
        SettingHistoryEntry entry =
                new SettingHistoryEntry(0, scope, scopeId, def.key(), version, SettingHistoryEntry.ACTION_SET, value, before, cleanReason, actor, now);
        try {
            if (backend != null) {
                writeBackend(backend, scope, agentId, def.key(), value);
            } else {
                store.saveCurrent(new SettingOverride(scope, scopeId, def.key(), value, version, actor, now));
            }
            store.appendHistory(entry);
        } catch (DuplicateKeyException e) {
            throw conflict(def.key(), e);
        }
        afterWrite(ACTION_UPDATED, def, entry);
        return new Applied(def.key(), scope, version, false, effectiveNow(def, agentId));
    }

    @Transactional
    public Applied reset(SettingScope scope, UUID agentId, String key, String reason) {
        SettingDefinition def = registry.require(key);
        authorize(def, scope, agentId, true);
        checkWritable(def, scope);
        String cleanReason = cleanReason(reason);
        if (def.risk().requiresReason() && cleanReason == null) {
            throw new SettingInvalidException(def.key(), "A reason is required to reset this setting (" + def.risk().json() + " risk).");
        }
        String scopeId = scopeId(scope, agentId);
        int version = nextVersion(scope, scopeId, def.key());
        Object before = current(def, scope, agentId);
        if (before == null) {
            return new Applied(def.key(), scope, null, true, effectiveNow(def, agentId));
        }
        String actor = actor();
        Instant now = Instant.now();
        SettingBackend backend = backendFor(def.key());
        SettingHistoryEntry entry =
                new SettingHistoryEntry(0, scope, scopeId, def.key(), version, SettingHistoryEntry.ACTION_RESET, null, before, cleanReason, actor, now);
        try {
            if (backend != null) {
                writeBackend(backend, scope, agentId, def.key(), null);
            } else {
                store.deleteCurrent(scope, scopeId, def.key());
            }
            store.appendHistory(entry);
        } catch (DuplicateKeyException e) {
            throw conflict(def.key(), e);
        }
        afterWrite(ACTION_RESET, def, entry);
        return new Applied(def.key(), scope, version, false, effectiveNow(def, agentId));
    }

    /**
     * Records, in the immutable history, a change that was made through another door onto the same storage (the older
     * {@code /runtime-limits} endpoints write the keys of a {@link SettingBackend}), so the history of a key is
     * complete whichever API changed it. {@code before} and {@code after} map catalog keys to the override at that
     * scope (a missing key = not overridden). No validation, no audit entry: the caller already did both.
     */
    @Transactional
    public void recordExternalChange(
            SettingScope scope, UUID agentId, Map<String, Object> before, Map<String, Object> after, String source) {
        String scopeId = scopeId(scope, agentId);
        String actor = actor();
        Instant now = Instant.now();
        java.util.Set<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            SettingDefinition def = registry.find(key).orElse(null);
            if (def == null) {
                continue;
            }
            Object b = before.get(key) == null ? null : def.canonical(before.get(key));
            Object a = after.get(key) == null ? null : def.canonical(after.get(key));
            if (Objects.equals(a, b)) {
                continue;
            }
            int version = store.latestVersion(scope, scopeId, key) + 1;
            store.appendHistory(
                    new SettingHistoryEntry(
                            0, scope, scopeId, key, version,
                            a == null ? SettingHistoryEntry.ACTION_RESET : SettingHistoryEntry.ACTION_SET,
                            a, b, "changed through " + source, actor, now));
        }
        resolver.evictCurrentTenant();
    }

    // ------------------------------------------------------------------------------------------ internals

    /** The next version of the key at the scope; reading it also takes the store's per-key write lock until commit. */
    private int nextVersion(SettingScope scope, String scopeId, String key) {
        return store.latestVersion(scope, scopeId, key) + 1;
    }

    private static SettingConflictException conflict(String key, DuplicateKeyException cause) {
        return new SettingConflictException(
                key, "This setting was changed by someone else at the same moment. Reload it and try again.", cause);
    }

    private SettingValue effectiveNow(SettingDefinition def, UUID agentId) {
        // The write is visible to this thread's next read: the cache is evicted after commit, so bypass it for the answer.
        resolver.evictCurrentTenant();
        return resolver.get(def.key(), SettingContext.forAgent(agentId));
    }

    private void checkWritable(SettingDefinition def, SettingScope scope) {
        if (!def.allows(scope)) {
            throw new SettingInvalidException(def.key(), "This setting cannot be set at " + scope.json() + " level.");
        }
        if (properties.isLocked(def.key())) {
            throw new SettingLockedException(def.key(), properties.lockedReason(def.key()));
        }
    }

    private void authorize(SettingDefinition def, SettingScope scope, UUID agentId, boolean write) {
        TenantContext ctx = TenantContext.current().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "A workspace is required to change settings."));
        if (scope == SettingScope.TENANT) {
            requireTenantAdmin(ctx);
            return;
        }
        if (agentId == null) {
            throw new SettingInvalidException(def.key(), "agentId is required for agent scope.");
        }
        access().requireEditor(agentId);
        if (write) {
            access().requireEditable(agentId);
        }
        if (write && def.minRole() == SettingRole.TENANT_ADMIN) {
            requireTenantAdmin(ctx);
        }
    }

    private static void requireTenantAdmin(TenantContext ctx) {
        if (!ctx.hasRole(ROLE_TENANT_ADMIN) && !ctx.hasRole(ROLE_PLATFORM_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A tenant admin is required to change this setting.");
        }
    }

    private SettingsAgentAccess access() {
        SettingsAgentAccess a = agentAccess.getIfAvailable();
        if (a == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such agent in this workspace.");
        }
        return a;
    }

    private SettingBackend backendFor(String key) {
        for (SettingBackend b : backends) {
            if (b.keys().contains(key)) {
                return b;
            }
        }
        return null;
    }

    private void writeBackend(SettingBackend b, SettingScope scope, UUID agentId, String key, Object value) {
        if (scope == SettingScope.TENANT) {
            b.writeTenant(key, value);
        } else {
            b.writeAgent(agentId, key, value);
        }
    }

    /** The canonical override stored at the scope, or null. */
    private Object current(SettingDefinition def, SettingScope scope, UUID agentId) {
        SettingBackend backend = backendFor(def.key());
        Object raw;
        if (backend != null) {
            raw = (scope == SettingScope.TENANT ? backend.tenantValues() : backend.agentValues(agentId)).get(def.key());
        } else {
            raw = store.find(scope, scopeId(scope, agentId), def.key()).map(SettingOverride::value).orElse(null);
        }
        return raw == null ? null : def.canonical(raw);
    }

    private static String scopeId(SettingScope scope, UUID agentId) {
        return scope == SettingScope.AGENT ? String.valueOf(agentId) : "";
    }

    private String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String r = reason.strip();
        return r.length() > properties.getReasonMaxLength() ? r.substring(0, properties.getReasonMaxLength()) : r;
    }

    private void afterWrite(String action, SettingDefinition def, SettingHistoryEntry e) {
        runAfterCommit(resolver::evictCurrentTenant);
        resolver.evictCurrentTenant();
        log.info("Setting {} {} at {} scope by {} (v{})", def.key(), action, e.scope().json(), e.actor(), e.version());
        try {
            audit.record(e.actor(), action, AUDIT_TARGET, def.key(), AuditOutcome.SUCCESS, auditDetail(e));
        } catch (RuntimeException ex) {
            log.warn("Audit write failed for {} on {} - the change itself stands", action, def.key(), ex);
        }
    }

    private void auditRejected(SettingDefinition def, SettingScope scope, UUID agentId, Object attempted, Object inherited, String reason) {
        try {
            audit.record(
                    actor(),
                    ACTION_REJECTED,
                    AUDIT_TARGET,
                    def.key(),
                    AuditOutcome.DENIED,
                    "key=" + def.key() + " scope=" + scope.json() + (agentId == null ? "" : " scopeId=" + agentId)
                            + " attempted=" + attempted + " inherited=" + inherited + " reason=" + reason
                            + " (a security setting cannot be made weaker)");
        } catch (RuntimeException ex) {
            log.warn("Audit write failed for a rejected change of {}", def.key(), ex);
        }
    }

    /** Who, scope, key, version, old and new value, reason. */
    static String auditDetail(SettingHistoryEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("key=").append(e.key());
        sb.append(" scope=").append(e.scope().json());
        if (!e.scopeId().isEmpty()) {
            sb.append(" scopeId=").append(e.scopeId());
        }
        sb.append(" action=").append(e.action());
        sb.append(" version=").append(e.version());
        sb.append(" old=").append(e.previousValue() == null ? "<inherited>" : e.previousValue());
        sb.append(" new=").append(e.value() == null ? "<inherited>" : e.value());
        if (e.reason() != null) {
            sb.append(" reason=").append(e.reason());
        }
        return sb.toString();
    }

    private static void runAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            r.run();
                        }
                    });
        }
    }

    private static String actor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }
}
