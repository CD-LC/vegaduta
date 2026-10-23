package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.classification.LethalTrifectaDetector;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.agents.tools.classification.TrifectaEnforcementPolicy;
import com.agenticai.core.agents.tools.classification.TrifectaStatusResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Lethal-trifecta enforcement: what a save does in each mode, the per-agent exempt-list override on
 * the dispatch path (cached, invalidated, fail safe), and the additive fields on the status
 * response.
 */
class TrifectaEnforcementServiceTest {

    private static final String READ = "read_crm";
    private static final String WEB = "fetch_web_page";
    private static final String SEND = "send_mail";
    private static final String CALC = "calculator";

    private final UUID agentId = UUID.randomUUID();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
    private final AgentService agents = mock(AgentService.class);
    private final AgentToolProvider toolProvider = mock(AgentToolProvider.class);
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void stubRegistry() {
        Map<String, ToolClassificationDecision> catalog = new LinkedHashMap<>();
        catalog.put(
                READ,
                classified(READ, ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_TENANT, false));
        catalog.put(
                WEB,
                classified(
                        WEB, ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.READ_PUBLIC, false));
        catalog.put(
                SEND, classified(SEND, ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS, true));
        catalog.put(
                CALC,
                classified(
                        CALC, ToolTrustTier.TRUSTED_INTERNAL, ToolSensitivity.READ_PUBLIC, false));
        when(registry.resolveAll(anyCollection()))
                .thenAnswer(
                        invocation -> {
                            Map<String, ToolClassificationDecision> out = new LinkedHashMap<>();
                            for (Object name :
                                    (java.util.Collection<?>) invocation.getArgument(0)) {
                                ToolClassificationDecision decision =
                                        catalog.get(String.valueOf(name));
                                if (decision != null) {
                                    out.put(String.valueOf(name), decision);
                                }
                            }
                            return out;
                        });
    }

    // ---- save time ---------------------------------------------------------

    @Test
    void offDoesNothingAndNeverTouchesTheRegistry() {
        TrifectaEnforcementService service = service("off", true, false, true);

        TrifectaEnforcementService.SaveVerdict verdict =
                service.evaluateSave(agentId, Set.of(), Set.of(READ, WEB, SEND));

        assertEquals(TrifectaEnforcementPolicy.Action.NONE, verdict.action());
        verify(registry, never()).resolveAll(any());
    }

    @Test
    void warnWarnsAndCountsButChangesNothing() {
        TrifectaEnforcementService service = service("warn", true, false, true);

        TrifectaEnforcementService.SaveVerdict verdict =
                service.evaluateSave(agentId, Set.of(), Set.of(READ, WEB, SEND));

        assertEquals(TrifectaEnforcementPolicy.Action.WARN, verdict.action());
        assertFalse(verdict.forcesApproval());
        assertEquals(1.0, meters.counter("agent.config.lethal_trifecta").count());
        assertEquals(0.0, meters.counter(TrifectaEnforcementService.BLOCKED_METRIC).count());
    }

    @Test
    void aSaveThatIsNotATrifectaIsLeftAlone() {
        TrifectaEnforcementService service = service("block", true, false, true);

        assertEquals(
                TrifectaEnforcementPolicy.Action.NONE,
                service.evaluateSave(agentId, Set.of(), Set.of(READ, SEND)).action());
        assertEquals(0.0, meters.counter("agent.config.lethal_trifecta").count());
    }

    @Test
    void requireApprovalAsksTheCallerToForceTheSupervisedTier() {
        TrifectaEnforcementService service = service("require-approval", true, false, true);

        TrifectaEnforcementService.SaveVerdict verdict =
                service.evaluateSave(agentId, Set.of(READ), Set.of(READ, WEB, SEND));

        assertTrue(verdict.forcesApproval());
        assertTrue(verdict.report().isLethalTrifecta());
    }

    @Test
    void noteApprovalForcedIsCounted() {
        TrifectaEnforcementService service = service("require-approval", true, false, true);

        service.noteApprovalForced(agentId);

        assertEquals(
                1.0, meters.counter(TrifectaEnforcementService.APPROVAL_FORCED_METRIC).count());
    }

