package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.turn.ChatFinishProperties;
import com.agenticai.core.agents.turn.ContinuationProperties;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.retry.TransientAiException;

/**
 * The curated behaviour settings of the catalog (docs/settings.md) reach the runtime they claim to steer. Each test runs
 * the real turn pipeline (real {@code AgentChatService}, ChatClient, tool loop, session memory, dispatcher) over a real
 * settings catalog on the harness's own property objects; only the LLM is scripted. The control in every test is the same
 * turn with no override: it must do what the platform property says, so wiring a key changes nothing until it is set.
 */
class BehaviourSettingsTurnE2ETest {

    private static final String QUESTION = "Walk me through setting up billing.";

    private final LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
    private final List<ApprovalTurnHarness> open = new ArrayList<>();

    @AfterEach
    void close() {
        open.forEach(ApprovalTurnHarness::close);
    }

    private ApprovalTurnHarness.Builder base(ScriptedLlm.Responder responder) {
        return ApprovalTurnHarness.builder().settingsCatalog().responder(responder).responseCache(cache);
    }

    private ApprovalTurnHarness built(ApprovalTurnHarness.Builder builder) {
        ApprovalTurnHarness harness = builder.build();
        open.add(harness);
        return harness;
    }

    private static ScriptedLlm.Responder alwaysCutOff() {
        return request -> ScriptedLlm.truncated("part" + request.number() + " ");
    }

    // =========================================================================== chat.finish.*

    @Test
    void theTenantsMaxContinuationsDecideHowManyTimesACutOffReplyIsContinued() {
        ApprovalTurnHarness control = built(base(alwaysCutOff()).finish(p -> p.setMaxContinuations(2)));
        control.chat(QUESTION);

        ApprovalTurnHarness tenant = built(base(alwaysCutOff()).finish(p -> p.setMaxContinuations(2)));
        tenant.tenantSetting("chat.finish.maxContinuations", 0);
        String reply = tenant.chat(QUESTION);

        assertAll(
                () -> assertEquals(3, control.llm().modelCalls(), "control: the platform's two continuations"),
                () -> assertEquals(1, tenant.llm().modelCalls(), "the tenant allows none"),
                () -> assertTrue(reply.endsWith(new ChatFinishProperties().getTruncatedNotice()), reply));
    }

    @Test
    void aTenantCanAllowMoreContinuationsThanThePlatformDoes() {
        ApprovalTurnHarness harness = built(base(alwaysCutOff()).finish(p -> p.setMaxContinuations(1)));
        harness.tenantSetting("chat.finish.maxContinuations", 3);

        harness.chat(QUESTION);

        assertEquals(4, harness.llm().modelCalls(), "one pass and the tenant's three continuations");
    }

    @Test
    void anAgentsOwnMaxContinuationsBeatTheTenantsOnTheStreamedPath() {
        ApprovalTurnHarness harness = built(base(alwaysCutOff()));
        harness.tenantSetting("chat.finish.maxContinuations", 0);
        harness.agentSetting("chat.finish.maxContinuations", 2);

        harness.chat(QUESTION);

        assertEquals(3, harness.llm().modelCalls());
    }

    @Test
    void theBufferedDepthsReadTheSameSetting() {
        ApprovalTurnHarness control = built(base(alwaysCutOff()).depth(ReasoningDepth.CAREFUL).finish(p -> p.setMaxContinuations(2)));
        control.chat(QUESTION);

        ApprovalTurnHarness tenant = built(base(alwaysCutOff()).depth(ReasoningDepth.CAREFUL).finish(p -> p.setMaxContinuations(2)));
        tenant.tenantSetting("chat.finish.maxContinuations", 1);
        tenant.chat(QUESTION);

        assertAll(
                () -> assertEquals(3, control.llm().modelCalls()),
                () -> assertEquals(2, tenant.llm().modelCalls()));
    }

