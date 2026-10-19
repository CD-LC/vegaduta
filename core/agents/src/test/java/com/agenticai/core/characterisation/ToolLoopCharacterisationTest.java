package com.agenticai.core.characterisation;

import static com.agenticai.core.characterisation.ScriptedChatModel.call;
import static com.agenticai.core.characterisation.ScriptedChatModel.text;
import static com.agenticai.core.characterisation.ScriptedChatModel.toolCalls;
import static com.agenticai.core.characterisation.ToolLoopHarness.usage;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.TenantContextPropagatingToolCallback;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.Disposable;

/**
 * P0 characterisation (ADR-0012, section 6 P0.3): the observable behaviour of the tool loop as the
 * chat path drives it today, asserted at the {@link ChatClient} level over a {@link
 * ScriptedChatModel}.
 *
 * <p>Pinned: argument sanitisation of non-JSON tool-call arguments, redaction of tool exceptions,
 * the per-invocation max-iteration guard (soft then hard stop), {@code ToolContext} propagation,
 * tenant propagation onto the tool-execution thread, {@code returnDirect}, and cancellation.
 *
 * <p>Migration risk this catches: in Spring AI 2 the loop moves from the {@code ChatModel} into the
 * ChatClient's {@code ToolCallingAdvisor}. If {@code ResilientToolCallingManagerFactory} is not
 * re-seated correctly, the sanitiser, the redaction and the round cap silently stop applying and
 * one of these tests fails. Only {@link ToolLoopHarness#client} should need adapting for P3.
 */
class ToolLoopCharacterisationTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ helpers

    private static Tool tool(String name, Function<String, String> body) {
        return new Tool(name, false, body);
    }

    /**
     * Messages of the given type in the LAST prompt the model received (the full loop
     * conversation).
     */
    private static <T extends Message> List<T> lastPromptMessages(
            ScriptedChatModel model, Class<T> type) {
        List<Prompt> prompts = model.prompts();
        List<T> found = new ArrayList<>();
        for (Message message : prompts.get(prompts.size() - 1).getInstructions()) {
            if (type.isInstance(message)) {
                found.add(type.cast(message));
            }
        }
        return found;
    }

    private static String allToolResponseData(ScriptedChatModel model) {
        StringBuilder all = new StringBuilder();
        for (ToolResponseMessage message : lastPromptMessages(model, ToolResponseMessage.class)) {
            message.getResponses().forEach(r -> all.append(r.responseData()).append('\n'));
        }
        return all.toString();
    }

    // -------------------------------------------------------------- sanitising

    @Test
    void nonJsonToolCallArgumentsAreRepairedInTheHistoryTheModelSeesNext() {
        Tool echo = tool("echo", in -> "{\"ok\":true}");
        ScriptedChatModel model =
                new ScriptedChatModel(
                        List.of(
                                toolCalls(usage(10, 5), call("c1", "echo", "this is { not json")),
                                text(usage(20, 5), "all done")),
                        ToolLoopHarness.resilientManager());

        String answer =
                ToolLoopHarness.client(model)
                        .prompt()
                        .user("go")
                        .toolCallbacks(echo)
                        .call()
                        .content();

        assertThat(answer).isEqualTo("all done");
        assertThat(model.modelCalls()).isEqualTo(2);
        List<AssistantMessage> replayed = lastPromptMessages(model, AssistantMessage.class);
        assertThat(replayed).hasSize(1);
        assertThat(replayed.get(0).getToolCalls())
                .singleElement()
                .satisfies(
                        tc -> {
                            assertThat(tc.name()).isEqualTo("echo");
                            assertThat(tc.arguments())
                                    .as("non-JSON arguments must be replayed as an empty object")
                                    .isEqualTo("{}");
                        });
    }

    @Test
    void aToolNameThatWasNeverOfferedIsAnsweredNotFatal() {
        Tool real = tool("real_tool", in -> "{\"ok\":true}");
        ScriptedChatModel model =
                new ScriptedChatModel(
                        List.of(
                                toolCalls(usage(10, 5), call("c1", "invented_tool", "{}")),
                                text(usage(20, 5), "recovered")),
                        ToolLoopHarness.resilientManager());

        String answer =
                ToolLoopHarness.client(model)
                        .prompt()
                        .user("go")
                        .toolCallbacks(real)
                        .call()
                        .content();

        assertThat(answer).isEqualTo("recovered");
        assertThat(real.calls.get()).isZero();
        assertThat(allToolResponseData(model)).contains("invented_tool");
    }

    // --------------------------------------------------------------- redaction

    @Test
    void aToolExceptionBecomesARedactedToolResultAndTheTurnCompletes() {
        Tool failing =
                tool(
                        "flaky",
                        in -> {
                            throw new IllegalStateException(
                                    "upstream 500 for https://x.test/api?token=supersecretvalue123");
                        });
        ScriptedChatModel model =
                new ScriptedChatModel(
                        List.of(
                                toolCalls(usage(10, 5), call("c1", "flaky", "{}")),
                                text(usage(20, 5), "sorry, that failed")),
                        ToolLoopHarness.resilientManager());

        String answer =
                ToolLoopHarness.client(model)
                        .prompt()
                        .user("go")
                        .toolCallbacks(failing)
                        .call()
                        .content();

        assertThat(answer).isEqualTo("sorry, that failed");
        String toolResult = allToolResponseData(model);
        assertThat(toolResult).contains("upstream 500");
        assertThat(toolResult)
                .as("credential-shaped text must never reach the provider")
                .doesNotContain("supersecretvalue123");
    }

    // ------------------------------------------------------ max-iteration guard

    @Test
    void theRoundCapAnswersTheFirstExcessRoundWithABudgetNoticeAndNeverExecutesIt() {
        Tool search = tool("search", in -> "{\"hits\":1}");
        ScriptedChatModel model =
                new ScriptedChatModel(
                        List.of(
                                toolCalls(usage(10, 1), call("c1", "search", "{}")),
                                toolCalls(usage(10, 1), call("c2", "search", "{}")),
                                toolCalls(usage(10, 1), call("c3", "search", "{}")),
                                text(usage(10, 1), "final answer")),
                        ToolLoopHarness.resilientManager(2, 8));

        String answer =
                ToolLoopHarness.client(model)
                        .prompt()
                        .user("go")
                        .toolCallbacks(search)
                        .call()
                        .content();

        assertThat(answer).isEqualTo("final answer");
        assertThat(search.calls.get())
                .as("only the two rounds within the cap execute")
                .isEqualTo(2);
        assertThat(model.modelCalls()).isEqualTo(4);
        assertThat(allToolResponseData(model)).contains("tool_budget_exhausted");
    }

    @Test
    void aModelThatIgnoresTheBudgetNoticeIsEndedWithoutAnotherModelCall() {
        Tool search = tool("search", in -> "{\"hits\":1}");
        ScriptedChatModel model =
                new ScriptedChatModel(
                        List.of(
                                toolCalls(usage(10, 1), call("c1", "search", "{}")),
                                toolCalls(
                                        usage(10, 1),
                                        call(
                                                "c2", "search",
                                                "{}")), // over the cap of 1 -> soft stop
                                toolCalls(
                                        usage(10, 1),
                                        call(
                                                "c3", "search",
                                                "{}")), // ignores the notice -> hard stop
                                text(usage(10, 1), "must never be requested")),
                        ToolLoopHarness.resilientManager(1, 8));

        String answer =
                ToolLoopHarness.client(model)
                        .prompt()
                        .user("go")
                        .toolCallbacks(search)
                        .call()
                        .content();

        assertThat(search.calls.get()).isEqualTo(1);
        assertThat(model.modelCalls())
                .as("the hard stop returns directly instead of calling the model again")
                .isEqualTo(3);
        assertThat(answer).isNotBlank().isNotEqualTo("must never be requested");
    }

    @Test
    void anUnscriptedRunawayLoopFailsLoudlyRatherThanSpinning() {
        // Guards the fixture itself: a loop with no cap must exhaust the script, not hang the
        // suite.
        Tool search = tool("search", in -> "{}");
        List<ScriptedChatModel.Step> steps = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            steps.add(toolCalls(usage(1, 1), call("c" + i, "search", "{}")));
        }
        ScriptedChatModel model =
                new ScriptedChatModel(steps, ToolLoopHarness.resilientManager(100, 100));

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () ->
                        ToolLoopHarness.client(model)
                                .prompt()
                                .user("go")
                                .toolCallbacks(search)
                                .call()
                                .content());
        assertThat(search.calls.get()).isEqualTo(3);
    }

    // ------------------------------------------------------------ ToolContext

    @Test
    void theToolContextMapReachesTheToolOnEveryRound() {
        List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
        Tool aware =
                new Tool("ctx_tool", false, in -> "{\"ok\":true}") {
                    @Override
                    public String call(String toolInput, ToolContext toolContext) {
                        seen.add(toolContext.getContext());
                        return super.call(toolInput);
                    }
                };
        ScriptedChatModel model =
                new ScriptedChatModel(
                        List.of(
                                toolCalls(usage(10, 1), call("c1", "ctx_tool", "{}")),
                                toolCalls(usage(10, 1), call("c2", "ctx_tool", "{}")),
                                text(usage(10, 1), "done")),
                        ToolLoopHarness.resilientManager());

        ToolLoopHarness.client(model)
                .prompt()
                .user("go")
                .toolCallbacks(aware)
                .toolContext(Map.of("tenantId", "t-42", "agentId", "a-7"))
                .call()
                .content();

        assertThat(seen).hasSize(2);
        assertThat(seen)
                .allSatisfy(
                        ctx -> {
                            assertThat(ctx)
                                    .containsEntry("tenantId", "t-42")
                                    .containsEntry("agentId", "a-7");
                        });
    }

    @Test
    void tenantContextFollowsAToolOntoTheStreamingExecutionThread() {
        // Streaming runs tools on a Reactor scheduler thread where the request's ThreadLocal is
        // gone;
        // TenantContextPropagatingToolCallback is what carries it. It must keep doing so after P3.
        TenantContext.set(TenantContext.of("t-42", "user-1", Set.of("ROLE_ADMIN")));
        AtomicReference<String> tenantOnToolThread = new AtomicReference<>();
        AtomicReference<Thread> toolThread = new AtomicReference<>();
        Tool probe =
                tool(
                        "probe",
                        in -> {
                            toolThread.set(Thread.currentThread());
                            tenantOnToolThread.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse("<none>"));
                            return "{\"ok\":true}";
                        });
        ToolCallback wrapped =
                new TenantContextPropagatingToolCallback(
                        probe, TenantContext.current().orElseThrow());
        ScriptedChatModel model =
                new ScriptedChatModel(
                        List.of(
                                toolCalls(usage(10, 1), call("c1", "probe", "{}")),
                                text(usage(10, 1), "done")),
                        ToolLoopHarness.resilientManager());

        List<String> chunks =
                ToolLoopHarness.client(model).prompt().user("go").toolCallbacks(wrapped).stream()
                        .content()
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertThat(String.join("", chunks)).isEqualTo("done");
        assertThat(tenantOnToolThread.get()).isEqualTo("t-42");
        assertThat(toolThread.get())
                .as("the tool must run off the calling thread for this test to mean anything")
                .isNotSameAs(Thread.currentThread());
    }

    // ------------------------------------------------------------ returnDirect

    @Test
    void aReturnDirectToolEndsTheLoopWithItsOwnOutputAndNoSecondModelCall() {
        Tool direct = new Tool("render_card", true, in -> "CARD-OUTPUT");
        ScriptedChatModel model =
                new ScriptedChatModel(
                        List.of(
                                toolCalls(usage(10, 1), call("c1", "render_card", "{}")),
                                text(usage(10, 1), "must never be requested")),
                        ToolLoopHarness.resilientManager());

        String answer =
                ToolLoopHarness.client(model)
                        .prompt()
                        .user("go")
                        .toolCallbacks(direct)
                        .call()
                        .content();

        assertThat(model.modelCalls()).isEqualTo(1);
        assertThat(answer).isEqualTo("CARD-OUTPUT");
    }

    // ------------------------------------------------------------ cancellation

    @Test
    void cancellingTheStreamWhileAToolRunsStopsTheLoop() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        AtomicBoolean toolInterrupted = new AtomicBoolean();
        AtomicInteger toolCompletions = new AtomicInteger();
        Tool slow =
                tool(
                        "slow",
                        in -> {
                            toolStarted.countDown();
                            try {
                                releaseTool.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                toolInterrupted.set(true);
                                Thread.currentThread().interrupt();
                            }
                            toolCompletions.incrementAndGet();
                            return "{\"ok\":true}";
                        });
        ScriptedChatModel model =
                new ScriptedChatModel(
                        List.of(
                                toolCalls(usage(10, 1), call("c1", "slow", "{}")),
                                text(usage(10, 1), "must not be requested after cancel")),
                        ToolLoopHarness.resilientManager());

        List<String> received = new CopyOnWriteArrayList<>();
        Disposable subscription =
                ToolLoopHarness.client(model).prompt().user("go").toolCallbacks(slow).stream()
                        .content()
                        .subscribe(received::add);
        assertThat(toolStarted.await(5, TimeUnit.SECONDS))
                .as("the tool must have started")
                .isTrue();

        subscription.dispose();
        releaseTool.countDown();
        TimeUnit.MILLISECONDS.sleep(400);

        assertThat(subscription.isDisposed()).isTrue();
        assertThat(model.modelCalls())
                .as("no model call may follow a cancelled tool round")
                .isEqualTo(1);
        assertThat(received).isEmpty();
    }

    // -------------------------------------------------------------- test tool

    /** A minimal callback: name, optional returnDirect, body, and a call counter. */
    private static class Tool implements ToolCallback {
        private final String name;
        private final boolean returnDirect;
        private final Function<String, String> body;
        final AtomicInteger calls = new AtomicInteger();

        Tool(String name, boolean returnDirect, Function<String, String> body) {
            this.name = name;
            this.returnDirect = returnDirect;
            this.body = body;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("test tool " + name)
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(returnDirect).build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            return body.apply(toolInput);
        }
    }
}
