package com.agenticai.core.tenancy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Tenant schemas whose startup migration failed in this JVM. {@link TenantStatusGuardImpl} treats
 * them as not usable (a stale schema serves wrong or 500ing queries) until a later successful
 * migration of the same schema clears the entry - see {@link TenantMigrationService}, which records
 * and clears, and {@link TenantMigrationRetryJob}, which retries.
 */
@Component
public class TenantMigrationFailures {

    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public void recordFailure(String schema) {
        if (schema != null) {
            failed.add(schema);
        }
    }

    public void recordSuccess(String schema) {
        if (schema != null) {
            failed.remove(schema);
        }
    }

    public boolean hasFailed(String schema) {
        return schema != null && !failed.isEmpty() && failed.contains(schema);
    }

    public Set<String> snapshot() {
        return Set.copyOf(failed);
    }
}
