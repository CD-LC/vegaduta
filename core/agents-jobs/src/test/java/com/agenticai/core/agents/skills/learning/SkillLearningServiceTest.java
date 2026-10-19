package com.agenticai.core.agents.skills.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.skills.SkillLearningGateway;
import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
import com.agenticai.core.agents.skills.SkillLifecycleService;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.SkillValidationException;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class SkillLearningServiceTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-17T09:00:00Z");

    private static final String GOOD_DRAFT =
            """
            Sure! ```json
            {"worth_saving": true,
             "name": "Monthly GST Summary",
             "description": "Builds a monthly GST summary from the invoice list. Use when the user asks for a GST summary of a month.",
             "instructions": "1. Call list_invoices for the month.\\n2. Call sum_invoices with the tax field.\\n3. Reply with a table of net, tax and gross, then a one-line total. Pitfall: invoices in draft state must be excluded.",
             "rationale": "Recurring month-end request."}
            ```""";

    private ToolCallLogService toolCalls;
    private AgentLabCompletionService lab;
    private SkillService skills;
    private SkillLifecycleService lifecycle;
    private SkillLearningService service;

    private SkillLearningProperties props(boolean enabled, int minCalls, int cooldown, int perDay) {
        return new SkillLearningProperties(enabled, minCalls, cooldown, perDay, 6000);
    }

    @SuppressWarnings("unchecked")
    private SkillLearningService build(SkillLearningProperties props, SkillDraftScrubber platform) {
        ObjectProvider<SkillDraftScrubber> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(platform);
        SkillLearningService s =
                new SkillLearningService(props, toolCalls, lab, skills, lifecycle, provider);
        s.useExecutor(Runnable::run);
        return s;
    }

    @BeforeEach
    void setUp() {
        toolCalls = mock(ToolCallLogService.class);
        lab = mock(AgentLabCompletionService.class);
        skills = mock(SkillService.class);
        lifecycle = mock(SkillLifecycleService.class);
        when(skills.settingsForTurn(AGENT)).thenReturn(new SkillService.AgentSkillSettings(true, Set.of()));
        when(skills.metadataForAgent(AGENT)).thenReturn(List.of());
        service = build(props(true, 3, 30, 3), null);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        SkillLearningGateway.install(null);
    }

    private static ToolCallLog call(String tool, String input, String output, String error, int sec) {
        Instant at = T0.plusSeconds(sec);
        return new ToolCallLog(AGENT, "s1", tool, input, output, error, at, at.plusMillis(200));
    }

    private static List<ToolCallLog> fourGoodCalls() {
        return List.of(
                call("list_invoices", "{\"month\":\"2026-09\"}", "12 invoices", null, 1),
                call("sum_invoices", "{\"field\":\"tax\"}", "4120.50", null, 2),
                call("format_table", "{}", "| net | tax |", null, 3),
                call("send_note", "{}", "ok", null, 4));
    }

    private TurnOutcome outcome(boolean completed, boolean cancelled) {
        return new TurnOutcome(
                AGENT,
                "s1",
                "Give me the GST summary for September",
                "Here is the summary: ...",
                T0,
                completed,
                cancelled,
                TenantScope.of(TenantContext.of("acme", "alice", Set.of())));
    }

    private void toolLog(List<ToolCallLog> calls) {
        when(toolCalls.findSince(eq(AGENT), eq("s1"), any())).thenReturn(calls);
    }

    // ---------------------------------------------------------------- off by default

    @Test
    void theLoopIsOffByDefault() {
        assertFalse(new SkillLearningProperties(false, 3, 30, 3, 6000).enabled());
        SkillLearningService off = build(props(false, 3, 30, 3), null);
        assertFalse(off.enabled());
        off.onTurn(outcome(true, false));
        verify(toolCalls, never()).findSince(any(), anyString(), any());
        verify(lab, never()).complete(any());
    }

    // ---------------------------------------------------------------- a good turn becomes a pending draft

    @Test
    void aSuccessfulMultiStepTurnProducesAPendingDraft() {
        toolLog(fourGoodCalls());
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn(GOOD_DRAFT);

        service.onTurn(outcome(true, false));

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(skills)
                .propose(
                        eq("monthly-gst-summary"),
                        description.capture(),
                        body.capture(),
                        eq("agent:learning:" + AGENT));
        assertTrue(description.getValue().startsWith("Builds a monthly GST summary"));
        assertTrue(body.getValue().contains("sum_invoices"));
        verify(lifecycle)
                .markPending(eq("monthly-gst-summary"), eq(AGENT), org.mockito.ArgumentMatchers.contains("Recurring"));
    }

    @Test
    void theReflectiveCallRunsOnTheAgentsOwnModelWithoutTools() {
        toolLog(fourGoodCalls());
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn("{\"worth_saving\": false}");
        service.onTurn(outcome(true, false));

        ArgumentCaptor<LabCompletionRequest> request = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(lab).complete(request.capture());
        assertEquals(AGENT, request.getValue().agentId());
        assertEquals(SkillLearningService.SYSTEM_PROMPT, request.getValue().systemPrompt());
        assertEquals("alice", request.getValue().subjectKey());
        assertTrue(request.getValue().userMessage().contains("list_invoices"));
        verify(skills, never()).propose(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aModelThatDeclinesOrBabblesStoresNothing() {
        toolLog(fourGoodCalls());
        for (String reply : List.of("I think not.", "{\"worth_saving\": false}", "{ broken json", "", "{\"worth_saving\": true, \"name\": \"x\"}")) {
            service = build(props(true, 3, 0, 50), null);
            when(lab.complete(any(LabCompletionRequest.class))).thenReturn(reply);
            service.onTurn(outcome(true, false));
        }
        verify(skills, never()).propose(anyString(), anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- secrets never get through

    @Test
    void theTranscriptSentToTheModelIsScrubbed() {
        toolLog(
                List.of(
                        call("fetch", "{\"api_key\":\"sk-ABCDEFGHIJKLMNOPQRSTUV1234\",\"to\":\"jane.doe@example.com\"}", "ok", null, 1),
                        call("fetch", "{\"auth\":\"Bearer abcdefghijklmnopqrstuvwxyz0123\"}", "ok", null, 2),
                        call("fetch", "{}", "AKIAABCDEFGHIJKLMNOP leaked", null, 3)));
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn("{\"worth_saving\": false}");

        service.onTurn(outcome(true, false));

        ArgumentCaptor<LabCompletionRequest> request = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(lab).complete(request.capture());
        String sent = request.getValue().userMessage();
        assertFalse(sent.contains("sk-ABCDEFGHIJKLMNOPQRSTUV1234"), sent);
        assertFalse(sent.contains("jane.doe@example.com"), sent);
        assertFalse(sent.contains("abcdefghijklmnopqrstuvwxyz0123"), sent);
        assertFalse(sent.contains("AKIAABCDEFGHIJKLMNOP"), sent);
        assertTrue(sent.contains("[REDACTED]") || sent.contains("[EMAIL]"), sent);
    }

    @Test
    void aSecretThatStraddlesTheFieldCutIsScrubbedBeforeTheCut() {
        // 222 characters of filler, then a bearer token: the 240-character cut lands ten
        // characters into the token, too short for any pattern to recognise if cutting came first.
        String input = "a ".repeat(111) + "Bearer abcdefghijklmnopqrstuvwxyz0123";
        toolLog(
                List.of(
                        call("fetch", input, "ok", null, 1),
                        call("fetch", "{}", "ok", null, 2),
                        call("fetch", "{}", "ok", null, 3)));
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn("{\"worth_saving\": false}");

        service.onTurn(outcome(true, false));

        ArgumentCaptor<LabCompletionRequest> request = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(lab).complete(request.capture());
        assertFalse(request.getValue().userMessage().contains("abcdefghij"), request.getValue().userMessage());
        assertTrue(request.getValue().userMessage().contains("[REDACTED]"));
    }

    @Test
    void theWorkerReadsTheToolLogInTheTenantOfTheTurn() {
        java.util.concurrent.atomic.AtomicReference<String> seen =
                new java.util.concurrent.atomic.AtomicReference<>("none");
        TenantContext.clear();
        when(toolCalls.findSince(any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            seen.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse("no-tenant"));
                            return List.of();
                        });
        service.onTurn(outcome(true, false));
        assertEquals("acme", seen.get());
        assertTrue(TenantContext.current().isEmpty(), "the worker thread is left clean");
    }

    @Test
    void aSecretTheModelEchoesBackIsScrubbedFromTheStoredDraft() {
        toolLog(fourGoodCalls());
        String leaky =
                "{\"worth_saving\": true, \"name\": \"fetch-report\", "
                        + "\"description\": \"Fetches the report from the vendor API. Use when the user asks for the vendor report.\", "
                        + "\"instructions\": \"1. Call fetch with header token=ghp_abcdefghijklmnopqrstuvwxyz0123456789 then 2. read the table and summarise it for the user in three bullet points.\", "
                        + "\"rationale\": \"x\"}";
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn(leaky);

        service.onTurn(outcome(true, false));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(skills).propose(eq("fetch-report"), anyString(), body.capture(), anyString());
        assertFalse(body.getValue().contains("ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
        assertTrue(body.getValue().contains("[REDACTED]"));
    }

    @Test
    void aDraftThatNeededHeavyScrubbingIsDroppedAsOneSpecificCase() {
        toolLog(fourGoodCalls());
        String specific =
                "{\"worth_saving\": true, \"name\": \"call-customers\", "
                        + "\"description\": \"Calls a list of customers about invoices. Use when asked to chase payment.\", "
                        + "\"instructions\": \"Call a@x.com, b@x.com, c@x.com, d@x.com and e@x.com in turn and then write a short summary of who answered and who did not.\", "
                        + "\"rationale\": \"x\"}";
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn(specific);
        service.onTurn(outcome(true, false));
        verify(skills, never()).propose(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void thePlatformScrubberIsAppliedOnTopOfTheBuiltInOne() {
        service.shutdown();
        service = build(props(true, 3, 30, 3), text -> text.replace("list_invoices", "[PLATFORM]"));
        toolLog(fourGoodCalls());
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn("{\"worth_saving\": false}");
        service.onTurn(outcome(true, false));
        ArgumentCaptor<LabCompletionRequest> request = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(lab).complete(request.capture());
        assertFalse(request.getValue().userMessage().contains("list_invoices"));
        assertTrue(request.getValue().userMessage().contains("[PLATFORM]"));
    }

    // ---------------------------------------------------------------- which turns teach anything

    private void assertNoModelCall() {
        verify(lab, never()).complete(any());
        verify(skills, never()).propose(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aTurnThatIsNotMultiStepTeachesNothing() {
        toolLog(fourGoodCalls().subList(0, 2));
        service.onTurn(outcome(true, false));
        assertNoModelCall();
    }

    @Test
    void skillToolsDoNotCountAsSteps() {
        toolLog(
                List.of(
                        call("read_skill_file", "{}", "text", null, 1),
                        call("read_skill_file", "{}", "text", null, 2),
                        call("improve_skill", "{}", "staged", null, 3),
                        call("search", "{}", "text", null, 4)));
        service.onTurn(outcome(true, false));
        assertNoModelCall();
    }

    @Test
    void aFailedOrCancelledTurnTeachesNothing() {
        toolLog(fourGoodCalls());
        service.onTurn(outcome(false, false));
        service.onTurn(outcome(false, true));
        assertNoModelCall();
    }

    @Test
    void aTurnWhereMostToolCallsFailedTeachesNothing() {
        toolLog(
                List.of(
                        call("a", "{}", null, "boom", 1),
                        call("b", "{}", null, "boom", 2),
                        call("c", "{}", "ok", null, 3),
                        call("d", "{}", "ok", null, 4)));
        service.onTurn(outcome(true, false));
        assertNoModelCall();
    }

    @Test
    void aTurnThatAlreadyUsedASkillDoesNotDraftAnother() {
        List<ToolCallLog> calls = new ArrayList<>(fourGoodCalls());
        calls.add(call("load_skill", "{\"name\":\"gst-report\"}", "# Skill: gst-report\n\nSteps", null, 0));
        toolLog(calls);
        service.onTurn(outcome(true, false));
        assertNoModelCall();
    }

    @Test
    void aTurnWhereTheAgentWroteItsOwnSkillIsLeftToThatSkill() {
        List<ToolCallLog> calls = new ArrayList<>(fourGoodCalls());
        calls.add(call("propose_skill", "{}", "saved as draft", null, 5));
        toolLog(calls);
        service.onTurn(outcome(true, false));
        assertNoModelCall();
    }

    @Test
    void anAgentWithSkillsSwitchedOffNeverLearns() {
        when(skills.settingsForTurn(AGENT)).thenReturn(new SkillService.AgentSkillSettings(false, Set.of()));
        toolLog(fourGoodCalls());
        service.onTurn(outcome(true, false));
        assertNoModelCall();
    }

    @Test
    void toolCallsFromEarlierTurnsOfTheSessionAreIgnored() {
        List<ToolCallLog> earlier = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            earlier.add(call("old_tool", "{}", "ok", null, -100 - i));
        }
        toolLog(earlier);
        service.onTurn(outcome(true, false));
        assertNoModelCall();
    }

    @Test
    void aTurnWithoutATenantIsIgnoredRatherThanRunInTheWrongSchema() {
        TurnOutcome noTenant =
                new TurnOutcome(AGENT, "s1", "q", "a", T0, true, false, TenantScope.of((TenantContext) null));
        service.onTurn(noTenant);
        verify(toolCalls, never()).findSince(any(), anyString(), any());
    }

    // ---------------------------------------------------------------- rate limits

    @Test
    void anAgentIsOnlyAskedOncePerCooldown() {
        toolLog(fourGoodCalls());
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn("{\"worth_saving\": false}");
        service.onTurn(outcome(true, false));
        service.onTurn(outcome(true, false));
        service.onTurn(outcome(true, false));
        verify(lab, times(1)).complete(any());
    }

    @Test
    void aDailyCapBoundsSpendEvenWithNoCooldown() {
        service.shutdown();
        service = build(props(true, 3, 0, 2), null);
        toolLog(fourGoodCalls());
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn("{\"worth_saving\": false}");
        for (int i = 0; i < 6; i++) {
            service.onTurn(outcome(true, false));
        }
        verify(lab, times(2)).complete(any());
    }

    @Test
    void theCooldownIsPerAgent() {
        assertTrue(service.claimDraftSlot(AGENT));
        assertFalse(service.claimDraftSlot(AGENT));
        assertTrue(service.claimDraftSlot(UUID.randomUUID()));
    }

    // ---------------------------------------------------------------- failures never reach the turn

    @Test
    void aLimitFailureOrProviderErrorIsSwallowed() {
        toolLog(fourGoodCalls());
        when(lab.complete(any(LabCompletionRequest.class)))
                .thenThrow(new TenantLimitExceededException("monthly token quota"));
        service.onTurn(outcome(true, false));

        service.shutdown();
        service = build(props(true, 3, 0, 50), null);
        when(lab.complete(any(LabCompletionRequest.class))).thenThrow(new IllegalStateException("provider down"));
        service.onTurn(outcome(true, false));
        verify(skills, never()).propose(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aRefusedDraftIsSwallowed() {
        toolLog(fourGoodCalls());
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn(GOOD_DRAFT);
        when(skills.propose(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new SkillValidationException("This workspace already has a skill named x."));
        service.onTurn(outcome(true, false));
        verify(lifecycle, never()).markPending(anyString(), any(), anyString());
    }

    @Test
    void aBrokenToolLogIsSwallowed() {
        when(toolCalls.findSince(any(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));
        service.onTurn(outcome(true, false));
        assertNoModelCall();
    }

    // ---------------------------------------------------------------- effectiveness signal

    @Test
    void aGoodTurnThatLoadedASkillCountsAsASuccessForIt() {
        List<ToolCallLog> calls = new ArrayList<>(fourGoodCalls());
        calls.add(call("load_skill", "{\"name\":\"gst-report\"}", "# Skill: gst-report\n\nSteps", null, 0));
        toolLog(calls);
        service.onTurn(outcome(true, false));
        verify(lifecycle).recordOutcome(Set.of("gst-report"), true);
    }

    @Test
    void anErroredTurnThatLoadedASkillCountsAgainstIt() {
        toolLog(List.of(call("load_skill", "{\"name\":\"gst-report\"}", "# Skill: gst-report\n\nSteps", null, 0)));
        service.onTurn(outcome(false, false));
        verify(lifecycle).recordOutcome(Set.of("gst-report"), false);
    }

    @Test
    void aCancelledTurnIsNeitherAWinNorALoss() {
        toolLog(List.of(call("load_skill", "{\"name\":\"gst-report\"}", "# Skill: gst-report\n\nSteps", null, 0)));
        service.onTurn(outcome(false, true));
        verify(lifecycle, never()).recordOutcome(anyCollection(), anyBoolean());
    }

    @Test
    void aFailedLoadIsNotAUse() {
        Set<String> loaded =
                SkillLearningService.loadedSkills(
                        List.of(
                                call("load_skill", "{\"name\":\"ghost\"}", "There is no skill named \"ghost\" available to you.", null, 1),
                                call("load_skill", "{\"name\":\"paused\"}", "# Skill: paused", "denied", 2),
                                call("load_skill", "not json", "# Skill: x", null, 3),
                                call("load_skill", "{\"name\":\"real\"}", "# Skill: real\n\nbody", null, 4)));
        assertEquals(Set.of("real"), loaded);
    }
}
