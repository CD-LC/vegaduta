package com.agenticai.core.chat;

import com.agenticai.core.chat.AgentTurn.Message;
import com.agenticai.core.chat.AgentTurn.Tool;
import com.agenticai.core.chat.AgentTurn.ToolCall;
import com.agenticai.core.chat.gateway.GatewayGovernanceAdvisor;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.security.TenantLimitExceededException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * One model turn for a CLIENT-SIDE tool-calling agent, served from the platform's
 * hosted LLM pool: the IDE plugins' coding agent can pick "VegaDuta hosted" as its
 * model instead of a local server or its own key.
 *
 * <p><b>The server never runs the tools.</b> The request carries tool definitions;
 * the call goes out with {@code internalToolExecutionEnabled(false)} and
 * definition-only {@link ToolCallback}s whose {@code call} can never succeed, so a
 * model-requested call comes back to the client as data. (Spring AI's default is to
 * execute registered callbacks itself and loop; that default would be the bug here -
 * the files and the shell live on the user's machine.)
 *
 * <p><b>Governed like hosted chat.</b> It reuses, rather than reimplements:
 * <ul>
 *   <li>the monthly pool token quota and per-user dollar budget, through the same
 *       {@link FimCompletionEntitlements} port FIM uses (see there for why it is a
 *       port). Reserved up front at <i>estimated prompt + max completion</i> tokens,
 *       because an agent turn's prompt (a conversation full of file contents) is the
 *       dominant cost, unlike FIM's tiny ones, and settled with the provider's real
 *       usage. There is no unmetered path: with no adapter registered this refuses;</li>
 *   <li>every {@link GatewayGovernanceAdvisor} bean (tenant content policy, prompt
 *       injection screening, response PII flag) - the same set the hosted-pool chat
 *       path attaches, which matters more here than anywhere: tool results are file
 *       contents from a repository the user may have just cloned;</li>
 *   <li>{@link HostedLlmChatClientFactory}, whose {@code LlmMetricsAdvisor} writes the
 *       llm call log rows, tagged PLATFORM_HOSTED.</li>
 * </ul>
 *
 * <p><b>Differences from FIM, on purpose.</b> FIM is a keystroke loop: cheapest row, one
 * shot, a 1.5s deadline, empty answer on failure. A coding-agent turn is the opposite -
 * a wrong or missing answer wastes a whole step, and nobody is typing. So this walks the
 * pool in the OPERATOR'S priority order (not cheapest: the cheapest row is the worst
 * choice for tool calling, and the pool carries no capability column to rank on), fails
 * over to the next row when one errors, and has no wall-clock deadline - it blocks the
 * request thread for the provider's own read timeout. Concurrency is therefore bounded
 * by a semaphore instead: a request that cannot start immediately is refused with 429
 * rather than parked behind slow upstream calls. Tenant context never crosses a thread
 * hop (the call runs on the request thread), so there is no propagation to get wrong.
 *
 * <p><b>Failover and billing.</b> A row that throws produced no billable completion by
 * our contract, so failing over does not double-reserve: one lease covers the turn and is
 * settled by whichever row finally answered, or abandoned when none did. Content-policy
 * violations and quota rejections are NOT failed over - another model would answer the
 * same forbidden prompt - they propagate to the platform's standard handler.
 */
