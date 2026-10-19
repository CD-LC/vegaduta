package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The template method: single ask, quota propagation, provider failure hook, and the one-shot repair. */
class AskAndValidateTest {

    private static final WorkflowNodeDto NODE = new WorkflowNodeDto("n1", "x", Map.of(), Map.of());

    /** Replies from a script; a RuntimeException entry is thrown. Records every request. */
    private static final class ScriptedModel implements WorkflowAiModel {
        final LinkedList<Object> script = new LinkedList<>();
        final List<Request> requests = new ArrayList<>();

        ScriptedModel then(Object next) {
            script.add(next);
            return this;
        }

        @Override
        public String complete(Request request) {
            requests.add(request);
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (String) next;
        }
    }

    /** Result = the reply text when it is "good", null when blank; "bad" replies are repairable. */
    private static final class Probe extends AskAndValidate<String> {
        final boolean repairable;
        int failures;

        Probe(boolean repairable) {
            this.repairable = repairable;
        }

        @Override
        String system() {
            return "SYS";
        }

        @Override
        String label() {
            return "Probe";
        }

        @Override
        String validate(String reply) {
            return reply == null || reply.isBlank() ? null : reply;
        }

        @Override
        List<String> issues(String result) {
            return result == null ? List.of("empty") : result.startsWith("bad") ? List.of("bad reply") : List.of();
        }

        @Override
        String repairPrompt(String user, List<String> issues) {
            return repairable ? user + "|fix:" + String.join(",", issues) : null;
        }

        @Override
        void onProviderFailure(RuntimeException failure, List<String> logLines) {
            failures++;
            logLines.add("failed:" + failure.getClass().getSimpleName());
        }
    }

    @Test
    void askSendsSystemUserAndZeroTemperatureAndValidatesTheReply() {
        ScriptedModel model = new ScriptedModel().then("ok");
        AskAndValidate.Attempt<String> a = new Probe(false).ask(model, NODE, "U", 77, "prov", "mod", new ArrayList<>());
        assertEquals("ok", a.result());
        assertFalse(a.providerFailed());
        WorkflowAiModel.Request r = model.requests.get(0);
        assertEquals(new WorkflowAiModel.Request("SYS", "U", 77, 0.0, "prov", "mod"), r);
    }

    @Test
    void providerFailureIsReportedNotRetriedAndHookRuns() {
        ScriptedModel model = new ScriptedModel().then(new IllegalStateException("boom")).then("never");
        Probe probe = new Probe(true);
        List<String> log = new ArrayList<>();
        AskAndValidate.Result<String> r = probe.run(model, NODE, "U", 10, null, null, log);
        assertTrue(r.providerFailed());
        assertNull(r.best());
        assertEquals(1, model.requests.size());
        assertEquals(1, probe.failures);
        assertEquals(List.of("failed:IllegalStateException"), log);
    }

    @Test
    void quotaExceptionsPropagate() {
        ScriptedModel model = new ScriptedModel().then(new TenantLimitExceededException("quota"));
        assertThrows(TenantLimitExceededException.class,
            () -> new Probe(true).run(model, NODE, "U", 10, null, null, new ArrayList<>()));
    }

    @Test
    void goodFirstReplyMakesNoRepairCall() {
        ScriptedModel model = new ScriptedModel().then("fine");
        AskAndValidate.Result<String> r = new Probe(true).run(model, NODE, "U", 10, null, null, new ArrayList<>());
        assertEquals("fine", r.best());
        assertEquals(1, model.requests.size());
    }

    @Test
    void badFirstReplyTriggersExactlyOneRepairWhoseBetterResultWins() {
        ScriptedModel model = new ScriptedModel().then("bad one").then("good").then("never");
        List<String> log = new ArrayList<>();
        AskAndValidate.Result<String> r = new Probe(true).run(model, NODE, "U", 10, null, null, log);
        assertEquals("good", r.best());
        assertEquals(2, model.requests.size());
        assertEquals("U|fix:bad reply", model.requests.get(1).user());
        assertTrue(log.get(0).contains("asking once more"));
    }

    @Test
    void unusableRepairKeepsTheFirstResultAndNoRepairWithoutAPrompt() {
        ScriptedModel model = new ScriptedModel().then("bad one").then("  ");
        assertEquals("bad one", new Probe(true).run(model, NODE, "U", 10, null, null, new ArrayList<>()).best());
        ScriptedModel single = new ScriptedModel().then("bad one").then("good");
        new Probe(false).run(single, NODE, "U", 10, null, null, new ArrayList<>());
        assertEquals(1, single.requests.size());
    }
}
