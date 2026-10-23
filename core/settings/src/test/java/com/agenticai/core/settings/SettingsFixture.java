package com.agenticai.core.settings;

import static org.mockito.Mockito.mock;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** A small catalog, an in-memory store, a map-backed backend and fake agent access, wired like production. */
class SettingsFixture {

    static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID OTHER_TENANT_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    final AtomicReference<Object> steps = new AtomicReference<>(15);
    final AtomicReference<Object> failMode = new AtomicReference<>("closed");
    final AtomicReference<Object> blockScore = new AtomicReference<>(60);
    final AtomicReference<Object> retries = new AtomicReference<>(2);
    final SettingsProperties properties = new SettingsProperties();
    final MockEnvironment environment = new MockEnvironment();
    final InMemorySettingsStore store = new InMemorySettingsStore();
    final MapBackend backend = new MapBackend();
    final AuditLogService audit = mock(AuditLogService.class);
    final Set<UUID> agentsOfTenantA = Set.of(AGENT);
    final Set<UUID> lockedAgents = new java.util.HashSet<>();
    final Set<UUID> editorOn = new java.util.HashSet<>(Set.of(AGENT));
    final SettingsRegistry registry;
    final DefaultSettingsResolver resolver;
    final SettingsService service;
    long now = 1_000_000L;

    /** Overrides of the keys a backend owns, kept per tenant like the real tables. */
    static class MapBackend implements SettingBackend {
        final Map<String, Map<String, Object>> tenant = new HashMap<>();
        final Map<String, Map<UUID, Map<String, Object>>> agent = new HashMap<>();

        static String t() {
            return TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        }

        @Override
        public Set<String> keys() {
            return Set.of("agent.limits.maxToolSteps");
        }

        @Override
        public Map<String, Object> tenantValues() {
            return new HashMap<>(tenant.getOrDefault(t(), Map.of()));
        }

        @Override
        public Map<String, Object> agentValues(UUID agentId) {
            return new HashMap<>(agent.getOrDefault(t(), Map.of()).getOrDefault(agentId, Map.of()));
        }

        @Override
        public void writeTenant(String key, Object value) {
            Map<String, Object> m = tenant.computeIfAbsent(t(), k -> new HashMap<>());
            if (value == null) m.remove(key); else m.put(key, value);
        }

        @Override
        public void writeAgent(UUID agentId, String key, Object value) {
            Map<String, Object> m = agent.computeIfAbsent(t(), k -> new HashMap<>()).computeIfAbsent(agentId, k -> new HashMap<>());
            if (value == null) m.remove(key); else m.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    SettingsFixture() {
        properties.setCacheTtl(Duration.ofSeconds(10));
        SettingsContributor contributor =
                () -> List.of(
                        SettingDefinition.builder("agent.limits.maxToolSteps", "agent-limits", SettingType.INT)
                                .unit(SettingUnit.COUNT).range(1, 500).risk(SettingRisk.MEDIUM)
                                .scopes(SettingScope.TENANT, SettingScope.AGENT)
                                .platform(steps::get).property("app.guardrails.max-tool-calls-per-turn").build(),
                        SettingDefinition.builder("chat.retry.maxAttempts", "chat", SettingType.INT)
                                .unit(SettingUnit.COUNT).range(1, 6).risk(SettingRisk.LOW)
                                .scopes(SettingScope.TENANT).platform(retries::get).build(),
                        SettingDefinition.builder("guardrails.failMode", "guardrails", SettingType.ENUM)
                                .enumValues("open", "closed").risk(SettingRisk.SECURITY)
                                .strictness(Strictness.ENUM_ORDER)
                                .scopes(SettingScope.TENANT, SettingScope.AGENT)
                                .platform(failMode::get).build(),
                        SettingDefinition.builder("guardrails.toxicity.blockScore", "guardrails", SettingType.INT)
                                .range(0, 100).risk(SettingRisk.SECURITY).strictness(Strictness.LOWER_IS_STRICTER)
                                .scopes(SettingScope.TENANT).platform(blockScore::get).build(),
                        SettingDefinition.builder("approvals.expiry.ttl", "approvals", SettingType.DURATION)
                                .unit(SettingUnit.S).range(0, 604800).risk(SettingRisk.HIGH)
                                .scopes(SettingScope.TENANT).platform(() -> Duration.ZERO).build(),
                        SettingDefinition.builder("chat.cache.eligibleWithTools", "chat", SettingType.BOOL)
                                .risk(SettingRisk.MEDIUM).scopes(SettingScope.TENANT, SettingScope.AGENT)
                                .platform(() -> false).build(),
                        SettingDefinition.builder("quality.judge.model", "quality", SettingType.STRING)
                                .max(100).risk(SettingRisk.LOW).scopes(SettingScope.TENANT).platform(() -> "").build(),
                        SettingDefinition.builder("agent.limits.retrievalMinScore", "agent-limits", SettingType.DECIMAL)
                                .range(0, 1).step(0.05).scopes(SettingScope.TENANT, SettingScope.AGENT)
                                .platform(() -> 0.0).build());
        registry = new SettingsRegistry(List.of(contributor));
        resolver = new DefaultSettingsResolver(registry, store, List.of(backend), properties, environment, () -> now);
        ObjectProvider<SettingsAgentAccess> access = mock(ObjectProvider.class);
        org.mockito.Mockito.when(access.getIfAvailable()).thenReturn(new SettingsAgentAccess() {
            @Override
            public void requireViewer(UUID agentId) {
                if (!agentsOfTenantA.contains(agentId) && !agentsOfTenantB.contains(agentId)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such agent");
                }
                check(agentId);
            }

            @Override
            public void requireEditor(UUID agentId) {
                requireViewer(agentId);
                if (!editorOn.contains(agentId)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an editor");
                }
            }

            @Override
            public void requireEditable(UUID agentId) {
                if (lockedAgents.contains(agentId)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent is edit-locked");
                }
            }

            private void check(UUID agentId) {
                String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("");
                boolean mine = tenant.equals("tenant-a") ? agentsOfTenantA.contains(agentId) : agentsOfTenantB.contains(agentId);
                if (!mine) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such agent");
                }
            }
        });
        service = new SettingsService(registry, store, List.of(backend), resolver, audit, access, properties);
    }

    final Set<UUID> agentsOfTenantB = Set.of(OTHER_TENANT_AGENT);

    static void as(String tenant, String subject, String... roles) {
        TenantContext.set(TenantContext.of(tenant, subject, Set.of(roles)));
    }

    static void asAdmin() {
        as("tenant-a", "alice", "ROLE_tenant-admin");
    }

    static void asEditor() {
        as("tenant-a", "erin", "ROLE_agent-builder");
    }
}
