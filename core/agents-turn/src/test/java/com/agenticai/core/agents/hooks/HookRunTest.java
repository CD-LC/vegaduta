package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/** The model-level and run-level hooks of one turn, on a reply stream. */
class HookRunTest {

    private static final UUID AGENT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final AuditLogService audit = mock(AuditLogService.class);
    private final List<String> webhookPayloads = new ArrayList<>();
    private HookEngine engine;
    private HookCtx ctx;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        HookWebhookClient webhook = new HookWebhookClient(
            url -> Optional.empty(), List.of("hooks.example.com"), "",
            (uri, payload, headers, timeout) -> {
                webhookPayloads.add(payload);
                return new HookWebhookClient.Response(200, "{\"decision\":\"allow\"}");
            });
        ObjectProvider<MeterRegistry> noMeters = provider(null);
        engine = new HookEngine(webhook, new HookRateLimiter(), new HookDecisionSink(provider(audit), noMeters), 2_000, 5_000, Runnable::run);
        ctx = new HookCtx(AGENT, "sess", "tenant-a", null, null, List.of());
    }

    private static EffectiveHook hook(String id, HookPoint point, HookActionType action, Map<String, Object> params) {
        return new EffectiveHook("agent", new HookDefinition(id, true, point, HookMatch.ANY, action, params, FailMode.OPEN, 2_000));
    }

    private HookRun run(List<EffectiveHook> before, List<EffectiveHook> after, List<EffectiveHook> error, List<EffectiveHook> end) {
        return new HookRun(engine, ctx, before, after, error, end);
    }

    private static List<String> collect(Flux<String> f) {
        return f.collectList().block();
    }

    @Test
    void beforeModelBlockRefusesAndTheRefusalIsStillReportedToRunEnd() {
        HookRun run = run(
            List.of(hook("closed", HookPoint.BEFORE_MODEL, HookActionType.BLOCK, Map.of("reason", "We are closed."))),
            List.of(), List.of(), List.of(hook("audit", HookPoint.ON_RUN_END, HookActionType.LOG, Map.of())));

        String refusal = run.beforeModel("hello");

        assertEquals("We are closed.", refusal);
        assertEquals(List.of("We are closed."), collect(run.refusal(refusal)));
        verify(audit).record(anyString(), eq("agent.hook.decision"), eq("agent"), anyString(), org.mockito.ArgumentMatchers.any(), contains("point=ON_RUN_END"));
    }

    @Test
    void appendContextBecomesTheTurnsContextNote() {
        HookRun run = run(
            List.of(hook("tone", HookPoint.BEFORE_MODEL, HookActionType.APPEND_CONTEXT, Map.of("note", "Answer in British English."))),
            List.of(), List.of(), List.of());

        assertNull(run.contextNote(), "nothing until beforeModel has run");
        assertNull(run.beforeModel("hello"));
        assertEquals("Workspace note: Answer in British English.", run.contextNote());
    }

    @Test
    void withNoBeforeModelHooksNothingHappens() {
        HookRun run = run(List.of(), List.of(hook("a", HookPoint.AFTER_MODEL, HookActionType.LOG, Map.of())), List.of(), List.of());

        assertNull(run.beforeModel("hello"));
        assertNull(run.contextNote());
    }

    @Test
    void aRedactingAfterModelHookBuffersAndRedactsAcrossChunkBoundaries() {
        HookRun run = run(List.of(), List.of(
            hook("scrub", HookPoint.AFTER_MODEL, HookActionType.REDACT, Map.of("patterns", List.of("email")))), List.of(), List.of());

        List<String> out = collect(run.wrap(Flux.just("Write to jo@exa", "mple.com ", "today")));

        assertEquals(List.of("Write to [REDACTED_EMAIL] today"), out,
            "the address was split across chunks, which only whole-reply redaction can catch");
    }

    @Test
    void keepAliveChunksPassStraightThroughWhileTheReplyIsHeldBack() {
        HookRun run = run(List.of(), List.of(
            hook("scrub", HookPoint.AFTER_MODEL, HookActionType.REDACT, Map.of("patterns", List.of("email")))), List.of(), List.of());

        List<String> out = collect(run.wrap(Flux.just("", "abc", "")));

        assertEquals(List.of("", "", "abc"), out);
    }

    @Test
    void aBlockingAfterModelHookReplacesTheReply() {
        HookRun run = run(List.of(), List.of(
            hook("stop", HookPoint.AFTER_MODEL, HookActionType.BLOCK, Map.of("reason", "Not for release."))), List.of(), List.of());

        assertEquals(List.of("Not for release."), collect(run.wrap(Flux.just("draft ", "text"))));
    }

    @Test
    void anEmptyReplyIsLeftEmpty() {
        HookRun run = run(List.of(), List.of(
            hook("stop", HookPoint.AFTER_MODEL, HookActionType.BLOCK, Map.of("reason", "Not for release."))), List.of(), List.of());

        assertTrue(collect(run.wrap(Flux.<String>empty())).isEmpty());
    }

    @Test
    void anAfterModelHookThatOnlyLogsKeepsStreaming() {
        HookRun run = run(List.of(), List.of(hook("audit", HookPoint.AFTER_MODEL, HookActionType.LOG, Map.of())), List.of(), List.of());
        List<String> seenWhileStreaming = new ArrayList<>();

        List<String> out = collect(run.wrap(Flux.just("a", "b", "c").doOnNext(seenWhileStreaming::add)));

        assertEquals(List.of("a", "b", "c"), out, "chunks are delivered as they are, not buffered");
        assertEquals(3, seenWhileStreaming.size());
        verify(audit, times(1)).record(anyString(), eq("agent.hook.decision"), eq("agent"), anyString(), org.mockito.ArgumentMatchers.any(), contains("decision=logged"));
    }

    @Test
    void onlyHooksThatCanChangeTheReplyTakeAwayTheResponseCaches() {
        assertEquals(false, run(List.of(hook("n", HookPoint.BEFORE_MODEL, HookActionType.LOG, Map.of())),
            List.of(hook("a", HookPoint.AFTER_MODEL, HookActionType.LOG, Map.of())),
            List.of(hook("e", HookPoint.ON_ERROR, HookActionType.LOG, Map.of())),
            List.of(hook("d", HookPoint.ON_RUN_END, HookActionType.LOG, Map.of()))).needsFreshModelReply(),
            "an audit-only set keeps the caches");
        assertTrue(run(List.of(hook("n", HookPoint.BEFORE_MODEL, HookActionType.APPEND_CONTEXT, Map.of("note", "x"))),
            List.of(), List.of(), List.of()).needsFreshModelReply());
        assertTrue(run(List.of(), List.of(hook("r", HookPoint.AFTER_MODEL, HookActionType.REDACT, Map.of("patterns", List.of("email")))),
            List.of(), List.of()).needsFreshModelReply());
    }

    @Test
    void aWebhookVetoOnTheReplyIsAppliedToTheWholeReply() {
        HookRun run = run(List.of(), List.of(
            hook("dlp", HookPoint.AFTER_MODEL, HookActionType.WEBHOOK, Map.of("url", "https://hooks.example.com/dlp", "includeData", true))),
            List.of(), List.of());

        List<String> out = collect(run.wrap(Flux.just("part one ", "part two")));

        assertEquals(List.of("part one part two"), out, "an allow leaves the reply as it was");
        assertEquals(1, webhookPayloads.size());
        assertTrue(webhookPayloads.get(0).contains("part one part two"));
    }

    @Test
    void onErrorHooksFireOnAStreamErrorAndTheErrorStillPropagates() {
        HookRun run = run(List.of(), List.of(), List.of(hook("page", HookPoint.ON_ERROR, HookActionType.LOG, Map.of())), List.of());

        assertThrows(IllegalStateException.class,
            () -> collect(run.wrap(Flux.<String>error(new IllegalStateException("provider down")))));

        verify(audit).record(anyString(), eq("agent.hook.decision"), eq("agent"), anyString(), org.mockito.ArgumentMatchers.any(),
            contains("reason=IllegalStateException"));
    }

    @Test
    void runEndHooksFireOnCompleteAndOnCancel() {
        HookRun run = run(List.of(), List.of(), List.of(), List.of(hook("done", HookPoint.ON_RUN_END, HookActionType.LOG, Map.of())));

        collect(run.wrap(Flux.just("x")));
        verify(audit).record(anyString(), eq("agent.hook.decision"), eq("agent"), anyString(), org.mockito.ArgumentMatchers.any(), contains("reason=ON_COMPLETE"));

        run.wrap(Flux.just("x", "y", "z")).take(1).blockLast();
        verify(audit).record(anyString(), eq("agent.hook.decision"), eq("agent"), anyString(), org.mockito.ArgumentMatchers.any(), contains("reason=CANCEL"));
    }

    @Test
    void aRunWithoutAnyAfterOrRunHooksIsTheSameFlux() {
        HookRun run = run(List.of(hook("closed", HookPoint.BEFORE_MODEL, HookActionType.LOG, Map.of())), List.of(), List.of(), List.of());
        Flux<String> in = Flux.just("a");

        assertTrue(run.wrap(in) == in, "nothing to add means the stream is returned untouched");
    }
}
