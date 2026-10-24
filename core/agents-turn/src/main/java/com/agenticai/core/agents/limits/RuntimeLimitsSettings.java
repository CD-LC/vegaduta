package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.runtime.RuntimeSetting;
import com.agenticai.core.settings.SettingBackend;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The seven runtime limits ({@link RuntimeSetting}) in the settings catalog as {@code agent.limits.<apiName>}. The
 * catalog entries and the older {@code /api/agents/{id}/runtime-limits} and {@code /api/agent-runtime/limits}
 * endpoints read and write the SAME storage ({@code tenant_runtime_limits} and the {@code agent} table, through
 * {@link TenantRuntimeLimitsService} and {@link AgentService}), so the two views cannot disagree. Bounds come from
 * {@code app.agent-runtime.bounds.*}, platform values from {@link AgentRuntimeSettingsResolver#platform}. The runtime
 * itself keeps reading {@link AgentRuntimeSettingsResolver}; nothing about enforcement changes.
 *
 * <p>Dependencies are looked up lazily: the settings registry is built early and must not pull the agent service
 * into a construction cycle.
 */
@Component
public class RuntimeLimitsSettings implements SettingsContributor, SettingBackend {

    static final String PREFIX = "agent.limits.";
    private static final String GROUP = "agent-limits";

    private final AgentRuntimeProperties properties;
    private final ObjectProvider<AgentRuntimeSettingsResolver> runtime;
    private final ObjectProvider<TenantRuntimeLimitsService> tenantLimits;
    private final ObjectProvider<AgentService> agents;

    public RuntimeLimitsSettings(
            AgentRuntimeProperties properties,
            ObjectProvider<AgentRuntimeSettingsResolver> runtime,
            ObjectProvider<TenantRuntimeLimitsService> tenantLimits,
            ObjectProvider<AgentService> agents) {
        this.properties = properties;
        this.runtime = runtime;
        this.tenantLimits = tenantLimits;
        this.agents = agents;
    }

    static String key(RuntimeSetting setting) {
        return PREFIX + setting.apiName();
    }

    private AgentRuntimeSettings platform() {
        return runtime.getObject().platform(AgentAutonomyTier.SEMI_AUTONOMOUS);
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                def(RuntimeSetting.MAX_TOOL_STEPS, SettingType.INT, SettingUnit.COUNT, SettingRisk.MEDIUM,
                        AgentRuntimeSettings::maxToolSteps, "app.guardrails.max-tool-calls-per-turn"),
                def(RuntimeSetting.TURN_TIMEOUT_SECONDS, SettingType.DURATION, SettingUnit.S, SettingRisk.MEDIUM,
                        AgentRuntimeSettings::turnTimeout, "app.llm.http.buffered-turn-timeout"),
                def(RuntimeSetting.TOOL_TIMEOUT_SECONDS, SettingType.DURATION, SettingUnit.S, SettingRisk.LOW,
                        AgentRuntimeSettings::toolTimeout, "app.agent-runtime.defaults.tool-timeout-seconds"),
                def(RuntimeSetting.TOOL_OUTPUT_MAX_CHARS, SettingType.INT, SettingUnit.CHARS, SettingRisk.MEDIUM,
                        AgentRuntimeSettings::toolOutputMaxChars, "app.tools.output.max-chars"),
                def(RuntimeSetting.RETRIEVAL_TOP_K, SettingType.INT, SettingUnit.COUNT, SettingRisk.LOW,
                        AgentRuntimeSettings::retrievalTopK, "app.agent-runtime.defaults.retrieval-top-k"),
                def(RuntimeSetting.RETRIEVAL_MIN_SCORE, SettingType.DECIMAL, null, SettingRisk.LOW,
                        AgentRuntimeSettings::retrievalMinScore, "app.agent-runtime.defaults.retrieval-min-score"),
                def(RuntimeSetting.CONTINUATION_PASSES, SettingType.INT, SettingUnit.COUNT, SettingRisk.LOW,
                        AgentRuntimeSettings::continuationPasses, "app.chat.continuation.max-passes"));
    }

    private SettingDefinition def(
            RuntimeSetting setting,
            SettingType type,
            SettingUnit unit,
            SettingRisk risk,
            Function<AgentRuntimeSettings, Object> platformValue,
            String property) {
        AgentRuntimeProperties.Range range = properties.getBounds().of(setting);
        SettingDefinition.Builder b =
                SettingDefinition.builder(key(setting), GROUP, type)
                        .range(range.getMin(), range.getMax())
                        .risk(risk)
                        .scopes(SettingScope.TENANT, SettingScope.AGENT)
                        .platform(() -> platformValue.apply(platform()))
                        .property(property)
                        .docsAnchor("agent-runtime-limits");
        if (unit != null) {
            b.unit(unit);
        }
        if (type == SettingType.DECIMAL) {
            b.step(0.05);
        }
        return b.build();
    }

    // ------------------------------------------------------------------------------------------ storage

    @Override
    public Set<String> keys() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (RuntimeSetting s : RuntimeSetting.values()) {
            keys.add(key(s));
        }
        return keys;
    }

    @Override
    public Map<String, Object> tenantValues() {
        return toMap(tenantLimits.getObject().current());
    }

    @Override
    public Map<String, Object> agentValues(UUID agentId) {
        return toMap(agents.getObject().getRuntimeLimits(agentId));
    }

    @Override
    public void writeTenant(String key, Object value) {
        TenantRuntimeLimitsService service = tenantLimits.getObject();
        service.replace(service.current().with(setting(key), (Number) value));
    }

    @Override
    public void writeAgent(UUID agentId, String key, Object value) {
        AgentService service = agents.getObject();
        service.setRuntimeLimits(agentId, service.getRuntimeLimits(agentId).with(setting(key), (Number) value));
    }

    private static RuntimeSetting setting(String key) {
        for (RuntimeSetting s : RuntimeSetting.values()) {
            if (key(s).equals(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Not a runtime limit: " + key);
    }

    private static Map<String, Object> toMap(RuntimeLimits limits) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (limits == null) {
            return out;
        }
        for (RuntimeSetting s : RuntimeSetting.values()) {
            Number n = limits.get(s);
            if (n != null) {
                out.put(key(s), n);
            }
        }
        return out;
    }
}