    @Test
    void blockRejectsASaveThatCompletesTheTrifectaWithAMessageNamingTheLegs() {
        TrifectaEnforcementService service = service("block", true, false, true);

        IllegalArgumentException blocked =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.evaluateSave(
                                        agentId, Set.of(READ, SEND), Set.of(READ, WEB, SEND)));

        assertTrue(
                blocked.getMessage().startsWith(TrifectaEnforcementPolicy.BLOCK_MESSAGE_PREFIX),
                blocked.getMessage());
        assertTrue(
                blocked.getMessage().contains(READ)
                        && blocked.getMessage().contains(WEB)
                        && blocked.getMessage().contains(SEND),
                blocked.getMessage());
        assertEquals(1.0, meters.counter(TrifectaEnforcementService.BLOCKED_METRIC).count());
    }

    @Test
    void blockNeverRejectsASaveThatRemovesALeg() {
        TrifectaEnforcementService service = service("block", true, false, true);

        TrifectaEnforcementService.SaveVerdict verdict =
                service.evaluateSave(agentId, Set.of(READ, WEB, SEND), Set.of(READ, SEND));

        assertEquals(TrifectaEnforcementPolicy.Action.NONE, verdict.action());
        assertEquals(0.0, meters.counter(TrifectaEnforcementService.BLOCKED_METRIC).count());
    }

    @Test
    void blockNeverRejectsASaveThatLeavesAnAlreadyLethalAgentNoWorse() {
        TrifectaEnforcementService service = service("block", true, false, true);

        TrifectaEnforcementService.SaveVerdict verdict =
                service.evaluateSave(
                        agentId, Set.of(READ, WEB, SEND), Set.of(READ, WEB, SEND, CALC));

        assertEquals(TrifectaEnforcementPolicy.Action.WARN, verdict.action());
    }

    @Test
    void anUnknownModeBehavesAsWarn() {
        TrifectaEnforcementService service = service("strictest", true, false, true);

        assertEquals(
                TrifectaEnforcementPolicy.Action.WARN,
                service.evaluateSave(agentId, Set.of(), Set.of(READ, WEB, SEND)).action());
    }

    @Test
    void aClassificationLookupFailureNeverFailsTheSave() {
        when(registry.resolveAll(anyCollection())).thenThrow(new IllegalStateException("db down"));
        TrifectaEnforcementService service = service("block", true, false, true);

        assertEquals(
                TrifectaEnforcementPolicy.Action.NONE,
                service.evaluateSave(agentId, Set.of(), Set.of(READ, WEB, SEND)).action());
    }

    @Test
    void anEmptyAllowListIsNotAnalysedUnlessAutoSelectAnalysisIsOn() {
        TrifectaEnforcementService service = service("block", true, false, true);

        assertEquals(
                TrifectaEnforcementPolicy.Action.NONE,
                service.evaluateSave(agentId, Set.of(READ), Set.of()).action());
        verify(toolProvider, never()).listAvailableToolsFor(any());
    }

    @Test
    void anEmptyAllowListIsAnalysedOverTheOfferedToolsWhenAutoSelectAnalysisIsOn() {
        when(toolProvider.listAvailableToolsFor(agentId))
                .thenReturn(
                        List.of(
                                new ToolDescriptor(READ, "r"),
                                new ToolDescriptor(WEB, "w"),
                                new ToolDescriptor(SEND, "s")));
        TrifectaEnforcementService service = service("block", true, true, true);

        // Switching a two-leg agent to Auto-select (empty list) would hand it all three legs.
        IllegalArgumentException blocked =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.evaluateSave(agentId, Set.of(READ, SEND), Set.of()));
        assertTrue(blocked.getMessage().contains(WEB), blocked.getMessage());
    }

    @Test
    void autoSelectToolsThatCannotBeResolvedNeverFailTheSave() {
        when(toolProvider.listAvailableToolsFor(agentId))
                .thenThrow(new IllegalStateException("mcp down"));
        TrifectaEnforcementService service = service("block", true, true, true);

        assertEquals(
                TrifectaEnforcementPolicy.Action.NONE,
                service.evaluateSave(agentId, Set.of(READ), Set.of()).action());
    }

    @Test
    void aServerSideWriterIsNeverRejectedInBlockModeAndFallsBackToTheSupervisedTier() {
        TrifectaEnforcementService service = service("block", true, false, true);

        TrifectaEnforcementService.SaveVerdict verdict =
                service.evaluateSave(agentId, Set.of(), Set.of(READ, WEB, SEND), false);

        assertTrue(verdict.forcesApproval());
        assertEquals(0.0, meters.counter(TrifectaEnforcementService.BLOCKED_METRIC).count());
    }

    @Test
    void aServerSideWriterInWarnModeStillOnlyWarns() {
        TrifectaEnforcementService service = service("warn", true, false, true);

        assertEquals(
                TrifectaEnforcementPolicy.Action.WARN,
                service.evaluateSave(agentId, Set.of(), Set.of(READ, WEB, SEND), false).action());
    }

    @Test
    void applyVerdictMovesTheAgentToSupervisedOnceAndNotesIt() {
        TrifectaEnforcementService service = service("require-approval", true, false, true);
        Agent agent = newAgent();
        agent.setAutonomyTier(AgentAutonomyTier.FULLY_AUTONOMOUS);
        TrifectaEnforcementService.SaveVerdict verdict =
                service.evaluateSave(agentId, Set.of(), Set.of(READ, WEB, SEND), false);

        String note = service.applyVerdict(agent, verdict);

        assertEquals(AgentAutonomyTier.SUPERVISED, agent.getAutonomyTier());
        assertTrue(note.contains("SUPERVISED") && note.contains("require-approval"), note);
        assertEquals(
                "",
                service.applyVerdict(agent, verdict),
                "already SUPERVISED: nothing to change or note");
        assertEquals(
                1.0, meters.counter(TrifectaEnforcementService.APPROVAL_FORCED_METRIC).count());
    }

    @Test
    void applyVerdictLeavesTheTierAloneForANoneOrNullVerdict() {
        TrifectaEnforcementService service = service("require-approval", true, false, true);
        Agent agent = newAgent();
        agent.setAutonomyTier(AgentAutonomyTier.FULLY_AUTONOMOUS);

        assertEquals("", service.applyVerdict(agent, TrifectaEnforcementService.SaveVerdict.NONE));
        assertEquals("", service.applyVerdict(agent, null));
        assertEquals(AgentAutonomyTier.FULLY_AUTONOMOUS, agent.getAutonomyTier());
    }

    private static Agent newAgent() {
        return new Agent(
                "Test Agent",
                "You are helpful.",
                ProviderName.ANTHROPIC,
                "claude-sonnet-5",
                1024,
                0.7,
                1.0,
                false,
                ExperienceMode.ADVANCED);
    }

    // ---- status ------------------------------------------------------------

    @Test
    void statusKeepsTheLegFieldsAndAddsEnforcementEnforcedAndGated() {
        TrifectaEnforcementService service = service("require-approval", true, false, true);

        TrifectaStatusResponse status =
                service.status(agentId, Set.of(READ, WEB, SEND), AgentAutonomyTier.SUPERVISED);

        assertEquals(List.of(READ), status.privateDataTools());
        assertEquals(List.of(WEB), status.untrustedContentTools());
        assertEquals(List.of(SEND), status.egressTools());
        assertTrue(status.lethalTrifecta());
        assertEquals("require-approval", status.enforcement());
        assertTrue(status.enforced());
        assertTrue(status.gated());
        assertTrue(status.warning().contains(SEND));
    }

    @Test
    void statusInWarnModeIsNeitherEnforcedNorGated() {
        TrifectaEnforcementService service = service("warn", true, false, true);

        TrifectaStatusResponse status =
                service.status(agentId, Set.of(READ, WEB, SEND), AgentAutonomyTier.SEMI_AUTONOMOUS);

        assertEquals("warn", status.enforcement());
        assertTrue(status.lethalTrifecta());
        assertFalse(status.enforced());
        assertFalse(status.gated());
    }

    @Test
    void statusInRequireApprovalModeIsGatedByTheExemptOverrideForASemiAutonomousAgent() {
        TrifectaEnforcementService service = service("require-approval", true, false, true);

        TrifectaStatusResponse status =
                service.status(agentId, Set.of(READ, WEB, SEND), AgentAutonomyTier.SEMI_AUTONOMOUS);

        assertTrue(status.enforced());
        assertTrue(status.gated());
    }

    @Test
    void statusIsNotGatedForAFullyAutonomousAgent() {
        TrifectaEnforcementService service = service("block", true, false, true);

        assertFalse(
                service.status(agentId, Set.of(READ, WEB, SEND), AgentAutonomyTier.FULLY_AUTONOMOUS)
                        .gated());
    }

    @Test
    void statusOfAnOrdinaryAgentIsQuiet() {
        TrifectaEnforcementService service = service("block", true, false, true);

        TrifectaStatusResponse status =
                service.status(agentId, Set.of(READ, SEND), AgentAutonomyTier.SEMI_AUTONOMOUS);

        assertFalse(status.lethalTrifecta());
        assertFalse(status.enforced());
        assertFalse(status.gated());
        assertNull(status.warning());
    }

    @Test
    void statusUnderOffReportsNothingSoTheConsoleStaysQuiet() {
        TrifectaEnforcementService service = service("off", true, false, true);

        TrifectaStatusResponse status =
                service.status(agentId, Set.of(READ, WEB, SEND), AgentAutonomyTier.SEMI_AUTONOMOUS);

        assertEquals("off", status.enforcement());
        assertTrue(status.privateDataTools().isEmpty() && status.egressTools().isEmpty());
        assertFalse(status.lethalTrifecta());
    }

    @Test
    void statusNeverThrowsWhenTheClassificationLookupFails() {
        when(registry.resolveAll(anyCollection())).thenThrow(new IllegalStateException("db down"));
        TrifectaEnforcementService service = service("warn", true, false, true);

        assertFalse(
                service.status(agentId, Set.of(READ, WEB, SEND), AgentAutonomyTier.SEMI_AUTONOMOUS)
                        .lethalTrifecta());
    }

    // ---- dispatch time: exempt-list override ------------------------------

    @Test
    void anOrdinaryAgentKeepsTheExemptList() {
        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, SEND));
        TrifectaEnforcementService service = service("require-approval", true, false, true);

        assertTrue(service.exemptionHolds(agentId));
        assertEquals(0.0, meters.counter(TrifectaEnforcementService.EXEMPT_REVOKED_METRIC).count());
    }

    @Test
    void anAgentHoldingTheTrifectaLosesTheExemptList() {
        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, WEB, SEND));
        TrifectaEnforcementService service = service("require-approval", true, false, true);

        assertFalse(service.exemptionHolds(agentId));
        assertEquals(1.0, meters.counter(TrifectaEnforcementService.EXEMPT_REVOKED_METRIC).count());
    }

    @Test
    void theOverrideCanBeSwitchedOffAndThenNeverLooksAtTheAgent() {
        TrifectaEnforcementService service = service("require-approval", false, false, true);

        assertTrue(service.exemptionHolds(agentId));
        verify(agents, never()).getAllowedToolNames(any());
    }

    @Test
    void warnModeNeverRevokesAnExemptionAndNeverLooksAtTheAgent() {
        TrifectaEnforcementService service = service("warn", true, false, true);

        assertTrue(service.exemptionHolds(agentId));
        verify(agents, never()).getAllowedToolNames(any());
        assertEquals(0.0, meters.counter(TrifectaEnforcementService.EXEMPT_REVOKED_METRIC).count());
    }

    @Test
    void offModeNeverRevokesAnExemption() {
        TrifectaEnforcementService service = service("off", true, false, true);

        assertTrue(service.exemptionHolds(agentId));
        verify(agents, never()).getAllowedToolNames(any());
    }

    @Test
    void theVerdictIsCachedPerAgentSoTheHotPathStaysCheap() {
        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, WEB, SEND));
        TrifectaEnforcementService service = service("require-approval", true, false, true);

        for (int i = 0; i < 25; i++) {
            assertFalse(service.exemptionHolds(agentId));
        }

        verify(agents, times(1)).getAllowedToolNames(agentId);
    }

    @Test
    void theVerdictExpiresAfterItsTtl() {
        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, SEND));
        TrifectaEnforcementService service = service("require-approval", true, false, true);
        assertTrue(service.exemptionHolds(agentId));

        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, WEB, SEND));
        now.addAndGet(30_000L);
        assertTrue(
                service.exemptionHolds(agentId), "still inside the TTL: the cached verdict stands");
        now.addAndGet(31_000L);
        assertFalse(
                service.exemptionHolds(agentId),
                "TTL passed: recomputed from the current tool config");
    }

    @Test
    void aToolConfigChangeInvalidatesTheCachedVerdict() {
        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, SEND));
        TrifectaEnforcementService service = service("require-approval", true, false, true);
        assertTrue(service.exemptionHolds(agentId));

        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, WEB, SEND));
        service.invalidateAfterCommit(agentId);

        assertFalse(service.exemptionHolds(agentId));
    }

    @Test
    void anAgentThatCannotBeJudgedLosesTheExemptionAndIsRetriedSoon() {
        when(agents.getAllowedToolNames(agentId)).thenThrow(new IllegalStateException("db down"));
        TrifectaEnforcementService service = service("require-approval", true, false, true);

        assertFalse(service.exemptionHolds(agentId), "fail safe: unknown means not exempt");
        assertFalse(service.exemptionHolds(agentId));
        verify(agents, times(1)).getAllowedToolNames(agentId);

        doReturn(Set.of(READ, SEND)).when(agents).getAllowedToolNames(agentId);
        now.addAndGet(6_000L);
        assertTrue(
                service.exemptionHolds(agentId),
                "the short negative TTL has passed, so it is judged again");
    }

    @Test
    void aClassificationLookupFailureRevokesTheExemption() {
        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, SEND));
        when(registry.resolveAll(anyCollection())).thenThrow(new IllegalStateException("db down"));
        TrifectaEnforcementService service = service("require-approval", true, false, true);

        assertFalse(service.exemptionHolds(agentId));
    }

    @Test
    void withoutAnAgentServiceTheExemptionFailsSafe() {
        TrifectaEnforcementService service =
                new TrifectaEnforcementService(
                        providerOf(
                                new LethalTrifectaDetector(
                                        registry, providerOf((MeterRegistry) meters))),
                        providerOf(null),
                        providerOf(toolProvider),
                        providerOf((MeterRegistry) meters),
                        "require-approval",
                        true,
                        false,
                        true);

        assertFalse(service.exemptionHolds(agentId));
    }

    // ---- dispatch time: relaxing a gate on the verdict ---------------------

    @Test
    void anAgentPositivelyJudgedNotATrifectaIsDefinitelyNotLethal() {
        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, SEND));

        assertTrue(service("warn", true, false, true).definitelyNotLethal(agentId));
    }

    @Test
    void anAgentHoldingTheTrifectaIsNotDefinitelyNotLethalInAnyMode() {
        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, WEB, SEND));

        for (String mode : new String[] {"off", "warn", "require-approval", "block"}) {
            assertFalse(
                    service(mode, true, false, true).definitelyNotLethal(agentId),
                    "mode " + mode + " is about saves; a relaxation of a gate still needs the agent to be safe");
        }
    }

    @Test
    void anAgentThatCannotBeJudgedIsNotDefinitelyNotLethal() {
        when(agents.getAllowedToolNames(agentId)).thenThrow(new IllegalStateException("db down"));
        TrifectaEnforcementService service = service("warn", true, false, true);

        assertFalse(service.definitelyNotLethal(agentId));
        assertFalse(service.definitelyNotLethal(null));
    }

    @Test
    void aClassificationLookupFailureIsNotDefinitelyNotLethal() {
        when(agents.getAllowedToolNames(agentId)).thenReturn(Set.of(READ, SEND));
        when(registry.resolveAll(anyCollection())).thenThrow(new IllegalStateException("db down"));

        assertFalse(service("warn", true, false, true).definitelyNotLethal(agentId));
    }

    // ---- helpers -----------------------------------------------------------

    private TrifectaEnforcementService service(
            String mode,
            boolean overridesExempt,
            boolean analyseAutoSelect,
            boolean confirmationGate) {
        TrifectaEnforcementService service =
                new TrifectaEnforcementService(
                        providerOf(
                                new LethalTrifectaDetector(
                                        registry, providerOf((MeterRegistry) meters))),
                        providerOf(agents),
                        providerOf(toolProvider),
                        providerOf((MeterRegistry) meters),
                        mode,
                        overridesExempt,
                        analyseAutoSelect,
                        confirmationGate);
        service.setClockForTest(now::get);
        return service;
    }

    private static ToolClassificationDecision classified(
            String name, ToolTrustTier trust, ToolSensitivity sensitivity, boolean irreversible) {
        return new ToolClassificationDecision.Classified(
                new ToolClassification(
                        name,
                        trust,
                        sensitivity,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        irreversible,
                        true));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
