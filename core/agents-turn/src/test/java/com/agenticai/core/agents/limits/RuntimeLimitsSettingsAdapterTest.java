package com.agenticai.core.agents.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingInvalidException;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingSource;
import com.agenticai.core.settings.SettingValue;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The seven {@code agent.limits.*} catalog keys and the older {@code /runtime-limits} endpoints are two views of the
 * same storage: a write through either is seen by the other and by the runtime resolver, and the catalog's bounds and
 * platform values are the runtime's own.
 */
class RuntimeLimitsSettingsAdapterTest {

    private static final UUID AGENT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final AtomicReference<RuntimeLimits> tenantStore = new AtomicReference<>(RuntimeLimits.none());
    private final AtomicReference<RuntimeLimits> agentStore = new AtomicReference<>(RuntimeLimits.none());
    private final AgentService agents = mock(AgentService.class);
    private final TenantRuntimeLimitsService tenantService = mock(TenantRuntimeLimitsService.class);
    private final AgentRuntimeProperties properties = new AgentRuntimeProperties();
    private AgentRuntimeSettingsResolver runtime;
    private SettingsService settings;
    private DefaultSettingsResolver catalog;
    private AgentRuntimeLimitsController agentEndpoint;
    private TenantRuntimeLimitsController tenantEndpoint;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("t1", "alice", Set.of("ROLE_tenant-admin")));
        when(tenantService.current()).thenAnswer(i -> tenantStore.get());
        when(tenantService.replace(any())).thenAnswer(i -> {
            RuntimeLimits l = i.getArgument(0);
            tenantStore.set(l == null ? RuntimeLimits.none() : l);
            return tenantStore.get();
        });
        when(agents.getRuntimeLimits(AGENT)).thenAnswer(i -> agentStore.get());
        when(agents.setRuntimeLimits(any(), any())).thenAnswer(i -> {
            RuntimeLimits l = i.getArgument(1);
            agentStore.set(l == null ? RuntimeLimits.none() : l);
            return null;
        });
        runtime = new AgentRuntimeSettingsResolver(
                properties,
                new AgentRuntimeSettingsResolver.PlatformKeys(15, Duration.ofSeconds(240), 8),
                new ToolStepLimitProperties(), new ToolOutputProperties(), new ToolLoopProperties(), tenantService, agents);
        ObjectProvider<AgentRuntimeSettingsResolver> rp = mock(ObjectProvider.class);
        when(rp.getObject()).thenReturn(runtime);
        ObjectProvider<TenantRuntimeLimitsService> tp = mock(ObjectProvider.class);
        when(tp.getObject()).thenReturn(tenantService);
        ObjectProvider<AgentService> ap = mock(ObjectProvider.class);
        when(ap.getObject()).thenReturn(agents);
        RuntimeLimitsSettings limits = new RuntimeLimitsSettings(properties, rp, tp, ap);

        SettingsProperties sp = new SettingsProperties();
        SettingsRegistry registry = new SettingsRegistry(List.of(limits));
        InMemorySettingsStore store = new InMemorySettingsStore();
        catalog = new DefaultSettingsResolver(registry, store, List.of(limits), sp, null);
        ObjectProvider<com.agenticai.core.settings.SettingsAgentAccess> access = mock(ObjectProvider.class);
        when(access.getIfAvailable()).thenReturn(new AgentServiceSettingsAccess(agents));
        settings = new SettingsService(registry, store, List.of(limits), catalog, mock(AuditLogService.class), access, sp);
        ObjectProvider<SettingsService> sv = mock(ObjectProvider.class);
        when(sv.getIfAvailable()).thenReturn(settings);
        RuntimeLimitsHistory history = new RuntimeLimitsHistory(sv);
        agentEndpoint = new AgentRuntimeLimitsController(agents, runtime, history);
        tenantEndpoint = new TenantRuntimeLimitsController(tenantService, runtime, history);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SettingValue value(String key) {
        catalog.evictCurrentTenant();
        return catalog.get(key, SettingContext.forAgent(AGENT));
    }

    @Test
    void sevenKeysWithTheRuntimesBoundsAndPlatformValues() {
        var defs = settings.registry().all();
        assertThat(defs).extracting(d -> d.key()).containsExactly(
                "agent.limits.maxToolSteps", "agent.limits.turnTimeoutSeconds", "agent.limits.toolTimeoutSeconds",
                "agent.limits.toolOutputMaxChars", "agent.limits.retrievalTopK", "agent.limits.retrievalMinScore",
                "agent.limits.continuationPasses");
        var steps = settings.registry().require("agent.limits.maxToolSteps");
        assertThat(steps.min()).isEqualTo(properties.getBounds().getMaxToolSteps().getMin());
        assertThat(steps.max()).isEqualTo(properties.getBounds().getMaxToolSteps().getMax());
        assertThat(steps.platformValue()).isEqualTo(15L);
        assertThat(settings.registry().require("agent.limits.turnTimeoutSeconds").platformValue()).isEqualTo(240L);
        assertThat(settings.registry().require("agent.limits.toolOutputMaxChars").platformValue())
                .isEqualTo((long) runtime.platform(null).toolOutputMaxChars());
        assertThat(settings.registry().require("agent.limits.retrievalMinScore").platformValue()).isEqualTo(0.0);
        for (var d : defs) {
            assertThat(d.scopes()).containsExactly(SettingScope.TENANT, SettingScope.AGENT);
        }
    }

    @Test
    void aCatalogWriteIsVisibleThroughTheOlderEndpointsAndTheRuntime() {
        settings.set(SettingScope.AGENT, AGENT, "agent.limits.maxToolSteps", 40, null);
        settings.set(SettingScope.AGENT, AGENT, "agent.limits.retrievalMinScore", 0.4, null);
        settings.set(SettingScope.TENANT, null, "agent.limits.turnTimeoutSeconds", 600, null);

        RuntimeLimitsView agentView = agentEndpoint.get(AGENT);
        assertThat(agentView.overrides().getMaxToolSteps()).isEqualTo(40);
        assertThat(agentView.overrides().getRetrievalMinScore()).isEqualTo(0.4);
        assertThat(tenantEndpoint.get().overrides().getTurnTimeoutSeconds()).isEqualTo(600);

        var effective = runtime.forAgent(AGENT);
        assertThat(effective.maxToolSteps()).isEqualTo(40);
        assertThat(effective.turnTimeout()).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void aWriteThroughTheOlderEndpointsIsVisibleInTheCatalog() {
        agentEndpoint.put(AGENT, new RuntimeLimits(25, null, null, 4000, null, null, null));
        tenantEndpoint.put(new RuntimeLimits(null, 900, null, null, null, null, 2));

        SettingValue steps = value("agent.limits.maxToolSteps");
        assertThat(steps.value()).isEqualTo(25L);
        assertThat(steps.source()).isEqualTo(SettingSource.AGENT);
        assertThat(value("agent.limits.toolOutputMaxChars").value()).isEqualTo(4000L);
        SettingValue turn = value("agent.limits.turnTimeoutSeconds");
        assertThat(turn.value()).isEqualTo(900L);
        assertThat(turn.source()).isEqualTo(SettingSource.TENANT);
        assertThat(value("agent.limits.continuationPasses").value()).isEqualTo(2L);
        assertThat(value("agent.limits.retrievalTopK").source()).isNotIn(SettingSource.TENANT, SettingSource.AGENT);
    }

    @Test
    void resettingOneKeyLeavesTheOthersOfTheSameRow() {
        settings.set(SettingScope.AGENT, AGENT, "agent.limits.maxToolSteps", 40, null);
        settings.set(SettingScope.AGENT, AGENT, "agent.limits.retrievalTopK", 7, null);
        settings.reset(SettingScope.AGENT, AGENT, "agent.limits.maxToolSteps", "back to inherited");
        RuntimeLimits now = agentEndpoint.get(AGENT).overrides();
        assertThat(now.getMaxToolSteps()).isNull();
        assertThat(now.getRetrievalTopK()).isEqualTo(7);
        // clearing the last one clears the row
        settings.reset(SettingScope.AGENT, AGENT, "agent.limits.retrievalTopK", null);
        assertThat(agentStore.get().isEmpty()).isTrue();
    }

    @Test
    void outOfBoundsIsRefusedWithTheRuntimesRangeAndNothingIsStored() {
        assertThatThrownBy(() -> settings.set(SettingScope.AGENT, AGENT, "agent.limits.maxToolSteps", 1_000_000, null))
                .isInstanceOfSatisfying(SettingInvalidException.class, e -> {
                    assertThat(e.min()).isEqualTo(1L);
                    assertThat(e.max()).isEqualTo(500L);
                });
        assertThatThrownBy(() -> settings.set(SettingScope.TENANT, null, "agent.limits.retrievalMinScore", 1.5, null))
                .isInstanceOf(SettingInvalidException.class);
        assertThat(agentStore.get().isEmpty()).isTrue();
        assertThat(tenantStore.get().isEmpty()).isTrue();
    }

    @Test
    void aWriteThroughTheOlderEndpointsIsInTheCatalogHistoryToo() {
        agentEndpoint.put(AGENT, new RuntimeLimits(25, null, null, null, 5, null, null));
        agentEndpoint.put(AGENT, new RuntimeLimits(30, null, null, null, 5, null, null));
        agentEndpoint.put(AGENT, RuntimeLimits.none());

        var steps = settings.history(SettingScope.AGENT, AGENT, "agent.limits.maxToolSteps", null);
        assertThat(steps).extracting(h -> h.version()).containsExactly(3, 2, 1);
        assertThat(steps).extracting(h -> h.action()).containsExactly("RESET", "SET", "SET");
        assertThat(steps.get(1).previousValue()).isEqualTo(25L);
        assertThat(steps.get(0).reason()).contains("/api/agents/{id}/runtime-limits");
        // top-k was 5 then cleared: set once, reset once; an unchanged value adds no row
        assertThat(settings.history(SettingScope.AGENT, AGENT, "agent.limits.retrievalTopK", null))
                .extracting(h -> h.action()).containsExactly("RESET", "SET");

        tenantEndpoint.put(new RuntimeLimits(null, 900, null, null, null, null, null));
        tenantEndpoint.clear();
        assertThat(settings.history(SettingScope.TENANT, null, "agent.limits.turnTimeoutSeconds", null))
                .extracting(h -> h.action()).containsExactly("RESET", "SET");
    }
}
