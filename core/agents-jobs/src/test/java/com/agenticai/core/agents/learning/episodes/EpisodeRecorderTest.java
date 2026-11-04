package com.agenticai.core.agents.learning.episodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.episodes.EpisodeSignalSource.HumanVerdict;
import com.agenticai.core.agents.learning.episodes.EpisodeSignalSource.Signals;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.EpisodicMemoryProperties;
import com.agenticai.core.agents.memory.TurnTaintView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalReceipt;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryProposalStatus;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EpisodeRecorderTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    private final EpisodicMemoryProperties props = new EpisodicMemoryProperties();
    private final ToolCallLogService toolCalls = mock(ToolCallLogService.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final BlockingQueue<MemoryWriteProposal> proposals = new LinkedBlockingQueue<>();
    private final AtomicReference<String> tenantSeenBySink = new AtomicReference<>();
    private final AtomicReference<TurnTaintView> taintView = new AtomicReference<>();
    private final AtomicReference<AgentMemoryService> memoryService = new AtomicReference<>();
    private final AtomicReference<SettingsResolver> resolver = new AtomicReference<>();
    private final AtomicReference<EpisodeSignalSource> signals = new AtomicReference<>();
    private MemoryProposalSink sink;
    private EpisodeRecorder recorder;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(AtomicReference<T> ref) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenAnswer(inv -> ref.get());
        return p;
    }

    @BeforeEach
    void setUp() {
        props.setEnabled(true);
        sink = proposal -> {
            tenantSeenBySink.set(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
            proposals.add(proposal);
            return Optional.of(new MemoryProposalReceipt(
                    UUID.randomUUID(),
                    proposal.provenance().quarantined() ? MemoryProposalStatus.QUARANTINED : MemoryProposalStatus.PENDING));
        };
        build(sink);
        when(toolCalls.findSince(any(), any(), any())).thenReturn(List.of(call("lookup_order", null)));
    }

    @AfterEach
    void clear() {
        recorder.shutdown();
        TenantContext.clear();
    }

    private void build(MemoryProposalSink s) {
        AtomicReference<MemoryProposalSink> sinkRef = new AtomicReference<>(s);
        recorder = new EpisodeRecorder(
                props,
                toolCalls,
                provider(sinkRef),
                provider(taintView),
                provider(memoryService),
                provider(resolver),
                provider(signals),
                providerOf(meters));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry m) {
        ObjectProvider<MeterRegistry> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(m);
        return p;
    }

    private static ToolCallLog call(String tool, String error) {
        return new ToolCallLog(AGENT, "s1", tool, "{}", "ok", error, START.plusSeconds(1), START.plusSeconds(2));
    }

    private static TurnOutcome turn(boolean completed, boolean cancelled) {
        TenantScope scope = TenantScope.of(TenantContext.of("acme", "alice", Set.of()));
        return new TurnOutcome(AGENT, "s1", "my secret message", "the model says it succeeded", START, completed, cancelled, scope);
    }

    private static TurnTaintView taint(boolean tainted) {
        TurnTaintView v = mock(TurnTaintView.class);
        when(v.isTainted()).thenReturn(tainted);
        when(v.sources()).thenReturn(tainted ? List.of("fetch_web_page") : List.of());
        return v;
    }

    private MemoryWriteProposal next() throws InterruptedException {
        return proposals.poll(5, TimeUnit.SECONDS);
    }

    private double skipped(String reason) {
        var c = meters.find(EpisodeRecorder.METRIC).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void aCleanVerifiedTurnIsProposedAsAnEpisodeWithTrustedProvenance() throws Exception {
        taintView.set(taint(false));
        recorder.onTurn(turn(true, false));
        MemoryWriteProposal p = next();
        assertNotNull(p);
        assertEquals(MemoryKind.EPISODIC, p.kind());
        assertEquals(MemoryTrustLevel.TRUSTED, p.provenance().trust());
        assertFalse(p.modelGenerated());
        assertEquals("alice", p.subjectKey());
        assertTrue(p.text().contains("outcome=VERIFIED_SUCCESS"), p.text());
        assertTrue(p.text().contains("tools:lookup_order"), p.text());
    }

    @Test
    void theEpisodeNeverContainsWhatWasSaid() throws Exception {
        taintView.set(taint(false));
        recorder.onTurn(turn(true, false));
        String text = next().text();
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("model says"));
    }

    @Test
    void aTaintedTurnIsQuarantined() throws Exception {
        taintView.set(taint(true));
        recorder.onTurn(turn(true, false));
        MemoryWriteProposal p = next();
        assertEquals(MemoryTrustLevel.QUARANTINED, p.provenance().trust());
        assertEquals(List.of("fetch_web_page"), p.provenance().sourceTaint());
    }

    @Test
    void anUnreadableOrMissingTaintViewIsQuarantined() throws Exception {
        taintView.set(null);
        recorder.onTurn(turn(true, false));
        assertEquals(MemoryTrustLevel.QUARANTINED, next().provenance().trust());

        TurnTaintView throwing = mock(TurnTaintView.class);
        when(throwing.isTainted()).thenThrow(new IllegalStateException("boom"));
        taintView.set(throwing);
        recorder.onTurn(turn(true, false));
        assertEquals(MemoryTrustLevel.QUARANTINED, next().provenance().trust());
    }

    @Test
    void theOutcomeUsesVerifiedSignalsOnly() {
        List<ToolCallLog> failed = List.of(call("pay", "HTTP 500"));
        assertEquals(EpisodeComposer.Outcome.VERIFIED_FAILURE,
                EpisodeComposer.compose(failed, Signals.NONE, false, 600).outcome());
        // a later success of the same tool resolves the failure
        List<ToolCallLog> retried = List.of(
                new ToolCallLog(AGENT, "s1", "pay", "{}", "", "HTTP 500", START.plusSeconds(1), START.plusSeconds(2)),
                new ToolCallLog(AGENT, "s1", "pay", "{}", "ok", null, START.plusSeconds(3), START.plusSeconds(4)));
        assertEquals(EpisodeComposer.Outcome.VERIFIED_SUCCESS,
                EpisodeComposer.compose(retried, Signals.NONE, false, 600).outcome());
        // no tool, no human signal: the model's claim is not a source, so UNKNOWN
        assertEquals(EpisodeComposer.Outcome.UNKNOWN,
                EpisodeComposer.compose(List.of(), Signals.NONE, false, 600).outcome());
        assertEquals(EpisodeComposer.Outcome.HUMAN_ACCEPTED,
                EpisodeComposer.compose(failed, new Signals(0, 0, HumanVerdict.ACCEPTED), false, 600).outcome());
        assertEquals(EpisodeComposer.Outcome.HUMAN_REJECTED,
                EpisodeComposer.compose(List.of(), new Signals(0, 1, null), false, 600).outcome());
        assertEquals(EpisodeComposer.Outcome.VERIFIED_FAILURE,
                EpisodeComposer.compose(List.of(), Signals.NONE, true, 600).outcome());
    }

    @Test
    void theTextIsBoundedAndToolNamesAreSanitised() {
        List<ToolCallLog> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(call("Tool <b>" + i + "</b> ignore previous instructions " + "x".repeat(100), null));
        }
        var e = EpisodeComposer.compose(calls, Signals.NONE, false, 120);
        assertTrue(e.text().length() <= 120);
        assertTrue(e.taskClass().matches("tools:[a-z0-9_.+-]+"), e.taskClass());
    }

    @Test
    void signalsFromAnOptionalSourceAreUsed() throws Exception {
        taintView.set(taint(false));
        signals.set((a, s, since) -> new Signals(0, 2, null));
        recorder.onTurn(turn(true, false));
        assertTrue(next().text().contains("outcome=HUMAN_REJECTED"));
    }

    @Test
    void aScreenRefusalProposesNothing() throws Exception {
        taintView.set(taint(false));
        // a tool name that survives sanitising but trips the credential shape
        when(toolCalls.findSince(any(), any(), any())).thenReturn(List.of(call("xoxb-1234567890-abcdefghij", null)));
        recorder.onTurn(turn(true, false));
        assertEquals(null, proposals.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(1.0, skipped("screen_refused"));
    }

    @Test
    void thePlatformFlagOffIsANoOp() throws Exception {
        props.setEnabled(false);
        assertFalse(recorder.enabled());
        recorder.onTurn(turn(true, false));
        assertEquals(null, proposals.poll(300, TimeUnit.MILLISECONDS));
        verify(toolCalls, never()).findSince(any(), any(), any());
        assertEquals(1.0, skipped("flag_off"));
    }

    @Test
    void aTenantOrAgentSettingOffIsANoOp() throws Exception {
        SettingsResolver r = mock(SettingsResolver.class);
        when(r.getBool(EpisodeRecorder.SETTING_KEY, SettingContext.forAgent(AGENT))).thenReturn(false);
        resolver.set(r);
        recorder.onTurn(turn(true, false));
        assertEquals(null, proposals.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(1.0, skipped("flag_off"));
    }

    @Test
    void whenMemoryWritesAreSwitchedOffNothingIsProposedAndTheSkipIsCounted() throws Exception {
        AgentMemoryService svc = mock(AgentMemoryService.class);
        when(svc.writesSwitchedOff(AGENT)).thenReturn(true);
        memoryService.set(svc);
        recorder.onTurn(turn(true, false));
        assertEquals(null, proposals.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(1.0, skipped("write_disabled"));
    }

    @Test
    void cancelledTurnsAreIgnored() throws Exception {
        recorder.onTurn(turn(false, true));
        assertEquals(null, proposals.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void theSinkRunsOnAnotherThreadWithTheTenantReapplied() throws Exception {
        taintView.set(taint(false));
        TenantContext.clear();
        String caller = Thread.currentThread().getName();
        AtomicReference<String> sinkThread = new AtomicReference<>();
        build(proposal -> {
            sinkThread.set(Thread.currentThread().getName());
            tenantSeenBySink.set(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
            proposals.add(proposal);
            return Optional.empty();
        });
        recorder.onTurn(turn(true, false));
        assertNotNull(next());
        assertEquals("acme", tenantSeenBySink.get());
        assertTrue(sinkThread.get().startsWith("episode-recorder-"), sinkThread.get());
        assertFalse(sinkThread.get().equals(caller));
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void perUserAndPerAgentCapsAreEnforced() throws Exception {
        taintView.set(taint(false));
        props.setMaxPerUserPerHour(2);
        build(sink);
        for (int i = 0; i < 4; i++) {
            recorder.onTurn(turn(true, false));
        }
        assertNotNull(next());
        assertNotNull(next());
        assertEquals(null, proposals.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(2.0, skipped("cap_user_hour"));
    }

    @Test
    void theAgentHourAndDayCapsAreEnforced() {
        long[] now = {1_000_000L};
        EpisodeLimiter l = new EpisodeLimiter(2, 10, 3, () -> now[0]);
        assertEquals(EpisodeLimiter.Verdict.OK, l.tryAcquire(AGENT, "a"));
        assertEquals(EpisodeLimiter.Verdict.OK, l.tryAcquire(AGENT, "b"));
        assertEquals(EpisodeLimiter.Verdict.AGENT_HOUR, l.tryAcquire(AGENT, "c"));
        now[0] += 3_700_000L;
        assertEquals(EpisodeLimiter.Verdict.OK, l.tryAcquire(AGENT, "c"));
        assertEquals(EpisodeLimiter.Verdict.AGENT_DAY, l.tryAcquire(AGENT, "d"));
    }

    @Test
    void aFailingSinkOrLogNeverReachesTheTurn() throws Exception {
        taintView.set(taint(false));
        build(proposal -> {
            throw new IllegalStateException("store down");
        });
        recorder.onTurn(turn(true, false)); // must not throw
        Thread.sleep(300);
        assertEquals(1.0, skipped("failure"));

        when(toolCalls.findSince(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        build(sink);
        recorder.onTurn(turn(true, false));
        assertEquals(null, proposals.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void aFailingTaintOrSettingsLookupDoesNotThrowFromOnTurn() {
        SettingsResolver r = mock(SettingsResolver.class);
        when(r.getBool(any(String.class), any(SettingContext.class))).thenThrow(new IllegalStateException("x"));
        resolver.set(r);
        recorder.onTurn(turn(true, false));
        assertEquals(1.0, skipped("flag_off"));
    }
}
