package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant layer of the agent-runtime settings: read on every turn through a short cache, written by the
 * tenant admin. A write replaces the whole layer (a field left out goes back to the platform default), evicts
 * the cache of the replica that took it and leaves an audit entry naming the values.
 */
@Service
public class TenantRuntimeLimitsService {

    static final String ACTION_UPDATED = "AGENT_RUNTIME_LIMITS_UPDATED";
    static final String ACTION_CLEARED = "AGENT_RUNTIME_LIMITS_CLEARED";
    private static final Logger log = LoggerFactory.getLogger(TenantRuntimeLimitsService.class);

    private record Cached(RuntimeLimits limits, long expiresAtMillis) {}

    private final TenantRuntimeLimitsRepository repository;
    private final AuditLogService audit;
    private final AgentRuntimeProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public TenantRuntimeLimitsService(
            TenantRuntimeLimitsRepository repository, AuditLogService audit, AgentRuntimeProperties properties) {
        this(repository, audit, properties, Clock.systemUTC());
    }

    TenantRuntimeLimitsService(
            TenantRuntimeLimitsRepository repository,
            AuditLogService audit,
            AgentRuntimeProperties properties,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** The current tenant's defaults; empty (nothing overridden) outside a tenant. */
    public RuntimeLimits current() {
        Optional<String> tenantId = tenantId();
        if (tenantId.isEmpty()) {
            return RuntimeLimits.none();
        }
        long now = clock.millis();
        Cached cached = cache.get(tenantId.get());
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.limits();
        }
        RuntimeLimits loaded = repository.findById(TenantRuntimeLimitsRow.ONLY_ID)
                .map(TenantRuntimeLimitsRow::limits)
                .orElse(RuntimeLimits.none());
        Duration ttl = properties.getCacheTtl();
        cache.put(tenantId.get(), new Cached(loaded, now + ttl.toMillis()));
        return loaded;
    }

    /** Replaces the tenant's defaults; the caller has validated them. An empty value clears the layer. */
    @Transactional
    public RuntimeLimits replace(RuntimeLimits limits) {
        RuntimeLimits value = limits == null ? RuntimeLimits.none() : limits;
        TenantRuntimeLimitsRow row = repository.findById(TenantRuntimeLimitsRow.ONLY_ID).orElse(null);
        if (value.isEmpty()) {
            if (row != null) {
                repository.delete(row);
            }
            afterWrite(ACTION_CLEARED, "reset to the platform defaults");
            return RuntimeLimits.none();
        }
        if (row == null) {
            repository.save(new TenantRuntimeLimitsRow(value, actor()));
        } else {
            row.apply(value, actor());
            repository.save(row);
        }
        afterWrite(ACTION_UPDATED, value.toString());
        return value;
    }

    /** Drops the cached value of the current tenant. */
    void evictCurrentTenant() {
        tenantId().ifPresent(cache::remove);
    }

    private void afterWrite(String action, String detail) {
        evictCurrentTenant();
        log.info("Agent runtime limits {} by {} ({})", action, actor(), detail);
        try {
            audit.record(actor(), action, "agent_runtime_limits", "tenant", AuditOutcome.SUCCESS, detail);
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {} - the change itself stands", action, e);
        }
    }

    private static Optional<String> tenantId() {
        return TenantContext.current().map(TenantContext::getTenantId);
    }

    private static String actor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }
}
