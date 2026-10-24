package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.ReadOnlyAutoAllow;
import com.agenticai.core.guardrails.ToolBudgetExhausted;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The SUPERVISED tier and read-only calls ({@code app.guardrails.supervised.gate-read-only}), run through the real turn
 * pipeline, the real guard chain, the real approval service and the REAL tool classification (the compiled-in
 * declarations), the real lethal-trifecta analysis and the real settings catalog; only the model is scripted.
 *
 * <p>With the setting at its base value (gate) every call of a supervised agent parks, a search included. With it off (the
 * {@code personal} profile) a call runs unattended only when its tool has an explicit read-only classification, and the
 * agent is not flagged by the trifecta detector; a write, an unclassified tool and a trifecta agent still park, and a
 * workspace or an agent can only make the setting stricter.
 */
class SupervisedReadOnlyE2ETest {

    private static final String GOAL = "Find out what the build status page says and note it.";
    /** Built-in classification: UNTRUSTED_EXTERNAL / READ_PUBLIC (a read). */
    private static final String SEARCH = "web_search";
    /** Built-in classification: TENANT_SCOPED / READ_TENANT (a read of the workspace's own data). */
    private static final String LIST = "list_knowledge_collections";
    /** Built-in classification: TENANT_SCOPED / WRITE_TENANT / DURABLE (a write). */
    private static final String REMEMBER = "remember_fact";
    /** No classification row anywhere. */
    private static final String UNKNOWN = "mystery_lookup";
    /** Built-in classification: EGRESS. Never offered here: only its presence in the allow-list matters. */
    private static final String EGRESS_TOOL = "http_request_write";

    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private static RecordingTool tool(String name) {
        return RecordingTool.returning(name, "Test tool " + name, "{\"ok\":true}");
    }

    private static ScriptedLlm.Responder callsOnceThenAnswers(String toolName) {
        return request ->
                request.hasToolResult()
                        ? ScriptedLlm.text("Done.")
                        : ScriptedLlm.callTool("c1", toolName, "{\"to\":\"x\"}");
    }

    private ApprovalTurnHarness.Builder supervised(RecordingTool... tools) {
        ApprovalTurnHarness.Builder builder =
                ApprovalTurnHarness.builder().autonomy(AgentAutonomyTier.SUPERVISED).classifications().settingsCatalog();
        for (RecordingTool t : tools) {
            builder.tool(t);
        }
        return builder;
    }

    /** The {@code personal} profile's value: a supervised agent no longer asks for a classified read. */
    private static void personal(com.agenticai.core.guardrails.GuardrailTuningProperties tuning) {
        tuning.getSupervised().setGateReadOnly(false);
    }

    // ------------------------------------------------------------------------------ the base value

    @Test
    void withTheBaseValueASupervisedAgentIsAskedBeforeASearchToo() {
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).responder(callsOnceThenAnswers(SEARCH)).build();

        harness.chat(GOAL);

