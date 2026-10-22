package com.agenticai.core.chat;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps a {@link ToolCallingManager} to repair tool-call {@code arguments} that aren't valid JSON
 * before they're replayed to the provider as conversation history.
 *
 * <p>Incident (2026-07-28, agent f3022111 on Sarvam): Sarvam's smaller models occasionally emit a
 * {@code function.arguments} string that isn't valid JSON for a tool call it generates. Spring AI's
 * default {@link org.springframework.ai.model.tool.DefaultToolCallingManager} executes the tool and
 * appends that {@link AssistantMessage.ToolCall} verbatim into the next request's conversation
 * history. Sarvam's own API then rejects *its own* prior output on replay - {@code "arguments must
 * be a valid JSON-encoded string"} (HTTP 400) - and because that happens mid-stream (headers
 * already flushed to the browser), the failure can't be turned into a clean error response; the
 * connection just aborts (surfaces to Chrome as {@code net::ERR_QUIC_PROTOCOL_ERROR}). Replacing
 * unparsable arguments with {@code "{}"} here keeps the conversation replayable so the turn can
 * still complete instead of crashing the whole chat stream.
 *
 * <p><b>Second incident, same class of problem (2026-07-29, agent MYSURU_MITRA on Sarvam):</b> the
 * same models also hallucinate tool <em>names</em>. {@code sarvam-105b} asked for {@code
 * generate_image} (a real platform tool, but one only registered when the tenant has an ACTIVE
 * OpenAI credential - this tenant has none) and, a request earlier, for {@code search_web_exa},
 * which exists nowhere in this codebase at all. Spring AI's {@code
 * DefaultToolCallingManager#executeToolCall} throws {@code IllegalStateException: No ToolCallback
 * found for tool name: <name>} for either, which - again mid-stream, headers already flushed -
 * kills the whole turn. Both are recorded in {@code bahuth.llm_call_log} at 2026-07-29 11:11:52Z
 * and 11:11:19Z with exactly that stack.
 *
 * <p>{@link #withFallbacksForUnknownTools} fixes that the same way: a tool call for a name that was
 * never offered is answered with an {@link UnavailableToolCallback} - a plain, actionable
 * explanation the model can relay - instead of being allowed to abort the turn. The fallback is
 * injected into <em>this one in-flight request's</em> options only, so nothing extra is ever
 * advertised in a tool list and no prompt tokens are spent on tools that do not work.
 *
 * <p><b>Applies to every provider, not just Sarvam.</b> Both incidents were diagnosed on Sarvam and
 * this was initially wired into {@link SarvamAgentChatClientFactory} alone, which left the other
 * twelve factories - {@link HostedLlmChatClientFactory} (the PLATFORM_HOSTED pool every FREE-tier
 * agent runs on) among them - still able to hard-fail a turn. Nothing here is Sarvam-specific:
 * emitting malformed arguments and inventing tool names are ordinary small-model behaviours, and
 * the FREE-tier pool is where the smallest models actually get used. Construction now goes through
 * {@link ResilientToolCallingManagerFactory}, which every tool-capable factory in this package
 * injects; see {@code ChatClientFactoryToolCallingWiringTest} for the guard that keeps it that way.
 *
 * <p><b>Per-invocation tool-loop cap (2026-09-28).</b> Spring AI's chat models run their tool loop
 * internally (execute tools, re-call the model, repeat for as long as the model keeps asking) with
 * no iteration limit of their own. The platform's per-turn step counter lives in {@code
 * GuardedToolCallback}, which only sees tools that went through {@code
 * AgentToolProvider#guardAndLog} - the {@link UnavailableToolCallback} fallbacks injected here
 * never touched it, and a model that kept inventing tool names could loop indefinitely. This class
 * therefore counts, statelessly from the prompt itself, how many tool rounds this one model
 * invocation has already run (assistant tool-call messages after the last {@link UserMessage}) and
 * how many of those calls named tools that were never offered. Past either cap it stops the loop in
 * two stages: first a <em>soft stop</em> (every requested call answered, unexecuted, with a "tool
 * budget exhausted - answer now" result so the model can write a normal final reply), then, if the
 * model ignores that, a <em>hard stop</em> via {@code returnDirect} so the chat model emits a fixed
 * closing message instead of calling itself again. Neither stage throws, so a stream whose headers
 * are already flushed still ends cleanly.
 *
 * <p><b>Runtime exceptions become tool results.</b> See {@link
 * RedactingToolExecutionExceptionProcessor} - Spring AI 1.1.7's manager only catches {@link
 * ToolExecutionException}; when {@code translateToolExceptions} is on, every callback is wrapped so
 * any other {@code RuntimeException} is re-thrown as one and comes back to the model as a redacted
 * error instead of killing the turn.
 */
public class ArgumentSanitizingToolCallingManager implements ToolCallingManager {

    private static final Logger log =
            LoggerFactory.getLogger(ArgumentSanitizingToolCallingManager.class);
    private static final String EMPTY_ARGUMENTS = "{}";

    /** Default cap on tool rounds (model -> tools -> model) inside ONE model invocation. */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 25;

    /** Default cap on calls to never-offered tool names inside ONE model invocation. */
    public static final int DEFAULT_MAX_UNKNOWN_TOOL_CALLS = 8;

    /**
     * Machine-readable marker in the soft-stop tool result; also how a later round detects it was
     * already sent.
     */
    static final String TOOL_BUDGET_EXHAUSTED = "tool_budget_exhausted";

    static final String LOOP_LIMIT_METRIC = "chat.tool_loop.limit";
    static final String UNKNOWN_TOOL_METRIC = "chat.tool.unknown_calls";
    static final String ARGUMENT_REJECTED_METRIC = "chat.tool.argument_rejected";

    static final String HARD_STOP_REPLY =
            "I had to stop here: this request needed more tool calls than a single reply is allowed"
                + " to make. Please narrow the request, or ask me to continue from where I left"
                + " off.";

    private final ToolCallingManager delegate;
    private final ObjectMapper objectMapper;
    private final int maxToolRounds;
    private final int maxUnknownToolCalls;
    private final boolean translateToolExceptions;
    private final MeterRegistry meterRegistry;
    private ToolResultClearing resultClearing = ToolResultClearing.disabled();
    private boolean validateArguments;

    /**
     * Enables in-turn tool-result clearing (see {@link ToolResultClearing}); off for direct
     * constructions.
     */
    public ArgumentSanitizingToolCallingManager withResultClearing(ToolResultClearing clearing) {
        this.resultClearing = clearing != null ? clearing : ToolResultClearing.disabled();
        return this;
    }

    /**
     * Checks every call's arguments against the tool's input schema before it runs ({@link
     * ToolArgumentValidator}: must be a JSON object carrying every {@code required} property) and
     * answers a failing call with a precise tool result naming what is missing, which keys the tool
     * does not know and the schema, so the model's next attempt can be right. Off for direct
     * constructions; {@link ResilientToolCallingManagerFactory} turns it on by default ({@code
     * app.chat.tools.argument-validation.enabled}).
     */
    public ArgumentSanitizingToolCallingManager withArgumentValidation(boolean enabled) {
        this.validateArguments = enabled;
        return this;
    }

    private RepeatToolCallGuard repeatGuard = RepeatToolCallGuard.disabled();

    /**
     * Refuses an identical repeat of an idempotent lookup inside one reply ({@link
     * RepeatToolCallGuard}; {@code names} is {@code app.chat.tools.repeat-guard.tools}). Off for
     * direct constructions.
     */
    public ArgumentSanitizingToolCallingManager withRepeatGuard(String names) {
        this.repeatGuard = RepeatToolCallGuard.of(names);
        return this;
    }

    public ArgumentSanitizingToolCallingManager(
            ToolCallingManager delegate, ObjectMapper objectMapper) {
        this(
                delegate,
                objectMapper,
                DEFAULT_MAX_TOOL_ROUNDS,
                DEFAULT_MAX_UNKNOWN_TOOL_CALLS,
                false,
                null);
    }

    /**
     * @param maxToolRounds tool rounds allowed per model invocation; values below 1 fall back to
     *     the default.
     * @param maxUnknownToolCalls calls to never-offered tool names allowed per model invocation;
     *     below 1 falls back to the default.
     * @param translateToolExceptions wrap every callback so a non-{@link ToolExecutionException}
     *     runtime failure reaches the configured exception processor instead of escaping the
     *     manager.
     * @param meterRegistry nullable - loop-limit and unknown-tool counters are skipped without one.
     */
    public ArgumentSanitizingToolCallingManager(
            ToolCallingManager delegate,
            ObjectMapper objectMapper,
            int maxToolRounds,
            int maxUnknownToolCalls,
            boolean translateToolExceptions,
            MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
        this.maxToolRounds = maxToolRounds > 0 ? maxToolRounds : DEFAULT_MAX_TOOL_ROUNDS;
        this.maxUnknownToolCalls =
                maxUnknownToolCalls > 0 ? maxUnknownToolCalls : DEFAULT_MAX_UNKNOWN_TOOL_CALLS;
        this.translateToolExceptions = translateToolExceptions;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ToolExecutionResult result = enforceLoopLimits(prompt, chatResponse);
        if (result == null) {
            result =
                    repeatGuard.answerRepeats(
                            prompt,
                            chatResponse,
                            objectMapper,
                            tool -> increment(RepeatToolCallGuard.METRIC, 1, "tool", tool));
        }
        if (result == null) {
            Prompt executable = withFallbacksForUnknownTools(prompt, chatResponse);
            if (validateArguments) {
                executable = withArgumentValidation(executable);
            }
            if (translateToolExceptions) {
                executable = withExceptionTranslation(executable);
            }
            result = delegate.executeToolCalls(executable, chatResponse);
        }
        return ToolExecutionResult.builder()
                .conversationHistory(
                        result.returnDirect()
                                ? sanitizeHistory(result.conversationHistory(), objectMapper)
                                : resultClearing.apply(
                                        applyRunTransform(
                                                prompt,
                                                sanitizeHistory(
                                                        result.conversationHistory(),
                                                        objectMapper))))
                .returnDirect(result.returnDirect())
                .build();
    }

    /**
     * Applies the run's own {@link ToolLoopHistoryTransform} (token-budget tool-result clearing and
     * compaction, {@code app.context.compaction.*}) when the request carries one in its tool
     * context. A request without the key - every request while the feature is off - gets {@code
     * history} back untouched, and a transform that throws or returns nothing never costs the turn.
     */
    private static List<Message> applyRunTransform(Prompt prompt, List<Message> history) {
        if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return history;
        }
        Map<String, Object> toolContext = options.getToolContext();
        Object candidate =
                toolContext == null
                        ? null
                        : toolContext.get(ToolLoopHistoryTransform.TOOL_CONTEXT_KEY);
        if (!(candidate instanceof ToolLoopHistoryTransform transform)) {
            return history;
        }
        try {
            List<Message> transformed = transform.transform(history, options.getModel());
            return transformed != null ? transformed : history;
        } catch (RuntimeException e) {
            log.warn(
                    "Tool-loop history transform failed; sending the history unchanged: {}",
                    e.toString());
            return history;
        }
    }

    // --- per-invocation loop cap -----------------------------------------

    /**
     * Returns {@code null} when this round is within both caps (the normal path), otherwise the
     * soft- or hard-stop result to hand back instead of executing anything. See the class javadoc
     * for the two stages.
     */
    ToolExecutionResult enforceLoopLimits(Prompt prompt, ChatResponse chatResponse) {
        AssistantMessage requesting = firstToolCallingOutput(chatResponse);
        if (requesting == null) {
            return null;
        }
        Set<String> known = knownToolNames(prompt);

        int priorRounds = 0;
        int unknownCalls = 0;
        boolean softStopAlreadySent = false;
        for (Message message : currentInvocation(prompt.getInstructions())) {
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                priorRounds++;
                unknownCalls += countUnknown(assistant.getToolCalls(), known);
            } else if (message instanceof ToolResponseMessage toolResponses
                    && carriesBudgetMarker(toolResponses)) {
                softStopAlreadySent = true;
            }
        }
        int newUnknown = countUnknown(requesting.getToolCalls(), known);
        if (newUnknown > 0) {
            increment(UNKNOWN_TOOL_METRIC, newUnknown);
        }
        unknownCalls += newUnknown;
        int round = priorRounds + 1;

        boolean roundsExceeded = round > maxToolRounds;
        boolean unknownExceeded = unknownCalls > maxUnknownToolCalls;
        if (!roundsExceeded && !unknownExceeded) {
            return null;
        }
        String reason = roundsExceeded ? "rounds" : "unknown_tools";
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(requesting);
        List<AssistantMessage.ToolCall> calls = requesting.getToolCalls();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());

        if (!softStopAlreadySent) {
            log.warn(
                    "Tool loop limit reached (reason={} round={} maxRounds={} unknownCalls={}"
                        + " maxUnknown={}) - answering {} tool call(s) with a budget-exhausted"
                        + " result instead of executing them",
                    reason,
                    round,
                    maxToolRounds,
                    unknownCalls,
                    maxUnknownToolCalls,
                    calls.size());
            increment(LOOP_LIMIT_METRIC, 1, "stage", "soft", "reason", reason);
            String softStop = softStopResult(priorRounds);
            for (AssistantMessage.ToolCall call : calls) {
                responses.add(
                        new ToolResponseMessage.ToolResponse(call.id(), call.name(), softStop));
            }
            history.add(ToolResponseMessage.builder().responses(responses).build());
            return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .returnDirect(false)
                    .build();
        }

        log.warn(
                "Model kept calling tools after the budget-exhausted notice (reason={} round={}) -"
                    + " ending the tool loop with returnDirect",
                reason,
                round);
        increment(LOOP_LIMIT_METRIC, 1, "stage", "hard", "reason", reason);
        for (int i = 0; i < calls.size(); i++) {
            // ToolExecutionResult.buildGenerations turns each response into one
            // Generation and ChatClient#content() reads only the first, so the
            // closing message goes on the first response and the rest stay
            // empty rather than repeating it in a multi-generation consumer.
            responses.add(
                    new ToolResponseMessage.ToolResponse(
                            calls.get(i).id(), calls.get(i).name(), i == 0 ? HARD_STOP_REPLY : ""));
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(true)
                .build();
    }

    private String softStopResult(int roundsUsed) {
        String instruction =
                "Tool budget for this reply is exhausted after "
                        + roundsUsed
                        + " tool round(s), so this call was NOT executed. Do not call any more"
                        + " tools. Answer the user now using only what you already have, and say"
                        + " briefly if anything is incomplete.";
        try {
            return objectMapper.writeValueAsString(
                    Map.of("error", TOOL_BUDGET_EXHAUSTED, "result", instruction));
        } catch (Exception e) {
            return "{\"error\":\""
                    + TOOL_BUDGET_EXHAUSTED
                    + "\",\"result\":\"Do not call any more tools. "
                    + "Answer the user now.\"}";
        }
    }

    private static boolean carriesBudgetMarker(ToolResponseMessage message) {
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            String data = response.responseData();
            if (data != null && data.contains("\"" + TOOL_BUDGET_EXHAUSTED + "\"")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Messages belonging to THIS model invocation's tool loop: everything after the last {@link
     * UserMessage}. Persisted history from earlier turns sits before it and must not count against
     * this turn's budget.
     */
    private static List<Message> currentInvocation(List<Message> instructions) {
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage) {
                return instructions.subList(i + 1, instructions.size());
            }
        }
        return instructions;
    }

    private static int countUnknown(List<AssistantMessage.ToolCall> calls, Set<String> known) {
        int unknown = 0;
        for (AssistantMessage.ToolCall call : calls) {
            if (call.name() == null || call.name().isBlank() || !known.contains(call.name())) {
                unknown++;
            }
        }
        return unknown;
    }

    private static AssistantMessage firstToolCallingOutput(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null) {
            return null;
        }
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage output = generation == null ? null : generation.getOutput();
            if (output != null && output.hasToolCalls()) {
                return output;
            }
        }
        return null;
    }

    private void increment(String name, int amount, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(name, tags).increment(amount);
        } catch (RuntimeException e) {
            log.debug("Could not record metric {}", name, e);
        }
    }

    // --- argument validation ---------------------------------------------

    /**
     * Copy of {@code prompt} whose callbacks with a checkable input schema are each wrapped in an
     * {@link ArgumentValidatingToolCallback}. Validation happens at call time, per invocation, so a
     * response that calls the same tool twice (once right, once wrong) needs no special case.
     */
    Prompt withArgumentValidation(Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolCallbacks() == null
                || options.getToolCallbacks().isEmpty()) {
            return prompt;
        }
        List<ToolCallback> wrapped = new ArrayList<>(options.getToolCallbacks().size());
        boolean changed = false;
        for (ToolCallback callback : options.getToolCallbacks()) {
            if (callback instanceof ArgumentValidatingToolCallback
                    || callback instanceof UnavailableToolCallback
                    || !ToolArgumentValidator.hasCheckableSchema(
                            callback.getToolDefinition().inputSchema(), objectMapper)) {
                wrapped.add(callback);
                continue;
            }
            wrapped.add(
                    new ArgumentValidatingToolCallback(
                            callback, objectMapper, this::onArgumentsRejected));
            changed = true;
        }
        if (!changed) {
            return prompt;
        }
        ToolCallingChatOptions rewrapped = options.mutate().toolCallbacks(wrapped).build();
        return new Prompt(prompt.getInstructions(), rewrapped);
    }

    private void onArgumentsRejected(String toolName, ToolArgumentValidator.Result result) {
        log.warn(
                "Tool call rejected before execution, the model gets the schema back: tool={}"
                    + " problems={} unknownKeys={}",
                toolName,
                result.problems(),
                result.unknownKeys());
        increment(ARGUMENT_REJECTED_METRIC, 1, "tool", toolName);
    }

    // --- exception translation -------------------------------------------

    /**
     * Copy of {@code prompt} whose callbacks are each wrapped in a {@link
     * ToolExceptionTranslatingCallback}. Leaves the prompt untouched when it carries no callbacks
     * or its options cannot be copied as tool options.
     */
    Prompt withExceptionTranslation(Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolCallbacks() == null
                || options.getToolCallbacks().isEmpty()) {
            return prompt;
        }
        List<ToolCallback> wrapped = new ArrayList<>(options.getToolCallbacks().size());
        for (ToolCallback callback : options.getToolCallbacks()) {
            wrapped.add(
                    callback instanceof ToolExceptionTranslatingCallback
                            ? callback
                            : new ToolExceptionTranslatingCallback(callback));
        }
        // Spring AI 2 options are immutable: mutate() yields a builder seeded with every current
        // value.
        ToolCallingChatOptions rewrapped = options.mutate().toolCallbacks(wrapped).build();
        return new Prompt(prompt.getInstructions(), rewrapped);
    }

    /**
     * Public, static entry point to the same repair {@link #executeToolCalls} applies - see this
     * class's javadoc for the full incident history.
     *
     * <p><b>Gap this closes (2026-08-09):</b> {@link #executeToolCalls} only ever runs when Spring
     * AI's tool-calling loop is actively executing a tool call from the model's IMMEDIATE response
     * - it never touches history loaded from persisted session storage ({@code
     * SessionMemoryService}) and handed to a fresh {@code ChatClient} call as pre-existing
     * messages. A poisoned {@code AssistantMessage.ToolCall} from many turns ago (Sarvam emitting
     * non-JSON arguments once, before this guard existed or on a code path it didn't reach) is
     * persisted as- is and replayed on every SUBSEQENT turn regardless of whether that turn's own
     * model response has any tool calls of its own to trigger {@link #executeToolCalls} at all -
     * confirmed live on a production tenant: a turn 77 messages deep kept 400ing ("arguments must
     * be a valid JSON-encoded string") on a plain follow-up question with no fresh tool call in
     * sight. Callers that load conversation history from persistence (see {@code
     * AgentChatService#chat}) should run it through here before handing it to a {@code ChatClient},
     * the same way this class already repairs the live tool-execution path.
     */
    public static List<Message> sanitizeHistory(List<Message> history, ObjectMapper objectMapper) {
        List<Message> sanitized = new ArrayList<>(history.size());
        for (Message message : history) {
            if (message instanceof AssistantMessage assistantMessage
                    && assistantMessage.hasToolCalls()) {
                sanitized.add(sanitizeAssistantMessage(assistantMessage, objectMapper));
            } else {
                sanitized.add(message);
            }
        }
        return sanitized;
    }

    /**
     * Returns {@code prompt} unchanged unless the model asked for a tool that was never offered, in
     * which case a copy is returned carrying one extra {@link UnavailableToolCallback} per unknown
     * name.
     *
     * <p>Injecting a callback rather than pruning the tool call, or catching the delegate's
     * exception, is deliberate on three counts. It keeps every bit of Spring AI's own execution
     * machinery (observations, the configured {@code ToolExecutionExceptionProcessor},
     * conversation-history assembly) on the normal path instead of re-implementing it here. It
     * handles a response that mixes real and hallucinated tool calls with no special case - the
     * real ones execute exactly as they would have. And it keeps the assistant message's tool calls
     * and the tool responses one-to-one, which OpenAI-compatible providers (Sarvam included)
     * require on replay - dropping the call without answering it would trade this crash for the
     * "provider rejects its own prior output" crash {@link #sanitizeAssistantMessage} exists to
     * prevent.
     *
     * <p>A name is "known" if it is in the request's own callbacks or its {@code toolNames} (which
     * a {@code ToolCallbackResolver} may satisfy from the application context). Anything else is
     * unknown, including the case where the request carried no tools at all - a model volunteering
     * a tool call when it was offered none is precisely the hallucination this guards.
     */
    Prompt withFallbacksForUnknownTools(Prompt prompt, ChatResponse chatResponse) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return prompt;
        }
        Set<String> known = knownToolNames(prompt);

        List<ToolCallback> fallbacks = new ArrayList<>();
        Set<String> alreadyAdded = new LinkedHashSet<>();
        for (AssistantMessage.ToolCall toolCall : requestedToolCalls(chatResponse)) {
            String name = toolCall.name();
            if (name == null || name.isBlank() || known.contains(name) || !alreadyAdded.add(name)) {
                continue;
            }
            UnavailableToolCallback fallback = UnavailableToolCallback.forToolName(name);
            log.warn(
                    "Model requested a tool that was not offered to it: name={} knownTools={} -"
                        + " answering with an explanation instead of failing the turn. Reply to the"
                        + " model: {}",
                    name,
                    known,
                    fallback.message());
            fallbacks.add(fallback);
        }
        if (fallbacks.isEmpty()) {
            return prompt;
        }

        List<ToolCallback> merged =
                options.getToolCallbacks() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(options.getToolCallbacks());
        merged.addAll(fallbacks);
        ToolCallingChatOptions withFallbacks = options.mutate().toolCallbacks(merged).build();
        return new Prompt(prompt.getInstructions(), withFallbacks);
    }

    /**
     * Names this request actually offered: its callbacks plus its resolver-backed {@code
     * toolNames}.
     */
    private static Set<String> knownToolNames(Prompt prompt) {
        Set<String> known = new LinkedHashSet<>();
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return known;
        }
        if (options.getToolCallbacks() != null) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                known.add(callback.getToolDefinition().name());
            }
        }
        return known;
    }

    private static List<AssistantMessage.ToolCall> requestedToolCalls(ChatResponse chatResponse) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        if (chatResponse == null || chatResponse.getResults() == null) {
            return toolCalls;
        }
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage output = generation == null ? null : generation.getOutput();
            if (output != null && output.hasToolCalls()) {
                toolCalls.addAll(output.getToolCalls());
            }
        }
        return toolCalls;
    }

    private static AssistantMessage sanitizeAssistantMessage(
            AssistantMessage assistantMessage, ObjectMapper objectMapper) {
        List<AssistantMessage.ToolCall> repaired =
                new ArrayList<>(assistantMessage.getToolCalls().size());
        boolean changed = false;
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            if (isValidJson(toolCall.arguments(), objectMapper)) {
                repaired.add(toolCall);
                continue;
            }
            changed = true;
            log.warn(
                    "Repairing non-JSON tool-call arguments before replay: tool={}"
                        + " argumentsPreview={}",
                    toolCall.name(),
                    preview(toolCall.arguments()));
            repaired.add(
                    new AssistantMessage.ToolCall(
                            toolCall.id(), toolCall.type(), toolCall.name(), EMPTY_ARGUMENTS));
        }
        if (!changed) {
            return assistantMessage;
        }
        return AssistantMessage.builder()
                .content(assistantMessage.getText())
                .properties(assistantMessage.getMetadata())
                .toolCalls(repaired)
                .media(assistantMessage.getMedia())
                .build();
    }

    private static boolean isValidJson(String arguments, ObjectMapper objectMapper) {
        if (arguments == null || arguments.isBlank()) {
            return false;
        }
        try {
            objectMapper.readTree(arguments);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String preview(String arguments) {
        if (arguments == null) {
            return "null";
        }
        return arguments.length() <= 120 ? arguments : arguments.substring(0, 120) + "...";
    }
}