    @Test
    void anAgentCanSwitchFinishHandlingOffAndGetsTheCutOffReplyAsIs() {
        ApprovalTurnHarness harness = built(base(request -> ScriptedLlm.truncated("A long answer that")));
        harness.agentSetting("chat.finish.enabled", false);

        String reply = harness.chat(QUESTION);

        assertAll(
                () -> assertEquals("A long answer that", reply),
                () -> assertEquals(1, harness.llm().modelCalls()));
    }

    @Test
    void theTenantsMaxToolCallRetriesDecideWhetherAMalformedToolCallIsRetried() {
        RecordingTool lookup = RecordingTool.returning("lookup", "finds a thing", "found it");
        ScriptedLlm.Responder malformed = request -> new ScriptedLlm.Reply("", List.of(), "MALFORMED_FUNCTION_CALL");
        ApprovalTurnHarness control = built(base(malformed).tool(lookup));
        control.chat(QUESTION);

        ApprovalTurnHarness tenant = built(base(malformed).tool(RecordingTool.returning("lookup", "finds a thing", "found it")));
        tenant.tenantSetting("chat.finish.maxToolCallRetries", 0);
        String reply = tenant.chat(QUESTION);

        assertAll(
                () -> assertEquals(2, control.llm().modelCalls(), "control: one pass and the platform's one retry"),
                () -> assertEquals(1, tenant.llm().modelCalls(), "the tenant allows no retry"),
                () -> assertEquals(new ChatFinishProperties().getMalformedToolCallNotice(), reply));
    }

    // ============================================================================ chat.cache.*

    private static ScriptedLlm.Responder answersWithoutCallingTheTool() {
        return request -> ScriptedLlm.text("Open Settings, then Billing.");
    }

    @Test
    void anAgentThatOffersToolsIsNotCachedUnlessTheTenantOptsIn() {
        ApprovalTurnHarness control = built(base(answersWithoutCallingTheTool()).tool(RecordingTool.returning("lookup", "finds", "ok")));
        control.chat(QUESTION);
        verify(cache, never()).put(any(), any(), any(), any(), any(), any());

        ApprovalTurnHarness optedIn = built(base(answersWithoutCallingTheTool()).tool(RecordingTool.returning("lookup", "finds", "ok")));
        optedIn.tenantSetting("chat.cache.eligibleWithTools", true);
        optedIn.chat(QUESTION);

        verify(cache, atLeastOnce()).put(any(), any(), any(), eq(QUESTION), eq(""), eq("Open Settings, then Billing."));
    }