@Service
public class AgentTurnService {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnService.class);
    private static final ObjectMapper JSON = JsonMappers.plain();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    /** Rough chars-per-token for the up-front reservation only; settlement uses real usage. */
    private static final int CHARS_PER_TOKEN = 4;

    private final ObjectProvider<FimCompletionEntitlements> entitlementsProvider;
    private final PlatformHostedLlmProviderService hostedProviders;
    private final HostedLlmChatClientFactory chatClientFactory;
    private final ObjectProvider<GatewayGovernanceAdvisor> governanceAdvisors;

    private final int defaultMaxTokens;
    private final int maxTokensCeiling;
    private final double defaultTemperature;
    private final Semaphore concurrency;

    @Nullable
    private volatile FimCompletionEntitlements entitlements;

    /**
     * Classifies a failed row's exception ({@link ProviderErrorTranslator}) so {@link
     * ProviderFailurePolicy} can tell an input-shaped failure from a row-shaped one. Built here, without
     * the Smart AI error explainer: a failover decision must never spend an LLM call.
     */
    private final ProviderErrorTranslator failureTranslator = new ProviderErrorTranslator(JSON, null);

    /** What to do when a tool call arrives with unusable arguments; setter-injected, defaults built in. */
    private volatile AgentTurnRepairProperties repair = new AgentTurnRepairProperties();

    @Autowired(required = false)
    void useRepairProperties(AgentTurnRepairProperties properties) {
        this.repair = properties;
    }

    public AgentTurnService(
        ObjectProvider<FimCompletionEntitlements> entitlements,
        PlatformHostedLlmProviderService hostedProviders,
        HostedLlmChatClientFactory chatClientFactory,
        ObjectProvider<GatewayGovernanceAdvisor> governanceAdvisors,
        @Value("${app.agent-turn.max-tokens.default:2048}") int defaultMaxTokens,
        @Value("${app.agent-turn.max-tokens.ceiling:4096}") int maxTokensCeiling,
        @Value("${app.agent-turn.temperature:0.2}") double defaultTemperature,
        @Value("${app.agent-turn.max-concurrent:16}") int maxConcurrent
    ) {
        this.entitlementsProvider = entitlements;
        this.hostedProviders = hostedProviders;
        this.chatClientFactory = chatClientFactory;
        this.governanceAdvisors = governanceAdvisors;
        this.defaultMaxTokens = Math.max(1, defaultMaxTokens);
        this.maxTokensCeiling = Math.max(this.defaultMaxTokens, maxTokensCeiling);
        this.defaultTemperature = Math.max(0.0, Math.min(2.0, defaultTemperature));
        this.concurrency = new Semaphore(Math.max(1, maxConcurrent));
        this.entitlements = entitlements.getIfAvailable();
        if (this.entitlements == null) {
            log.error("No FimCompletionEntitlements bean is registered - POST /api/completions/agent-turn will refuse "
                + "every request until one appears; it spends platform-paid hosted inference and must not run unmetered.");
        }
    }

    /** No entitlements adapter on the context - mapped to 503 by the controller. */
    public static final class EntitlementsUnavailableException extends IllegalStateException {
        EntitlementsUnavailableException(String message) {
            super(message);
        }
    }

    /** The hosted pool has no enabled row - 503. */
    public static final class NoModelAvailableException extends IllegalStateException {
        NoModelAvailableException(String message) {
            super(message);
        }
    }

    /** All concurrency slots are taken - 429. */
    public static final class BusyException extends IllegalStateException {
        BusyException(String message) {
            super(message);
        }
    }

    /** Every pool row failed - 502. */
    public static final class UpstreamFailedException extends IllegalStateException {
        UpstreamFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public AgentTurn.Result turn(AgentTurn.Request request) {
        FimCompletionEntitlements adapter = entitlements();
        if (adapter == null) {
            throw new EntitlementsUnavailableException(
                "Hosted agent turns are unavailable: no entitlements adapter is registered, and this endpoint "
                    + "must not serve platform-paid inference unmetered.");
        }
        int maxTokens = clampMaxTokens(request.maxTokens());
        double temperature = request.temperature() == null
            ? defaultTemperature : Math.max(0.0, Math.min(2.0, request.temperature()));

        // Gate BEFORE any provider is resolved or any key decrypted.
        FimCompletionEntitlements.Lease lease = adapter.reserve(estimatePromptTokens(request) + maxTokens);

        if (!concurrency.tryAcquire()) {
            lease.abandon();
            throw new BusyException("The hosted agent is handling its maximum number of turns - retry in a moment.");
        }
        try {
            return runChain(request, maxTokens, temperature, lease);
        } finally {
            concurrency.release();
        }
    }

    private AgentTurn.Result runChain(AgentTurn.Request request, int maxTokens, double temperature,
                                      FimCompletionEntitlements.Lease lease) {
        List<HostedProviderSelection> chain;
        try {
            chain = orderChain(hostedProviders.resolveEnabledChain(), request.model());
        } catch (RuntimeException e) {
            lease.abandon();
            throw e;
        }
        if (chain.isEmpty()) {
            lease.abandon();
            throw new NoModelAvailableException("No hosted model is enabled for this platform right now.");
        }

        List<org.springframework.ai.chat.messages.Message> messages;
        ToolCallingChatOptions options;
        try {
            messages = toSpringMessages(request.messages());
            options = toolOptions(request.tools());
        } catch (RuntimeException e) {
            lease.abandon();
            throw e;
        }

        RuntimeException last = null;
        for (HostedProviderSelection selection : chain) {
            String model = selection.provider().getModel();
            try {
                AgentTurn.Result result =
                    answer(buildClient(selection, maxTokens, temperature), messages, options, model);
                lease.settle(model,
                    result.usage() == null ? null : result.usage().promptTokens(),
                    result.usage() == null ? null : result.usage().completionTokens());
                return result;
            } catch (RuntimeException e) {
                if (isTerminalForTheTurn(e)) {
                    lease.abandon();
                    throw e;
                }
                // A failure that is a property of the INPUT (an over-long prompt, a payload too large,
                // a content-policy refusal) is rejected the same way by every row: walking the rest of
                // the pool only multiplies latency and spend before the same answer. Row-shaped
                // failures (key, billing, model id, capacity, 5xx) still move on.
                UpstreamProviderException classified = failureTranslator.translate(e);
                if (!ProviderFailurePolicy.isRetryableOnAnotherProvider(classified)) {
                    lease.abandon();
                    log.warn("Agent turn: pool row {} failed with an input-shaped error ({}) - not trying the"
                        + " other rows, they would reject it the same way", model, classified.getMessage());
                    throw classified;
                }
                last = e;
                log.warn("Agent turn: pool row {} failed ({}) - trying the next row", model, e.toString());
            }
        }
        lease.abandon();
        throw new UpstreamFailedException("Every hosted model failed to answer this turn.", last);
    }

    /** Package-private seam: tests substitute the provider call without mocking ChatClient's fluent chain. */
    @Nullable
    ChatResponse callModel(
        ChatClient client, List<org.springframework.ai.chat.messages.Message> messages, ToolCallingChatOptions options
    ) {
        return client.prompt().messages(messages).options(options).call().chatResponse();
    }

    private ChatClient buildClient(HostedProviderSelection selection, int maxTokens, double temperature) {
        ChatClient client = chatClientFactory.create(
            selection.provider(), selection.decryptedApiKey(), maxTokens, temperature, 1.0);
        List<Advisor> extra = new ArrayList<>(governanceAdvisors.orderedStream().toList());
        return extra.isEmpty() ? client : client.mutate().defaultAdvisors(extra.toArray(Advisor[]::new)).build();
    }

    /** Quota rejections and policy blocks are about the request, not the row - no failover. */
    private static boolean isTerminalForTheTurn(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof LlmGatewayContentPolicyViolationException || t instanceof TenantLimitExceededException) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    int clampMaxTokens(@Nullable Integer requested) {
        if (requested == null || requested <= 0) {
            return defaultMaxTokens;
        }
        return Math.min(requested, maxTokensCeiling);
    }

    static int estimatePromptTokens(AgentTurn.Request request) {
        long chars = 0;
        for (Message m : request.messages()) {
            chars += m.content() == null ? 0 : m.content().length();
            if (m.toolCalls() != null) {
                for (ToolCall call : m.toolCalls()) {
                    chars += 40 + String.valueOf(call.args()).length();
                }
            }
        }
        if (request.tools() != null) {
            for (Tool tool : request.tools()) {
                chars += 80 + (tool.description() == null ? 0 : tool.description().length())
                    + String.valueOf(tool.parameters()).length();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE / 2, chars / CHARS_PER_TOKEN + 1);
    }

    /** A client may steer between ENABLED rows; an unknown id leaves the operator's order alone. */
    static List<HostedProviderSelection> orderChain(List<HostedProviderSelection> chain, @Nullable String preferred) {
        if (preferred == null || preferred.isBlank() || chain.size() < 2) {
            return chain;
        }
        String wanted = preferred.trim();
        List<HostedProviderSelection> ordered = new ArrayList<>(chain.size());
        chain.stream().filter(s -> wanted.equals(trim(s.provider().getModel()))).findFirst().ifPresent(ordered::add);
        for (HostedProviderSelection s : chain) {
            if (!ordered.contains(s)) {
                ordered.add(s);
            }
        }
        return ordered;
    }

    private static String trim(@Nullable String value) {
        return value == null ? null : value.trim();
    }

    /**
     * Wire messages to Spring AI messages. Consecutive tool results are merged into ONE
     * {@link ToolResponseMessage}: providers that group function responses (Gemini) need
     * every result for one assistant turn together, and OpenAI-style providers split them
     * back out themselves.
     */
    static List<org.springframework.ai.chat.messages.Message> toSpringMessages(List<Message> wire) {
        List<org.springframework.ai.chat.messages.Message> out = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> pendingTools = new ArrayList<>();
        for (Message m : wire) {
            String role = m.role() == null ? "" : m.role();
            if (!"tool".equals(role) && !pendingTools.isEmpty()) {
                out.add(ToolResponseMessage.builder().responses(List.copyOf(pendingTools)).build());
                pendingTools.clear();
            }
            String content = m.content() == null ? "" : m.content();
            switch (role) {
                case "system" -> out.add(new SystemMessage(content));
                case "user" -> out.add(new UserMessage(content));
                case "assistant" -> out.add(AssistantMessage.builder()
                    .content(content)
                    .toolCalls(toSpringToolCalls(m.toolCalls()))
                    .build());
                case "tool" -> pendingTools.add(new ToolResponseMessage.ToolResponse(
                    m.toolCallId() == null ? "" : m.toolCallId(),
                    m.name() == null || m.name().isBlank() ? "tool" : m.name(),
                    content));
                default -> throw new IllegalArgumentException("unsupported message role \"" + role + "\"");
            }
        }
        if (!pendingTools.isEmpty()) {
            out.add(ToolResponseMessage.builder().responses(List.copyOf(pendingTools)).build());
        }
        return out;
    }

    private static List<AssistantMessage.ToolCall> toSpringToolCalls(@Nullable List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> out = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            out.add(new AssistantMessage.ToolCall(call.id(), "function", call.name(), toJson(call.args())));
        }
        return out;
    }

    static ToolCallingChatOptions toolOptions(@Nullable List<Tool> tools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (tools != null) {
            for (Tool tool : tools) {
                callbacks.add(new DefinitionOnlyToolCallback(tool));
            }
        }
        // internalToolExecutionEnabled(false) is the whole feature: the model's tool calls
        // are returned to the caller instead of being run (and looped on) by Spring AI.
        return ToolCallingChatOptions.builder()
            .toolCallbacks(callbacks)
            .internalToolExecutionEnabled(false)
            .build();
    }

    /**
     * One row's answer. A tool call whose arguments are unusable (invalid JSON, typically cut off at
     * the output limit) is never handed to the client with empty arguments - it would be run that way.
     * Instead the model is shown that error as the call's RESULT and asked again, up to
     * {@link AgentTurnRepairProperties#getMaxAttempts()} times; if the calls are still unusable they
     * are dropped and the client is told in the reply text. Usage of every call made is summed, so the
     * turn is settled for what it cost.
     */
    private AgentTurn.Result answer(
        ChatClient client, List<org.springframework.ai.chat.messages.Message> messages,
        ToolCallingChatOptions options, String model
    ) {
        AgentTurnRepairProperties repairProperties = repair;
        List<org.springframework.ai.chat.messages.Message> conversation = messages;
        AgentTurn.Usage total = null;
        for (int attempt = 0; ; attempt++) {
            Reply reply = read(callModel(client, conversation, options));
            total = add(total, reply.usage());
            if (reply.unusableCalls().isEmpty()) {
                return reply.toResult(model, total);
            }
            if (attempt >= repairProperties.getMaxAttempts()) {
                log.warn("Agent turn: model {} sent tool call(s) {} with unusable arguments and {} repair attempt(s) "
                    + "did not fix it - dropping the calls", model, reply.unusableNames(), attempt);
                return reply.withoutCalls(model, total, repairProperties);
            }
            log.info("Agent turn: model {} sent tool call(s) {} with unusable arguments (finish {}) - handing the "
                + "model the error as the result and asking again ({}/{})", model, reply.unusableNames(),
                reply.finish(), attempt + 1, repairProperties.getMaxAttempts());
            conversation = withRepairExchange(conversation, reply, repairProperties);
        }
    }

    /** The conversation extended with the broken reply and an error result for each of its calls. */
    private static List<org.springframework.ai.chat.messages.Message> withRepairExchange(
        List<org.springframework.ai.chat.messages.Message> conversation, Reply reply, AgentTurnRepairProperties repair
    ) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
        for (ReadCall call : reply.calls()) {
            // The unusable arguments are replaced by {} in the replayed call: providers (Gemini)
            // refuse to replay a call whose arguments are not valid JSON.
            calls.add(new AssistantMessage.ToolCall(call.id(), "function", call.name(),
                call.args().valid() ? call.rawArguments() : "{}"));
            String text = call.args().valid()
                ? repair.render(repair.getSkippedMessage(), call.name(), "")
                : repair.render(repair.getInvalidArgumentsMessage(), call.name(),
                    reply.finishReason() == FinishReason.LENGTH
                        ? repair.getInvalidReasonCutOff() : repair.getInvalidReasonMalformed());
            results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), text));
        }
        List<org.springframework.ai.chat.messages.Message> next = new ArrayList<>(conversation);
        next.add(AssistantMessage.builder().content(reply.text()).toolCalls(calls).build());
        next.add(ToolResponseMessage.builder().responses(results).build());
        return next;
    }

    private static AgentTurn.Usage add(@Nullable AgentTurn.Usage total, @Nullable AgentTurn.Usage next) {
        if (next == null) {
            return total;
        }
        if (total == null) {
            return next;
        }
        return new AgentTurn.Usage(sum(total.promptTokens(), next.promptTokens()),
            sum(total.completionTokens(), next.completionTokens()));
    }

    @Nullable
    private static Long sum(@Nullable Long a, @Nullable Long b) {
        return a == null ? b : b == null ? a : Long.valueOf(a + b);
    }

    /** What one model response said, with each tool call's arguments checked. */
    private record Reply(String text, List<ReadCall> calls, String rawFinish, @Nullable AgentTurn.Usage usage) {

        FinishReason finishReason() {
            return FinishReason.of(rawFinish);
        }

        String finish() {
            return rawFinish;
        }

        List<ReadCall> unusableCalls() {
            return calls.stream().filter(c -> !c.args().valid()).toList();
        }

        List<String> unusableNames() {
            return unusableCalls().stream().map(ReadCall::name).toList();
        }

        AgentTurn.Result toResult(String model, @Nullable AgentTurn.Usage total) {
            List<ToolCall> wire = new ArrayList<>();
            for (ReadCall call : calls) {
                wire.add(new ToolCall(call.id(), call.name(), call.args().value()));
            }
            return new AgentTurn.Result(text, wire, model, wireFinish(finishReason(), rawFinish, !wire.isEmpty()), total);
        }

        /** Every call dropped (a partly run batch is worse than none), the reason given in the text. */
        AgentTurn.Result withoutCalls(String model, @Nullable AgentTurn.Usage total, AgentTurnRepairProperties repair) {
            String reason = finishReason() == FinishReason.LENGTH
                ? repair.getInvalidReasonCutOff() : repair.getInvalidReasonMalformed();
            String notice = repair.render(repair.getUnusableCallsNotice(), String.join(", ", unusableNames()), reason);
            String body = text.isBlank() ? notice : text + "\n\n" + notice;
            String finish = finishReason() == FinishReason.LENGTH ? "length" : "malformed_tool_call";
            return new AgentTurn.Result(body, List.of(), model, finish, total);
        }
    }

    private record ReadCall(String id, String name, String rawArguments, ParsedArgs args) {
    }

    /** Parsed tool arguments; {@code valid} is false when the provider's JSON could not be read. */
    record ParsedArgs(Map<String, Object> value, boolean valid) {
    }

    private static Reply read(@Nullable ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("the model returned no output");
        }
        AssistantMessage output = response.getResult().getOutput();
        String rawFinish = response.getResult().getMetadata() == null
            ? null : response.getResult().getMetadata().getFinishReason();
        List<ReadCall> calls = new ArrayList<>();
        for (AssistantMessage.ToolCall call : output.getToolCalls()) {
            // Some providers (Gemini) send no call id; the client must be able to answer the call.
            calls.add(new ReadCall(ToolCallIds.ensure(call.id()), call.name(), call.arguments(),
                parseArgs(call.arguments())));
        }
        String text = output.getText() == null ? "" : output.getText();
        if (text.isEmpty() && calls.isEmpty()) {
            throw new IllegalStateException("the model returned neither text nor a tool call"
                + (FinishReason.of(rawFinish) == FinishReason.UNKNOWN ? "" : " (finish reason " + rawFinish + ")"));
        }
        AgentTurn.Usage usage = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var u = response.getMetadata().getUsage();
            usage = new AgentTurn.Usage(
                u.getPromptTokens() == null ? null : u.getPromptTokens().longValue(),
                u.getCompletionTokens() == null ? null : u.getCompletionTokens().longValue());
        }
        return new Reply(text, calls, rawFinish, usage);
    }

    /**
     * The finish reason put on the wire: {@code tool_calls} when usable calls are returned, otherwise
     * the provider's reason in one vocabulary ({@code stop}, {@code length}, {@code content_filter},
     * {@code malformed_tool_call}); a reason this service does not know is passed through unchanged.
     */
    static String wireFinish(FinishReason reason, @Nullable String raw, boolean hasCalls) {
        if (hasCalls) {
            return "tool_calls";
        }
        return switch (reason) {
            case LENGTH -> "length";
            case SAFETY -> "content_filter";
            case MALFORMED_TOOL_CALL -> "malformed_tool_call";
            case OTHER -> raw;
            case STOP, TOOL_CALLS, UNKNOWN -> "stop";
        };
    }

    /**
     * Providers return arguments as a JSON string. Blank arguments are a call with none. Anything that
     * is not a JSON object is NOT turned into an empty map: the call is marked unusable, so the
     * caller can tell the model instead of running a tool with arguments it never chose.
     */
    static ParsedArgs parseArgs(@Nullable String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new ParsedArgs(Map.of(), true);
        }
        try {
            Map<String, Object> parsed = JSON.readValue(arguments, MAP_TYPE);
            return new ParsedArgs(parsed == null ? Map.of() : parsed, true);
        } catch (JsonProcessingException e) {
            log.debug("Agent turn: tool-call arguments were not a valid JSON object");
            return new ParsedArgs(Map.of(), false);
        }
    }

    private static String toJson(@Nullable Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Never executed: {@code internalToolExecutionEnabled(false)} keeps Spring AI from calling it. */
    private record DefinitionOnlyToolCallback(Tool tool) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description() == null ? "" : tool.description())
                .inputSchema(toJson(tool.parameters() == null || tool.parameters().isEmpty() ? Map.of("type", "object") : tool.parameters()))
                .build();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException(
                "tool \"" + tool.name() + "\" is executed by the client, never by the server");
        }
    }

    @Nullable
    private FimCompletionEntitlements entitlements() {
        FimCompletionEntitlements resolved = entitlements;
        if (resolved != null) {
            return resolved;
        }
        resolved = entitlementsProvider.getIfAvailable();
        if (resolved != null) {
            entitlements = resolved;
        }
        return resolved;
    }
}
