package com.agenticai.core.agents.memorystore;

import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Platform AND tenant AND agent: unified memory is on for an agent only if every level says so
 * (ADR-0028 section 11). The platform level is {@code app.memory.unified.enabled}, default false;
 * the lower levels are {@link MemoryFlagOverride} beans, any of which can veto. A level that throws
 * counts as off (fail closed).
 */
public class MemoryUnifiedGate {

    private static final Logger log = LoggerFactory.getLogger(MemoryUnifiedGate.class);

    private final MemoryUnifiedProperties properties;
    private final ObjectProvider<MemoryFlagOverride> overrides;

    public MemoryUnifiedGate(
            MemoryUnifiedProperties properties, ObjectProvider<MemoryFlagOverride> overrides) {
        this.properties = properties;
        this.overrides = overrides;
    }

    public boolean enabled(UUID agentId) {
        if (!properties.isEnabled()) {
            return false;
        }
        return safe(() -> overrides.orderedStream().allMatch(o -> o.allows(agentId)));
    }

    private static boolean safe(Supplier<Boolean> check) {
        try {
            return Boolean.TRUE.equals(check.get());
        } catch (RuntimeException e) {
            log.warn("Unified memory flag level unreadable; treating as off", e);
            return false;
        }
    }
}