    @Test
    void aTenantCanTakeToolAgentsBackOutOfTheCachesWhereThePlatformHasThemIn() {
        ApprovalTurnHarness harness =
                built(base(answersWithoutCallingTheTool())
                        .tool(RecordingTool.returning("lookup", "finds", "ok"))
                        .cache(p -> p.setEligibleWithTools(true)));
        harness.tenantSetting("chat.cache.eligibleWithTools", false);

        harness.chat(QUESTION);

        verify(cache, never()).put(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theNumberOfContextTurnsInTheCacheKeyIsTheTenants() {
        String[] questions = {"first question", "second question", "third question"};
        LlmResponseCacheService platformCache = mock(LlmResponseCacheService.class);
        LlmResponseCacheService tenantCache = mock(LlmResponseCacheService.class);

        ApprovalTurnHarness platform =
                ApprovalTurnHarness.builder().settingsCatalog().responder(answersWithoutCallingTheTool()).responseCache(platformCache)
                        .cache(p -> p.setContextTurns(4)).build();
        open.add(platform);
        for (String q : questions) {
            platform.chat(q);
        }
        ApprovalTurnHarness tenant =
                ApprovalTurnHarness.builder().settingsCatalog().responder(answersWithoutCallingTheTool()).responseCache(tenantCache)
                        .cache(p -> p.setContextTurns(4)).build();
        open.add(tenant);
        tenant.tenantSetting("chat.cache.contextTurns", 1);
        for (String q : questions) {
            tenant.chat(q);
        }

        assertNotEquals(
                lastKeyStoredFor(platformCache, "third question"),
                lastKeyStoredFor(tenantCache, "third question"),
                "the tenant's key covers one earlier turn, the platform's four");
    }

    private static String lastKeyStoredFor(LlmResponseCacheService cache, String question) {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(cache, atLeastOnce()).put(any(), any(), any(), eq(question), key.capture(), any());
        return key.getValue();
    }

    // ================================================================= chat.continuation.heuristicEnabled

    private static ScriptedLlm.Responder stopsAtAnAnnouncedStep() {
        return request -> {
            boolean nudged = request.userTexts().stream().anyMatch(t -> t.contains("stopped before"));
            if (nudged) {
                return ScriptedLlm.text("Ran it, the build is ok. All done.");
            }
            if (request.hasToolResult()) {
                return ScriptedLlm.text("The file is written. Now let me run it:");
            }
            return ScriptedLlm.callTool("c1", "write_file", "{\"to\":\"pom.xml\"}");
        };
    }

    private ApprovalTurnHarness.Builder continuing() {
        return base(stopsAtAnAnnouncedStep())
                .continuationPolicy(p -> p.setHeuristicScope(ContinuationProperties.Scope.ALL_MODELS))
                .tool(RecordingTool.returning("write_file", "Writes a file into the project", "wrote pom.xml"));
    }

    @Test
    void aTenantCanSwitchTheWordingCheckOffSoAnAnnouncedStepIsNotReprompted() {
        ApprovalTurnHarness control = built(continuing());
        String continued = control.chat("Create a pom.xml and build it");

        ApprovalTurnHarness tenant = built(continuing());
        tenant.tenantSetting("chat.continuation.heuristicEnabled", false);
        String stopped = tenant.chat("Create a pom.xml and build it");

        assertAll(
                () -> assertTrue(continued.contains("All done"), continued),
                () -> assertEquals(3, control.llm().modelCalls(), "control: tool round, the stopped reply, one continuation pass"),
                () -> assertEquals(2, tenant.llm().modelCalls()),
                () -> assertTrue(stopped.endsWith("Now let me run it:"), stopped));
    }

    @Test
    void anAgentCanSwitchTheWordingCheckOffAndTheBufferedDepthsFollow() {
        ApprovalTurnHarness tenant = built(continuing().depth(ReasoningDepth.CAREFUL));
        tenant.agentSetting("chat.continuation.heuristicEnabled", false);

        String reply = tenant.chat("Create a pom.xml and build it");

        assertAll(
                () -> assertEquals(2, tenant.llm().modelCalls()),
                () -> assertTrue(reply.contains("Now let me run it:"), reply));
    }

    // ================================================================================ chat.followUp.*

    private static List<RecordingTool> catalog() {
        List<RecordingTool> tools = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            tools.add(RecordingTool.returning("warehouse_" + i, "Handles stock location " + i, "ok"));
        }
        tools.add(RecordingTool.returning("create_invoice", "Creates an invoice for a customer", "{\"invoice\":\"INV-1\"}"));
        return tools;
    }

    private static ScriptedLlm.Responder invoiceTask() {
        return request ->
                request.hasToolResult() || request.userTexts().stream().anyMatch(t -> t.equals("yes"))
                        ? ScriptedLlm.text("Done.")
                        : request.offeredTools().contains("create_invoice")
                                ? ScriptedLlm.callTool("c1", "create_invoice", "{\"to\":\"Acme\"}")
                                : ScriptedLlm.text("I have no tool for that.");
    }

    private ApprovalTurnHarness invoiceAgent() {
        ApprovalTurnHarness.Builder builder = ApprovalTurnHarness.builder().settingsCatalog().responder(invoiceTask());
        catalog().forEach(builder::tool);
        return built(builder);
    }

    private static List<String> toolsOfferedOnTheTurnThatSaid(ApprovalTurnHarness harness, String message) {
        return harness.llm().requests().stream()
                .filter(r -> !r.userTexts().isEmpty() && r.userTexts().get(r.userTexts().size() - 1).equals(message))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no model call for '" + message + "'"))
                .offeredTools();
    }

    @Test
    void aTenantCanSwitchStickyToolsOffSoAFollowUpLosesTheTasksTools() {
        ApprovalTurnHarness control = invoiceAgent();
        control.chat("Create an invoice for Acme");
        control.chat("yes");

        ApprovalTurnHarness tenant = invoiceAgent();
        tenant.tenantSetting("chat.followUp.stickyToolsEnabled", false);
        tenant.chat("Create an invoice for Acme");
        tenant.chat("yes");

        assertAll(
                () -> assertTrue(toolsOfferedOnTheTurnThatSaid(control, "yes").contains("create_invoice"), "control: sticky"),
                () -> assertFalse(toolsOfferedOnTheTurnThatSaid(tenant, "yes").contains("create_invoice")));
    }

    @Test
    void aTenantsShorterStickyWindowForgetsToolsTheConversationUsedLongerAgo() {
        ApprovalTurnHarness control = invoiceAgent();
        control.chat("Create an invoice for Acme");
        control.ageToolCallLog(Duration.ofMinutes(30));
        control.chat("yes");

        ApprovalTurnHarness tenant = invoiceAgent();
        tenant.tenantSetting("chat.followUp.stickyToolsWindow", 600);
        tenant.chat("Create an invoice for Acme");
        tenant.ageToolCallLog(Duration.ofMinutes(30));
        tenant.chat("yes");

        assertAll(
                () -> assertTrue(toolsOfferedOnTheTurnThatSaid(control, "yes").contains("create_invoice"), "control: the platform looks back two hours"),
                () -> assertFalse(toolsOfferedOnTheTurnThatSaid(tenant, "yes").contains("create_invoice"), "the tenant looks back ten minutes"));
    }

    // ========================================================================== routing.simple.*

    @Test
    void whatIsSimpleEnoughForTheEconomyModelIsTheTenantsToDecide() {
        ApprovalTurnHarness control = built(ApprovalTurnHarness.builder().settingsCatalog().costRouting().responder(r -> ScriptedLlm.text("Hello.")));
        control.chat("hi");

        ApprovalTurnHarness tenant = built(ApprovalTurnHarness.builder().settingsCatalog().costRouting().responder(r -> ScriptedLlm.text("Hello.")));
        tenant.tenantSetting("routing.simple.maxChars", 1);
        tenant.chat("hi");

        assertAll(
                () -> assertEquals(List.of(ApprovalTurnHarness.ECONOMY_MODEL), control.modelsRequested()),
                () -> assertEquals(List.of(ApprovalTurnHarness.PRIMARY_MODEL), tenant.modelsRequested(), "nothing is short enough for the tenant"));
    }

    // ============================================================================ chat.retry.*

    private ApprovalTurnHarness.Builder failing(AtomicInteger calls) {
        return ApprovalTurnHarness.builder()
                .settingsCatalog()
                .realErrorTranslation()
                .responder(request -> {
                    calls.incrementAndGet();
                    throw new TransientAiException("503 - overloaded");
                })
                .retry(r -> r.setMaxAttempts(3));
    }

    @Test
    void aTenantsAttemptsPerCallDecideHowOftenAStreamIsRetriedBeforeItsFirstToken() {
        AtomicInteger controlCalls = new AtomicInteger();
        ApprovalTurnHarness control = built(failing(controlCalls));
        assertNotNull(control.chatFailure("Hello"));

        AtomicInteger single = new AtomicInteger();
        ApprovalTurnHarness once = built(failing(single));
        once.tenantSetting("chat.retry.maxAttempts", 1);
        assertNotNull(once.chatFailure("Hello"));

        AtomicInteger more = new AtomicInteger();
        ApprovalTurnHarness five = built(failing(more));
        five.tenantSetting("chat.retry.maxAttempts", 5);
        assertNotNull(five.chatFailure("Hello"));

        assertAll(
                () -> assertEquals(3, controlCalls.get(), "control: the platform's three attempts"),
                () -> assertEquals(1, single.get(), "the tenant allows a single attempt"),
                () -> assertEquals(5, more.get(), "the tenant allows five"));
    }

    // ==================================================================== approvals.continuation.enabled

    private static final String TOOL = "send_invoice";
    private static final String CONFIRMATION = "CONF-9042";

    private static ScriptedLlm.Responder sensibleModel() {
        return request -> {
            if (request.mentions(CONFIRMATION)) {
                return ScriptedLlm.text("Done. The confirmation number is " + CONFIRMATION + ".");
            }
            if (request.hasToolResult()) {
                return ScriptedLlm.text("I'm waiting for your approval before the invoice can be sent.");
            }
            return ScriptedLlm.callTool("call-" + request.number(), TOOL, "{\"to\":\"billing@acme.test\"}");
        };
    }

    private ApprovalTurnHarness gatedInvoiceAgent(boolean platformEnabled) {
        return built(
                ApprovalTurnHarness.builder()
                        .settingsCatalog()
                        .tool(RecordingTool.returning(TOOL, "Sends an invoice", "Invoice sent (confirmation " + CONFIRMATION + ")"))
                        .gateTool(TOOL)
                        .continuation(p -> p.setEnabled(platformEnabled))
                        .responder(sensibleModel()));
    }

    private static boolean continuationStarted(ApprovalTurnHarness harness) {
        return harness.storedTranscript().stream()
                .anyMatch(t -> t.role() == MessageRole.USER && t.content() != null
                        && t.content().startsWith(ApprovalTurnHarness.CONTINUATION_MARKER));
    }

    private static void waitABit() {
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aTenantThatTurnedContinuationOffGetsTheToolRunButNoServerStartedTurn() {
        ApprovalTurnHarness harness = gatedInvoiceAgent(true);
        harness.tenantSetting("approvals.continuation.enabled", false);
        harness.chat("Send invoice INV-7731 to billing@acme.test");
        int callsBefore = harness.llm().modelCalls();

        harness.approvePending();
        waitABit();

        assertAll(
                () -> assertEquals(1, harness.tool(TOOL).executions(), "the approved call still runs"),
                () -> assertEquals(callsBefore, harness.llm().modelCalls(), "but nothing carries on with the task"),
                () -> assertFalse(continuationStarted(harness)));
    }

    @Test
    void anAgentCanOptInToContinuationWhereThePlatformHasItOff() {
        ApprovalTurnHarness harness = gatedInvoiceAgent(false);
        harness.agentSetting("approvals.continuation.enabled", true);
        harness.chat("Send invoice INV-7731 to billing@acme.test");

        harness.approvePending();
        String finalReply = harness.awaitContinuation();

        assertTrue(finalReply.contains(CONFIRMATION), finalReply);
    }

    @Test
    void withNoOverrideThePlatformPropertyDecidesAsBefore() {
        ApprovalTurnHarness off = gatedInvoiceAgent(false);
        off.chat("Send invoice INV-7731 to billing@acme.test");
        int callsBefore = off.llm().modelCalls();
        off.approvePending();
        waitABit();

        ApprovalTurnHarness on = gatedInvoiceAgent(true);
        on.chat("Send invoice INV-7731 to billing@acme.test");
        on.approvePending();
        String reply = on.awaitContinuation();

        assertAll(
                () -> assertEquals(callsBefore, off.llm().modelCalls()),
                () -> assertTrue(reply.contains(CONFIRMATION), reply));
    }
}
