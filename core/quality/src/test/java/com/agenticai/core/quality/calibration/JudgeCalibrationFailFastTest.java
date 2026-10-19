package com.agenticai.core.quality.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.calibration.JudgeCalibration.LabeledPair;
import com.agenticai.core.quality.dataset.AgentDefinitionLookup;
import com.agenticai.core.quality.impl.JudgeCallGuard;
import com.agenticai.core.quality.impl.JudgeChatClientFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Prod 2026-10-05 (bugs 1b / 11): a calibration took 91 s and answered "0 of 20 rows judged" with a first
 * line blaming the labels, because every judge call "succeeded" with output nobody could read. Each
 * failure shape - timeout, unreadable output, HTTP error - must now stop the run quickly with an error
 * that names the judge, the count and the FIRST reason.
 */
class JudgeCalibrationFailFastTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    /** A judge whose n-th call (0-based) is produced by {@code behaviour}: a String answer, or a thrown exception. */
    private static ChatClient judge(IntFunction<Object> behaviour, AtomicInteger calls) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenAnswer(inv -> {
            Object result = behaviour.apply(calls.getAndIncrement());
            if (result instanceof RuntimeException e) {
                throw e;
            }
            if (result instanceof Long sleepMs) {
                Thread.sleep(sleepMs);
                return "VERDICT: 1";
            }
            return result;
        });
        when(client.prompt(any(Prompt.class))).thenReturn(spec);
        return client;
    }

    private static JudgeCalibrationService service(ChatClient client, Duration callTimeout) {
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        when(factory.buildJudgeClient(anyInt())).thenReturn(client);
        when(factory.resolveJudgeModelIdOrNull()).thenReturn("OPENROUTER/aion-labs/aion-2.0");
        when(factory.probeUnavailableReason()).thenReturn(Optional.empty());
        when(factory.callTimeout()).thenReturn(callTimeout);
        when(factory.budget()).thenReturn(Duration.ofSeconds(120));
        AgentDefinitionLookup lookup = mock(AgentDefinitionLookup.class);
        when(lookup.find(AGENT_ID)).thenReturn(Optional.empty());
        JudgeCalibrationService svc = new JudgeCalibrationService(factory, lookup);
        ReflectionTestUtils.setField(svc, "enabled", true);
        return svc;
    }

    private static List<LabeledPair> examples(int n) {
        List<LabeledPair> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new LabeledPair("p" + i, "q" + i, "good", "bad", i % 2 == 0 ? "A" : "B"));
        }
        return out;
    }

    private static String reasonOf(ResponseStatusException e) {
        assertEquals(400, e.getStatusCode().value());
        return e.getReason();
    }

    @Test
    void aJudgeThatTimesOutStopsAfterThreeCallsNamingTheTimeout() {
        AtomicInteger calls = new AtomicInteger();
        JudgeCalibrationService svc = service(judge(n -> 10_000L, calls), Duration.ofMillis(200));

        long started = System.nanoTime();
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> svc.calibrate(AGENT_ID, examples(15)));
        long tookMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        String reason = reasonOf(e);
        assertTrue(reason.startsWith("Judge model OPENROUTER/aion-labs/aion-2.0 returned no usable verdicts "
            + "(3 of 3 judge calls failed, stopped after 3 failures in a row: timed out after 0.2 s)"), reason);
        assertEquals(3, calls.get(), "stops after three consecutive failures, not after all 40 calls");
        assertTrue(tookMs < 5_000, "three 200 ms timeouts, not the provider's own 10 s sleeps: " + tookMs);
        assertTrue(!reason.contains("label") && !reason.contains("answer position"), reason);
    }

    @Test
    void unreadableOutputIsCountedAsAFailureAndQuotedNotBlamedOnTheLabels() {
        AtomicInteger calls = new AtomicInteger();
        JudgeCalibrationService svc = service(
            judge(n -> "Both answers are fine, honestly. I would pick whichever you like.", calls), Duration.ofSeconds(5));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> svc.calibrate(AGENT_ID, examples(15)));

        String reason = reasonOf(e);
        assertTrue(reason.contains("returned no usable verdicts (3 of 3 judge calls failed"), reason);
        assertTrue(reason.contains("unparseable output 'Both answers are fine, honestly."), reason);
        assertEquals(3, calls.get());
    }

    @Test
    void anEmptyCompletionIsNamedAsSuch() {
        JudgeCalibrationService svc = service(judge(n -> "", new AtomicInteger()), Duration.ofSeconds(5));

        String reason = reasonOf(assertThrows(ResponseStatusException.class, () -> svc.calibrate(AGENT_ID, examples(12))));

        assertTrue(reason.contains("(3 of 3 judge calls failed, stopped after 3 failures in a row: empty completion)"), reason);
    }

    @Test
    void anHttpErrorNamesItsStatus() {
        JudgeCalibrationService svc = service(judge(
            n -> HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null),
            new AtomicInteger()), Duration.ofSeconds(5));

        String reason = reasonOf(assertThrows(ResponseStatusException.class, () -> svc.calibrate(AGENT_ID, examples(12))));

        assertTrue(reason.contains("3 judge calls failed") || reason.contains("3 of 3 judge calls failed"), reason);
        assertTrue(reason.contains("HTTP 401 Unauthorized"), reason);
    }

    @Test
    void aProviderClientErrorWithTextIsQuoted() {
        JudgeCalibrationService svc = service(judge(n -> new IllegalStateException("model does not exist"),
            new AtomicInteger()), Duration.ofSeconds(5));

        String reason = reasonOf(assertThrows(ResponseStatusException.class, () -> svc.calibrate(AGENT_ID, examples(12))));

        assertTrue(reason.contains("model does not exist"), reason);
    }

    @Test
    void aMixedJudgeStillYieldsAReportWithTheFirstFailureNamed() {
        AtomicInteger calls = new AtomicInteger();
        // call 0 fails, 1 is unreadable, everything after answers (never 3 failures in a row)
        JudgeCalibrationService svc = service(judge(n -> n == 0 ? new IllegalStateException("blip")
            : n == 1 ? "hmm" : "VERDICT: 1", calls), Duration.ofSeconds(5));

        JudgeCalibrationService.CalibrationResponse r = svc.calibrate(AGENT_ID, examples(12));

        assertEquals(24, calls.get());
        assertTrue(r.report().usable() > 0);
        assertTrue(r.report().warnings().stream().anyMatch(w -> w.startsWith("2 judge call(s) failed (first: blip)")),
            r.report().warnings().toString());
    }

    @Test
    void aJudgeThatDiesMidRunStopsAfterSixFailuresInARow() {
        AtomicInteger calls = new AtomicInteger();
        JudgeCalibrationService svc = service(judge(n -> n < 4 ? "VERDICT: 1" : new IllegalStateException("quota exhausted"), calls),
            Duration.ofSeconds(5));

        String reason = reasonOf(assertThrows(ResponseStatusException.class, () -> svc.calibrate(AGENT_ID, examples(15))));

        assertEquals(10, calls.get());
        assertTrue(reason.contains("6 of 10 judge calls failed, stopped after 6 failures in a row: quota exhausted"), reason);
    }

    @Test
    void aHealthyJudgeIsUnaffected() {
        AtomicInteger calls = new AtomicInteger();
        JudgeCalibrationService svc = service(judge(n -> "VERDICT: 1", calls), Duration.ofSeconds(5));

        JudgeCalibrationService.CalibrationResponse r = svc.calibrate(AGENT_ID, examples(10));

        assertEquals(20, calls.get());
        assertEquals(10, r.report().usable());
    }

    @Test
    void verdictParsingToleratesMarkdownAndTakesTheFinalAnswer() {
        assertEquals(Integer.valueOf(1), JudgeCalibrationService.parseVerdict("VERDICT: 1"));
        assertEquals(Integer.valueOf(2), JudgeCalibrationService.parseVerdict("**VERDICT: 2**"));
        assertEquals(Integer.valueOf(2), JudgeCalibrationService.parseVerdict("Reasoning...\nverdict = 2"));
        assertEquals(Integer.valueOf(1), JudgeCalibrationService.parseVerdict("Format is VERDICT: 2.\nVERDICT: 1"));
        org.junit.jupiter.api.Assertions.assertNull(JudgeCalibrationService.parseVerdict("Response 1 is better"));
        org.junit.jupiter.api.Assertions.assertNull(JudgeCalibrationService.parseVerdict(null));
    }

    @Test
    void theOverallBudgetStopsARunThatIsMerelySlow() throws Exception {
        JudgeCallGuard.Tracker tracker = new JudgeCallGuard.Tracker("OPENAI/j", "judge calls", 40,
            Duration.ofMillis(20), 3, 6);
        tracker.success();
        Thread.sleep(60);

        JudgeCallGuard.JudgeRunAbortedException e = assertThrows(JudgeCallGuard.JudgeRunAbortedException.class,
            tracker::checkBudget);

        assertTrue(e.getMessage().contains("Judge model OPENAI/j returned no usable verdicts"), e.getMessage());
        assertTrue(e.getMessage().contains("overall budget"), e.getMessage());
    }
}