        assertAll(
                () -> assertEquals(0, search.executions(), "the search did not run: it is waiting for a person"),
                () -> assertEquals(1, harness.approvalRequests().size()),
                () -> verify(harness.auditLog(), never()).record(
                        eq("agent-supervised"), eq(ReadOnlyAutoAllow.AUDIT_ACTION), eq("tool"), eq(SEARCH),
                        eq(AuditOutcome.AUTO_APPROVED), contains(ReadOnlyAutoAllow.NOTE)));
    }

    // -------------------------------------------------------------------- off: a classified read runs

    @Test
    void withTheSettingOffAClassifiedReadOnlyToolRunsWithNoApprovalAndIsAudited() {
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .responder(callsOnceThenAnswers(SEARCH)).build();

        String reply = harness.chat(GOAL);

        assertAll(
                () -> assertEquals(1, search.executions(), "the classified read-only tool ran"),
                () -> assertTrue(harness.approvalRequests().isEmpty(), "and nobody was asked"),
                () -> assertTrue(reply.contains("Done."), reply),
                () -> verify(harness.auditLog()).record(
                        eq("agent-supervised"), eq(ReadOnlyAutoAllow.AUDIT_ACTION), eq("tool"), eq(SEARCH),
                        eq(AuditOutcome.AUTO_APPROVED), contains("auto-allowed: read-only under SUPERVISED")));
    }

    @Test
    void aReadOfTheWorkspacesOwnDataIsReadOnlyToo() {
        RecordingTool list = tool(LIST);
        harness = supervised(list).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .responder(callsOnceThenAnswers(LIST)).build();

        harness.chat(GOAL);

        assertEquals(1, list.executions());
        assertTrue(harness.approvalRequests().isEmpty());
    }

    @Test
    void aWriteIsStillParkedForAPersonEvenWithTheSettingOff() {
        RecordingTool remember = tool(REMEMBER);
        harness = supervised(remember).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .responder(callsOnceThenAnswers(REMEMBER)).build();

        harness.chat(GOAL);

        assertAll(
                () -> assertEquals(0, remember.executions(), "a write needs a person"),
                () -> assertEquals(1, harness.approvalRequests().size()),
                () -> verify(harness.auditLog(), never()).record(
                        eq("agent-supervised"), eq(ReadOnlyAutoAllow.AUDIT_ACTION), eq("tool"), eq(REMEMBER),
                        eq(AuditOutcome.AUTO_APPROVED), contains(ReadOnlyAutoAllow.NOTE)));
    }

    @Test
    void anUnclassifiedToolIsStillParkedEvenWithTheSettingOff() {
        RecordingTool unknown = tool(UNKNOWN);
        harness = supervised(unknown).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .responder(callsOnceThenAnswers(UNKNOWN)).build();

        harness.chat(GOAL);

        assertAll(
                () -> assertEquals(0, unknown.executions(), "unknown is not read-only"),
                () -> assertEquals(1, harness.approvalRequests().size()));
    }

    @Test
    void anAgentThatHoldsTheLethalTrifectaKeepsFullGating() {
        // Private data (list_knowledge_collections), untrusted content (web_search) and egress (http_request_write)
        // in one allow-list: the detector flags the agent, so even a classified read is asked about.
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .allowedTools(SEARCH, LIST, EGRESS_TOOL)
                .responder(callsOnceThenAnswers(SEARCH)).build();

        harness.chat(GOAL);

        assertAll(
                () -> assertEquals(0, search.executions(), "a trifecta agent is gated on every call"),
                () -> assertEquals(1, harness.approvalRequests().size()));
    }

    @Test
    void anAgentThatIsNotATrifectaKeepsTheReadsUnattendedEvenWithAnAllowList() {
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .allowedTools(SEARCH, LIST)
                .responder(callsOnceThenAnswers(SEARCH)).build();

        harness.chat(GOAL);

        assertEquals(1, search.executions());
        assertTrue(harness.approvalRequests().isEmpty());
    }

    // ----------------------------------------------------------- the other guards still run

    @Test
    void theStepCeilingStillAppliesToAnUnattendedReadOnlyCall() {
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .maxToolCallsPerTurn(4)
                .stepLimits(limits -> limits.setSupervisedMinSteps(1))
                .responder(
                        request ->
                                request.toolResults().stream().anyMatch(ToolBudgetExhausted::isBudgetExhausted)
                                        ? ScriptedLlm.text("Here is what I found so far.")
                                        : ScriptedLlm.callTool("c" + request.number(), SEARCH, "{\"n\":" + request.number() + "}"))
                .build();

        String reply = harness.chat(GOAL);

        assertAll(
                () -> assertEquals(2, search.executions(), "a supervised agent's ceiling is half of 4; the third call was refused"),
                () -> assertTrue(harness.approvalRequests().isEmpty()),
                () -> assertTrue(reply.contains("Here is what I found so far."), reply));
    }

    // ---------------------------------------------- a workspace or an agent can only make it stricter

    @Test
    void aWorkspaceCannotLoosenTheBaseValue() {
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).responder(callsOnceThenAnswers(SEARCH)).build();
        // Written around the API (the API refuses it): the resolver and the use site must both ignore it.
        harness.tenantSetting("guardrails.supervised.readOnlyCalls", "auto-allow");
        harness.agentSetting("guardrails.supervised.readOnlyCalls", "auto-allow");

        harness.chat(GOAL);

        assertAll(
                () -> assertEquals(0, search.executions(), "the platform gates; a stored 'auto-allow' does not lift it"),
                () -> assertEquals(1, harness.approvalRequests().size()));
    }

    @Test
    void aWorkspaceCanMakeItStricterThanTheOffPlatformValue() {
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .responder(callsOnceThenAnswers(SEARCH)).build();
        harness.tenantSetting("guardrails.supervised.readOnlyCalls", "gate");

        harness.chat(GOAL);

        assertAll(
                () -> assertEquals(0, search.executions(), "the workspace asked to be asked every time"),
                () -> assertEquals(1, harness.approvalRequests().size()));
    }

    @Test
    void anAgentCanMakeItStricterThanTheOffPlatformValue() {
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .responder(callsOnceThenAnswers(SEARCH)).build();
        harness.agentSetting("guardrails.supervised.readOnlyCalls", "gate");

        harness.chat(GOAL);

        assertAll(
                () -> assertEquals(0, search.executions()),
                () -> assertEquals(1, harness.approvalRequests().size()));
    }

    @Test
    void aStoredValueThatIsNotAChoiceIsIgnoredAndCannotLoosenTheBaseValue() {
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).responder(callsOnceThenAnswers(SEARCH)).build();
        harness.tenantSetting("guardrails.supervised.readOnlyCalls", "whatever");

        harness.chat(GOAL);

        assertAll(
                () -> assertEquals(0, search.executions(), "an unreadable row is not a way past a platform gate"),
                () -> assertEquals(1, harness.approvalRequests().size()));
    }

    @Test
    void aStoredValueThatIsNotAChoiceIsIgnoredLikeAnyInvalidRowAndThePlatformValueApplies() {
        RecordingTool search = tool(SEARCH);
        harness = supervised(search).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .responder(callsOnceThenAnswers(SEARCH)).build();
        harness.tenantSetting("guardrails.supervised.readOnlyCalls", "whatever");

        harness.chat(GOAL);

        assertEquals(1, search.executions(), "the settings resolver drops the invalid row; the personal platform value stands");
    }

    // ------------------------------------------------------------------- other tiers are unchanged

    @Test
    void theSettingChangesNothingForTheOtherTiers() {
        RecordingTool search = tool(SEARCH);
        harness = ApprovalTurnHarness.builder().autonomy(AgentAutonomyTier.SEMI_AUTONOMOUS).classifications()
                .settingsCatalog().tool(search).guardrailTuning(SupervisedReadOnlyE2ETest::personal)
                .responder(callsOnceThenAnswers(SEARCH)).build();

        harness.chat(GOAL);

        assertEquals(1, search.executions(), "SEMI_AUTONOMOUS never gated a search, with the setting on or off");
        assertTrue(harness.approvalRequests().isEmpty());
    }
}
