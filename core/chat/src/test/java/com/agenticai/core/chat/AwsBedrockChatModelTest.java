package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * Locks in the three gaps closed on {@link AwsBedrockChatModel}: {@code
 * stream()} no longer throws (ChatModel's default did, and the FAST depth
 * always streams), Bedrock's token usage reaches {@code
 * ChatResponseMetadata} on both paths (so {@code LlmMetricsAdvisor} can cost
 * the call), and TOOL history messages are still skipped rather than sent.
 */
class AwsBedrockChatModelTest {

    private static final String MODEL = "anthropic.claude-3-5-sonnet-20241022-v2:0";

    private final BedrockRuntimeClient syncClient = mock(BedrockRuntimeClient.class);
    private final BedrockRuntimeAsyncClient asyncClient = mock(BedrockRuntimeAsyncClient.class);

    private static TokenUsage usage(int in, int out) {
        return TokenUsage.builder().inputTokens(in).outputTokens(out).totalTokens(in + out).build();
    }

    private static ConverseResponse converseResponse(String text) {
        return ConverseResponse.builder()
            .output(ConverseOutput.fromMessage(Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText(text))
                .build()))
            .stopReason("end_turn")
            .usage(usage(12, 5))
            .build();
    }

    @Test
    void callMapsTextAndUsageIntoMetadata() {
        when(syncClient.converse(any(ConverseRequest.class))).thenReturn(converseResponse("hello"));
        AwsBedrockChatModel model = new AwsBedrockChatModel(syncClient, MODEL, 256, 0.2, 0.9);

        ChatResponse response = model.call(new Prompt("hi"));

        assertEquals("hello", response.getResult().getOutput().getText());
        assertEquals(12, response.getMetadata().getUsage().getPromptTokens());
        assertEquals(5, response.getMetadata().getUsage().getCompletionTokens());
        assertEquals(17, response.getMetadata().getUsage().getTotalTokens());
        assertEquals(MODEL, response.getMetadata().getModel());
        assertEquals("end_turn", response.getResult().getMetadata().getFinishReason());
    }

    @Test
    void callSkipsToolMessagesAndSendsSystemSeparately() {
        when(syncClient.converse(any(ConverseRequest.class))).thenReturn(converseResponse("ok"));
        AwsBedrockChatModel model = new AwsBedrockChatModel(syncClient, MODEL, 256, 0.2, 0.9);

        model.call(new Prompt(List.<org.springframework.ai.chat.messages.Message>of(
            new SystemMessage("be brief"),
            new UserMessage("what's the weather?"),
            new AssistantMessage("checking"),
            ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("1", "weather", "sunny")))
                .build(),
            new UserMessage("and?"))));

        ArgumentCaptor<ConverseRequest> sent = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(syncClient).converse(sent.capture());
        assertEquals(1, sent.getValue().system().size());
        assertEquals(List.of(ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.USER),
            sent.getValue().messages().stream().map(Message::role).collect(Collectors.toList()));
    }

    @Test
    void streamEmitsDeltasThenFinalUsageChunk() {
        when(asyncClient.converseStream(any(ConverseStreamRequest.class), any(ConverseStreamResponseHandler.class)))
            .thenAnswer(invocation -> {
                ConverseStreamResponseHandler handler = invocation.getArgument(1);
                List<ConverseStreamOutput> events = List.of(
                    ConverseStreamOutput.contentBlockDeltaBuilder().delta(ContentBlockDelta.fromText("Hel")).build(),
                    ConverseStreamOutput.contentBlockDeltaBuilder().delta(ContentBlockDelta.fromText("lo")).build(),
                    ConverseStreamOutput.messageStopBuilder().stopReason("end_turn").build(),
                    ConverseStreamOutput.metadataBuilder().usage(usage(30, 2)).build());
                handler.onEventStream(SdkPublisher.adapt(Flux.fromIterable(events)));
                handler.complete();
                return CompletableFuture.completedFuture(null);
            });
        AwsBedrockChatModel model = new AwsBedrockChatModel(syncClient, asyncClient, MODEL, 256, 0.2, 0.9);

        List<ChatResponse> chunks = model.stream(new Prompt("hi")).collectList().block();

        assertEquals(3, chunks.size());
        assertEquals("Hel", chunks.get(0).getResult().getOutput().getText());
        assertEquals("lo", chunks.get(1).getResult().getOutput().getText());
        ChatResponse last = chunks.get(2);
        assertEquals("", last.getResult().getOutput().getText());
        assertEquals(30, last.getMetadata().getUsage().getPromptTokens());
        assertEquals(2, last.getMetadata().getUsage().getCompletionTokens());
        assertEquals(32, last.getMetadata().getUsage().getTotalTokens());
        assertEquals("end_turn", last.getResult().getMetadata().getFinishReason());
    }

    @Test
    void streamPropagatesUnwrappedSdkError() {
        RuntimeException sdkError = new RuntimeException("ValidationException: bad model id");
        when(asyncClient.converseStream(any(ConverseStreamRequest.class), any(ConverseStreamResponseHandler.class)))
            .thenReturn(CompletableFuture.failedFuture(new CompletionException(sdkError)));
        AwsBedrockChatModel model = new AwsBedrockChatModel(syncClient, asyncClient, MODEL, 256, 0.2, 0.9);

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> model.stream(new Prompt("hi")).collectList().block());
        assertSame(sdkError, thrown);
    }

    @Test
    void streamWithoutAsyncClientFallsBackToSingleCallChunk() {
        when(syncClient.converse(any(ConverseRequest.class))).thenReturn(converseResponse("whole answer"));
        AwsBedrockChatModel model = new AwsBedrockChatModel(syncClient, MODEL, 256, 0.2, 0.9);

        List<ChatResponse> chunks = model.stream(new Prompt("hi")).collectList().block();

        assertEquals(1, chunks.size());
        assertEquals("whole answer", chunks.get(0).getResult().getOutput().getText());
        assertEquals(17, chunks.get(0).getMetadata().getUsage().getTotalTokens());
    }
}
