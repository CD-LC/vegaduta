package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.McpAnnotationClassifier;
import com.agenticai.core.agents.tools.classification.McpToolHints;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.guardrails.taint.TaintGate;
import com.agenticai.core.guardrails.taint.ToolTaintProfile;
import com.agenticai.core.audit.AuditLogService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Classification rules behind the taint layer, checked against the real built-in declarations. */
class AgentTaintPolicyTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** A registry answering from the compiled-in declarations (and the MCP annotation mapper). */
    private static ToolClassificationRegistry builtInRegistry() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        when(registry.resolve(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            ToolClassification declared = BuiltInToolClassifications.all().get(name);
            if (declared != null) {
                return new ToolClassificationDecision.Classified(declared);
            }
            return new ToolClassificationDecision.Blocked(
                name, ToolClassificationDecision.Reason.UNCLASSIFIED, "unclassified");
        });
        return registry;
    }

    private static AgentTaintPolicy policy(String mode, String hosts, ToolClassificationRegistry registry) {
        return new AgentTaintPolicy(mode, hosts, provider(registry), provider((MeterRegistry) null),
            provider((AuditLogService) null));
    }

    @Test
    void webFetchIsASourceButNotASink() {
        ToolTaintProfile p = policy("enforce", "", builtInRegistry()).profile("fetch_web_page", "{}");
        assertTrue(p.producesTaint());
        assertFalse(p.sink());
    }

    @Test
    void emailAndWriteHttpAreSinks() {
        AgentTaintPolicy policy = policy("enforce", "", builtInRegistry());
        assertTrue(policy.profile("google_gmail", "{}").sink());
        assertTrue(policy.profile("http_request_write", "{}").sink());
        assertTrue(policy.profile("add_to_knowledge_base", "{}").sink(), "a write persists injected text");
    }

    @Test
    void anUnclassifiedToolIsASinkAndOnlyMcpToolsAreSources() {
        AgentTaintPolicy policy = policy("enforce", "", builtInRegistry());
        ToolTaintProfile custom = policy.profile("my_custom_tool", "{}");
        assertTrue(custom.sink());
        assertFalse(custom.producesTaint());
        ToolTaintProfile mcp = policy.profile("mcp__crm__lookup", "{}");
        assertTrue(mcp.sink());
        assertTrue(mcp.producesTaint());
    }

    @Test
    void aMcpToolWithAReadOnlyHintIsNotASink() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        ToolClassification readOnly = McpAnnotationClassifier
            .map("mcp__docs__search", new McpToolHints(true, null, null, null)).orElseThrow();
        when(registry.resolve("mcp__docs__search")).thenReturn(new ToolClassificationDecision.Classified(readOnly));

        ToolTaintProfile p = policy("enforce", "", registry).profile("mcp__docs__search", "{}");

        assertFalse(p.sink());
        assertTrue(p.producesTaint(), "third-party output still taints");
    }

    @Test
    void httpRequestMethodIsInspectedBecauseTheToolIsClassifiedByNameOnly() {
        AgentTaintPolicy policy = policy("enforce", "", builtInRegistry());
        assertFalse(policy.profile("http_request", "{\"url\":\"https://a.test\"}").sink());
        assertFalse(policy.profile("http_request", "{\"url\":\"https://a.test\",\"method\":\"get\"}").sink());
        assertTrue(policy.profile("http_request", "{\"url\":\"https://a.test\",\"method\":\"POST\"}").sink());
        assertTrue(policy.profile("http_request", "{\"url\":\"https://a.test\",\"method\":\"DELETE\"}").sink());
        assertTrue(policy.profile("http_request", "not json").sink(), "unparseable arguments fail closed");
        assertTrue(policy.profile("http_request", "{\"method\":\"POST\"}").producesTaint());
    }

    @Test
    void getAllowListMakesOtherHostsASinkWhenConfigured() {
        AgentTaintPolicy policy = policy("enforce", "docs.example.com, example.org", builtInRegistry());
        assertFalse(policy.profile("fetch_web_page", "{\"url\":\"https://docs.example.com/a\"}").sink());
        assertFalse(policy.profile("http_request", "{\"url\":\"https://x.example.org/a\"}").sink());
        assertTrue(policy.profile("fetch_web_page", "{\"url\":\"https://evil.test/?d=secret\"}").sink());
        assertTrue(policy.profile("fetch_web_page", "{\"url\":\"https://notexample.org/\"}").sink());
        assertTrue(policy.profile("fetch_web_page", "{}").sink());
        assertTrue(policy.profile("fetch_web_page", "{\"url\":\"https://docs.example.com@evil.test/\"}").sink(),
            "userinfo must not smuggle an allow-listed name in front of another host");
        assertTrue(policy.profile("fetch_web_page", "{\"url\":\"https://evil.test/docs.example.com\"}").sink());
    }

    @Test
    void anAuditTargetLongerThanTheColumnIsTruncatedNotLost() {
        AuditLogService audit = mock(AuditLogService.class);
        AgentTaintPolicy policy = new AgentTaintPolicy("enforce", "", provider(builtInRegistry()),
            provider((MeterRegistry) null), provider(audit));
        AtomicInteger turn = new AtomicInteger();
        policy.seed(turn, "fetch_web_page");
        String longName = "mcp__" + "x".repeat(300);

        assertTrue(policy.gateFor(turn, UUID.randomUUID(), "s").check(longName, "{}").requiresApproval());

        org.mockito.ArgumentCaptor<String> target = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(audit).record(org.mockito.ArgumentMatchers.eq("agent-taint"),
            org.mockito.ArgumentMatchers.eq("tool.taint.approval_required"),
            org.mockito.ArgumentMatchers.eq("tool"), target.capture(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
        assertEquals(128, target.getValue().length());
    }

    @Test
    void verdictsAreMemoisedPerTool() {
        ToolClassificationRegistry registry = builtInRegistry();
        AgentTaintPolicy policy = policy("enforce", "", registry);
        policy.profile("fetch_web_page", "{}");
        policy.profile("fetch_web_page", "{}");
        policy.profile("fetch_web_page", "{}");
        verify(registry, times(1)).resolve("fetch_web_page");
    }

    @Test
    void offBuildsNoGateAndNoState() {
        AgentTaintPolicy policy = policy("off", "", builtInRegistry());
        AtomicInteger turn = new AtomicInteger();
        assertNull(policy.gateFor(turn, UUID.randomUUID(), "s"));
        assertNull(policy.turnTaint(turn));
        policy.seed(turn, "x");
    }

    @Test
    void theTurnSharesOneTaintAcrossEveryToolGate() {
        AgentTaintPolicy policy = policy("observe", "", builtInRegistry());
        AtomicInteger turn = new AtomicInteger();
        TaintGate a = policy.gateFor(turn, UUID.randomUUID(), "s");
        TaintGate b = policy.gateFor(turn, UUID.randomUUID(), "s");
        assertNotNull(a);
        assertSame(a.turn(), b.turn());
        a.afterCall("fetch_web_page", "{}", "page");
        assertTrue(b.turn().isTainted());
        assertFalse(policy.gateFor(new AtomicInteger(), UUID.randomUUID(), "s").turn().isTainted(),
            "a different turn starts clean");
    }

    @Test
    void aSeededTurnIsTaintedBeforeAnyToolRuns() {
        AgentTaintPolicy policy = policy("enforce", "", builtInRegistry());
        AtomicInteger turn = new AtomicInteger();
        policy.seed(turn, "channel:non-owner");
        TaintGate gate = policy.gateFor(turn, UUID.randomUUID(), "s");
        assertTrue(gate.check("google_gmail", "{}").requiresApproval());
        assertFalse(gate.check("fetch_web_page", "{}").requiresApproval());
        assertEquals("[channel:non-owner]", gate.turn().sources().toString());
    }

    @Test
    void modeDefaultsToObserveWhenUnrecognised() {
        assertEquals("OBSERVE", policy("???", "", builtInRegistry()).mode().name());
    }

    @Test
    void theLayerObservesByDefaultAndIsInertWhenSwitchedOff() throws Exception {
        var ctor = AgentTaintPolicy.class.getConstructors()[0];
        String expr =
            ctor.getParameters()[0].getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();

        // Owner decision 2026-10-23 (defaults review row 3): observe in the base profile (the code default equals
        // application.yml, ConfigurationDriftTest); personal ships enforce, APP_AGENTS_TAINT_MODE=off switches it off.
        assertEquals("${app.agents.taint.mode:observe}", expr);
        AgentTaintPolicy off = policy("off", "", builtInRegistry());
        assertEquals(null, off.gateFor(new java.util.concurrent.atomic.AtomicInteger(), java.util.UUID.randomUUID(), "s"));
    }

    @Test
    void observeTracksTaintButNeverAsksForApprovalSoTheDefaultChangesNoBehaviour() {
        AgentTaintPolicy policy = policy("observe", "", builtInRegistry());
        AtomicInteger turn = new AtomicInteger();
        policy.seed(turn, "channel:non-owner");
        TaintGate gate = policy.gateFor(turn, UUID.randomUUID(), "s");

        assertTrue(gate.turn().isTainted(), "observe still records that the turn is tainted");
        assertFalse(gate.check("google_gmail", "{}").requiresApproval(), "but a consequential call is not parked");
    }

    @Test
    void theHostAllowListAlsoCoversBrowserNavigation() {
        AgentTaintPolicy policy = policy("enforce", "example.org", builtInRegistry());
        assertFalse(policy.profile("browser_navigate", "{\"url\":\"https://docs.example.org/a\"}").sink());
        assertTrue(policy.profile("browser_navigate", "{\"url\":\"https://evil.test/?d=secret\"}").sink());
        // unset list: unchanged, no restriction
        assertFalse(policy("enforce", "", builtInRegistry())
            .profile("browser_navigate", "{\"url\":\"https://evil.test/\"}").sink());
    }

    @Test
    void searchAndBrowserAgentOutputTaintsTheTurn() {
        AgentTaintPolicy policy = policy("enforce", "", builtInRegistry());
        for (String tool : new String[] {"web_search", "web_task", "delegate_parallel", "run_subtasks", "search_conversation_history"}) {
            assertTrue(policy.profile(tool, "{}").producesTaint(), tool);
        }
        assertFalse(policy.profile("load_skill", "{}").sink(), "a skill load after a fetch is not an unknown-tool approval");
    }

    @Test
    void promptTimeSeedTaintsTheTurnWhicheverSideArrivesFirst() {
        AgentTaintPolicy policy = policy("enforce", "", builtInRegistry());
        AtomicInteger key = new AtomicInteger();
        com.agenticai.core.guardrails.taint.TurnTaintSeeds seeds = new com.agenticai.core.guardrails.taint.TurnTaintSeeds();
        seeds.mark("memory_recall"); // prompt assembly finished before tool resolution bound the key
        seeds.bind(source -> policy.seed(key, source));
        assertTrue(policy.turnTaint(key).isTainted());
        assertEquals(java.util.List.of("memory_recall"), policy.turnTaint(key).sources());

        AtomicInteger later = new AtomicInteger();
        com.agenticai.core.guardrails.taint.TurnTaintSeeds late = new com.agenticai.core.guardrails.taint.TurnTaintSeeds();
        late.bind(source -> policy.seed(later, source));
        assertFalse(policy.turnTaint(later).isTainted());
        late.mark("memory_recall");
        assertTrue(policy.turnTaint(later).isTainted());
    }

    @Test
    void seedIsInertWhenTheLayerIsOff() {
        AgentTaintPolicy policy = policy("off", "", builtInRegistry());
        AtomicInteger key = new AtomicInteger();
        policy.seed(key, "memory_recall");
        assertNull(policy.turnTaint(key));
    }
}
