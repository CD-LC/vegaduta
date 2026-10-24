package com.agenticai.core.agents.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingValue;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * With no override, the value a use site gets from the catalog IS the value of the property object it used to read:
 * wiring a key must change nothing until somebody sets it. The properties are moved off their defaults first, so a catalog
 * that quietly answered a default of its own would fail here.
 */
class BehaviourSettingsGoldenTest {

    private static final UUID AGENT = UUID.randomUUID();

    private final BehaviourCatalogFixture f = new BehaviourCatalogFixture();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void expect(Object expected, String key) {
        TenantContext.set(TenantContext.of("tenant-a", "u", Set.of()));
        for (SettingContext ctx : new SettingContext[] {SettingContext.tenant(), SettingContext.forAgent(AGENT)}) {
            SettingValue resolved = f.resolver().get(key, ctx);
            Object value = resolved.value();
            if (expected instanceof Duration d) {
                value = f.resolver().getDuration(key, ctx);
                assertEquals(d, value, key);
            } else if (expected instanceof Number n) {
                // The catalog carries whole numbers as long; the property object as int.
                assertEquals(n.longValue(), ((Number) value).longValue(), key + " " + ctx);
            } else {
                assertEquals(expected, value, key + " " + ctx);
            }
        }
    }

    @Test
    void everyWiredKeyAnswersThePropertyItReplaced() {
        f.approvalContinuation.setEnabled(!f.approvalContinuation.isEnabled());
        f.finish.setEnabled(!f.finish.isEnabled());
        f.finish.setMaxContinuations(7);
        f.finish.setMaxToolCallRetries(3);
        f.retry.setMaxAttempts(5);
        f.cache.setEligibleWithTools(!f.cache.isEligibleWithTools());
        f.cache.setContextTurns(4);
        f.followUp.setStickyToolsEnabled(!f.followUp.isStickyToolsEnabled());
        f.followUp.setStickyToolsWindow(Duration.ofMinutes(7));
        f.continuation.setHeuristicEnabled(!f.continuation.isHeuristicEnabled());
        f.toolLoop.getResultClearing().setEnabled(!f.toolLoop.getResultClearing().isEnabled());
        f.toolLoop.getResultClearing().setThresholdChars(12_345);
        f.routing.setMaxChars(321);
        f.routing.setMaxClauses(4);
        f.memory.setAgentRecallTopK(11);
        f.memory.setTenantRecallTopK(12);
        f.memory.setAgentMaxMemories(77);
        f.auxiliary.setUseAgentModel(!f.auxiliary.isUseAgentModel());

        expect(f.approvalContinuation.isEnabled(), "approvals.continuation.enabled");
        expect(f.finish.isEnabled(), "chat.finish.enabled");
        expect(7, "chat.finish.maxContinuations");
        expect(3, "chat.finish.maxToolCallRetries");
        expect(5, "chat.retry.maxAttempts");
        expect(f.cache.isEligibleWithTools(), "chat.cache.eligibleWithTools");
        expect(4, "chat.cache.contextTurns");
        expect(f.followUp.isStickyToolsEnabled(), "chat.followUp.stickyToolsEnabled");
        expect(Duration.ofMinutes(7), "chat.followUp.stickyToolsWindow");
        expect(f.continuation.isHeuristicEnabled(), "chat.continuation.heuristicEnabled");
        expect(f.toolLoop.getResultClearing().isEnabled(), "chat.toolLoop.resultClearing.enabled");
        expect(12_345, "chat.toolLoop.resultClearing.thresholdChars");
        expect(321, "routing.simple.maxChars");
        expect(4, "routing.simple.maxClauses");
        expect(11, "memory.recall.agentTopK");
        expect(12, "memory.recall.tenantTopK");
        expect(77, "memory.agent.maxMemories");
        expect(f.auxiliary.isUseAgentModel(), "aux.useAgentModel");
    }
}
