package com.agenticai.core.chat;

import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * Minimal, hand-rolled {@link ChatModel} over AWS Bedrock's Converse API (via {@code
 * software.amazon.awssdk:bedrockruntime}'s {@link BedrockRuntimeClient#converse} / {@link
 * BedrockRuntimeAsyncClient#converseStream}), NOT a Spring AI Bedrock starter - this platform's
 * Spring AI BOM pin (1.1.7, see {@code core/pom.xml}) was not confidently verified to resolve a
 * {@code spring-ai-starter-model-bedrock-*} artifact with a known-correct autoconfiguration class
 * name to add to {@code CoreApplication}'s exclude list (see that class's javadoc for why an
 * unexcluded Spring AI autoconfiguration crashes this platform's BYOK boot), so per this build's
 * own guidance this hand-rolls the Converse call directly against the AWS SDK instead of guessing
 * at a starter dependency coordinate.
 *
 * <p><b>Honestly scoped - what this does and does NOT cover:</b>
 *
 * <ul>
 *   <li>Covers: single- and multi-turn text chat (system + user + assistant history), {@code
 *       maxTokens}/{@code temperature}/{@code topP} inference config, SigV4 auth via the
 *       credential's own access key/secret/region, token usage (Bedrock's {@link TokenUsage} mapped
 *       into {@link DefaultUsage} on the {@link ChatResponseMetadata}, so {@code
 *       LlmMetricsAdvisor}'s cost tracking sees real numbers), and streaming.
 *   <li>Streaming ({@link #stream}): when an async client is supplied, a real {@code
 *       ConverseStream} - one {@link ChatResponse} per text delta, then a final empty-text chunk
 *       carrying the usage and finish reason (the advisor reads usage off the LAST chunk). Without
 *       an async client it degrades to one {@link #call} emitted as a single chunk on {@code
 *       boundedElastic} - correct output, no incremental tokens. This override matters: {@link
 *       ChatModel}'s inherited default throws {@code UnsupportedOperationException}, and the FAST
 *       reasoning depth (every agent's default) always streams.
 *   <li>Does NOT cover: tool/function calling (Bedrock's {@code toolConfig} is not wired - a {@code
 *       TOOL} message in the prompt history is skipped rather than sent, and tool definitions on
 *       the prompt's options are ignored; both are logged at WARN once per call so it isn't
 *       silent), and image/document content blocks (only {@link ContentBlock#fromText}).
 * </ul>
 */
public class AwsBedrockChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(AwsBedrockChatModel.class);

    private final BedrockRuntimeClient client;
    private final BedrockRuntimeAsyncClient asyncClient;
    private final String modelId;
    private final int maxTokens;
    private final double temperature;
    private final double topP;

    /** Sync-only: {@link #stream} degrades to a single blocking {@link #call} chunk. */
    public AwsBedrockChatModel(
            BedrockRuntimeClient client,
            String modelId,
            int maxTokens,
            double temperature,
            double topP) {
        this(client, null, modelId, maxTokens, temperature, topP);
    }

    public AwsBedrockChatModel(
            BedrockRuntimeClient client,
            BedrockRuntimeAsyncClient asyncClient,
            String modelId,
            int maxTokens,
            double temperature,
            double topP) {
        this.client = client;
        this.asyncClient = asyncClient;
        this.modelId = modelId;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.topP = topP;
    }

    /**
     * Converse-shaped pieces of a Spring AI prompt, shared by {@link #call} and {@link #stream}.
     */
    private record ConverseInput(
            List<Message> messages,
            List<SystemContentBlock> system,
            InferenceConfiguration inference) {}

    private ConverseInput toConverseInput(Prompt prompt) {
        List<Message> messages = new ArrayList<>();
        List<SystemContentBlock> systemBlocks = new ArrayList<>();
        int skippedToolMessages = 0;

        for (org.springframework.ai.chat.messages.Message message : prompt.getInstructions()) {
            if (message.getMessageType() == MessageType.TOOL) {
                // Not supported - see this class's javadoc. Skipped rather than
                // failing the whole turn outright, so an agent whose history
                // contains a tool result but is (temporarily or permanently)
                // routed to Bedrock degrades to "missing that turn's tool
                // result" instead of a hard error on every subsequent turn.
                skippedToolMessages++;
                continue;
            }
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            switch (message.getMessageType()) {
                case SYSTEM -> systemBlocks.add(SystemContentBlock.fromText(text));
                case USER ->
                        messages.add(
                                Message.builder()
                                        .role(ConversationRole.USER)
                                        .content(ContentBlock.fromText(text))
                                        .build());
                case ASSISTANT ->
                        messages.add(
                                Message.builder()
                                        .role(ConversationRole.ASSISTANT)
                                        .content(ContentBlock.fromText(text))
                                        .build());
                default -> {}
            }
        }

        warnIfToolsIgnored(prompt, skippedToolMessages);

        InferenceConfiguration inference =
                InferenceConfiguration.builder()
                        .maxTokens(maxTokens)
                        .temperature((float) temperature)
                        .topP((float) topP)
                        .build();
        return new ConverseInput(messages, systemBlocks, inference);
    }

    /**
     * One WARN per call when the caller asked for tools this model cannot honour - the turn still
     * runs as plain text chat (the previous, silent behaviour), but an operator can now see why a
     * Bedrock agent never calls its tools.
     */
    private void warnIfToolsIgnored(Prompt prompt, int skippedToolMessages) {
        int toolDefinitions = 0;
        if (prompt.getOptions() instanceof ToolCallingChatOptions toolOptions) {
            if (toolOptions.getToolCallbacks() != null) {
                toolDefinitions += toolOptions.getToolCallbacks().size();
            }
        }
        if (toolDefinitions > 0 || skippedToolMessages > 0) {
            log.warn(
                    "AWS Bedrock model {} does not support tool calling here: ignoring {} tool definition(s) "
                            + "and skipping {} TOOL message(s); the turn runs as plain text chat",
                    modelId,
                    toolDefinitions,
                    skippedToolMessages);
        }
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ConverseInput input = toConverseInput(prompt);

        ConverseRequest.Builder requestBuilder =
                ConverseRequest.builder()
                        .modelId(modelId)
                        .messages(input.messages())
                        .inferenceConfig(input.inference());
        if (!input.system().isEmpty()) {
            requestBuilder.system(input.system());
        }

        // AWS SDK exceptions (BedrockRuntimeException and its subtypes -
        // AccessDeniedException, ValidationException, ThrottlingException,
        // etc.) are left to propagate as-is rather than caught/rewrapped
        // here: they're plain RuntimeExceptions with a real message, which
        // ProviderErrorTranslator's generic describeCauseChain fallback
        // already knows how to surface - the same posture AnthropicApi's
        // own exceptions get (see that translator's class javadoc).
        ConverseResponse response = client.converse(requestBuilder.build());

        String text =
                (response.output() != null && response.output().message() != null)
                        ? response.output().message().content().stream()
                                .map(ContentBlock::text)
                                .filter(Objects::nonNull)
                                .collect(Collectors.joining())
                        : "";

        Generation generation =
                new Generation(
                        new AssistantMessage(text), finishMetadata(response.stopReasonAsString()));
        return new ChatResponse(List.of(generation), responseMetadata(response.usage()));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (asyncClient == null) {
            // Degraded path - see class javadoc. call() blocks, hence boundedElastic.
            return Flux.defer(() -> Flux.just(call(prompt)))
                    .subscribeOn(TenantAwareSchedulers.boundedElastic());
        }
        return Flux.defer(
                () -> {
                    ConverseInput input = toConverseInput(prompt);
                    ConverseStreamRequest.Builder requestBuilder =
                            ConverseStreamRequest.builder()
                                    .modelId(modelId)
                                    .messages(input.messages())
                                    .inferenceConfig(input.inference());
                    if (!input.system().isEmpty()) {
                        requestBuilder.system(input.system());
                    }
                    ConverseStreamRequest request = requestBuilder.build();

                    return Flux.<ChatResponse>create(
                            sink -> {
                                AtomicReference<TokenUsage> usage = new AtomicReference<>();
                                AtomicReference<String> stopReason = new AtomicReference<>();

                                ConverseStreamResponseHandler handler =
                                        ConverseStreamResponseHandler.builder()
                                                .subscriber(
                                                        ConverseStreamResponseHandler.Visitor
                                                                .builder()
                                                                .onContentBlockDelta(
                                                                        event -> {
                                                                            String delta =
                                                                                    event.delta()
                                                                                                    == null
                                                                                            ? null
                                                                                            : event.delta()
                                                                                                    .text();
                                                                            if (delta != null
                                                                                    && !delta
                                                                                            .isEmpty()) {
                                                                                sink.next(
                                                                                        new ChatResponse(
                                                                                                List
                                                                                                        .of(
                                                                                                                new Generation(
                                                                                                                        new AssistantMessage(
                                                                                                                                delta))),
                                                                                                ChatResponseMetadata
                                                                                                        .builder()
                                                                                                        .model(
                                                                                                                modelId)
                                                                                                        .build()));
                                                                            }
                                                                        })
                                                                .onMessageStop(
                                                                        event ->
                                                                                stopReason.set(
                                                                                        event
                                                                                                .stopReasonAsString()))
                                                                .onMetadata(
                                                                        event ->
                                                                                usage.set(
                                                                                        event
                                                                                                .usage()))
                                                                .build())
                                                .onComplete(
                                                        () -> {
                                                            // Final chunk: empty text, carries
                                                            // usage + finish reason.
                                                            // LlmMetricsAdvisor reads usage off the
                                                            // last chunk seen.
                                                            sink.next(
                                                                    new ChatResponse(
                                                                            List.of(
                                                                                    new Generation(
                                                                                            new AssistantMessage(
                                                                                                    ""),
                                                                                            finishMetadata(
                                                                                                    stopReason
                                                                                                            .get()))),
                                                                            responseMetadata(
                                                                                    usage.get())));
                                                            sink.complete();
                                                        })
                                                .build();

                                // Errors are taken from the returned future only (the handler's
                                // onError is left at its no-op default) so a failure is signalled
                                // exactly once; unwrapped so ProviderErrorTranslator sees the
                                // BedrockRuntimeException itself, same as the sync path.
                                CompletableFuture<Void> future =
                                        asyncClient.converseStream(request, handler);
                                future.whenComplete(
                                        (ignored, error) -> {
                                            if (error != null) {
                                                sink.error(unwrap(error));
                                            }
                                        });
                                sink.onCancel(() -> future.cancel(true));
                            });
                });
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ChatGenerationMetadata finishMetadata(String stopReason) {
        return stopReason == null
                ? ChatGenerationMetadata.NULL
                : ChatGenerationMetadata.builder().finishReason(stopReason).build();
    }

    private ChatResponseMetadata responseMetadata(TokenUsage usage) {
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder().model(modelId);
        if (usage != null) {
            builder.usage(
                    new DefaultUsage(
                            usage.inputTokens(), usage.outputTokens(), usage.totalTokens()));
        }
        return builder.build();
    }
}
