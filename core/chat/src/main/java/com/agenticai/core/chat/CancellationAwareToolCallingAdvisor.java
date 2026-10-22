package com.agenticai.core.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.model.tool.ToolExecutionResult;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * {@link ToolCallingAdvisor} that stops a streamed tool loop whose subscriber has gone away
 * (ADR-0012 P3).
 *
 * <p>Spring AI 1.1 ran the loop inside the model, and a cancelled stream never issued another
 * provider request. Spring AI 2's advisor runs the (blocking) tool on {@code boundedElastic}; when
 * the client disconnects mid-tool, Reactor cancels that task by INTERRUPTING the worker thread, but
 * the tool returns anyway and the advisor then subscribes the next model round - a wasted (and
 * billed) provider request whose result nobody reads. The next-round hook runs on that same worker
 * thread right after the tool returns, so an interrupted thread there means "cancelled while the
 * tool ran": the loop is stopped by throwing a {@link CancellationException} into the
 * already-cancelled subscription (dropped by Reactor, never reaching anyone). Blocking
 * (non-streaming) calls never hit this path.
 *
 * <p>Detection is best-effort by nature (a tool that swallows the interrupt hides it); the
 * characterisation test {@code
 * ToolLoopCharacterisationTest#cancellingTheStreamWhileAToolRunsStopsTheLoop} pins the common case.
 */
public class CancellationAwareToolCallingAdvisor extends ToolCallingAdvisor {

    protected CancellationAwareToolCallingAdvisor(
            org.springframework.ai.model.tool.ToolCallingManager toolCallingManager,
            ToolExecutionEligibilityChecker eligibilityChecker,
            int advisorOrder,
            boolean conversationHistoryEnabled) {
        super(toolCallingManager, eligibilityChecker, advisorOrder, conversationHistoryEnabled);
    }

    /**
     * A request marked {@link CallerRunsToolLoop} goes straight to the model: its tool calls are
     * returned to the caller, not executed.
     */
    @Override
    public ChatClientResponse adviseCall(
            ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        if (CallerRunsToolLoop.isSet(chatClientRequest.prompt().getOptions())) {
            return callAdvisorChain.nextCall(chatClientRequest);
        }
        return super.adviseCall(chatClientRequest, callAdvisorChain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        if (CallerRunsToolLoop.isSet(chatClientRequest.prompt().getOptions())) {
            return streamAdvisorChain.nextStream(chatClientRequest);
        }
        return super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(
            ChatClientRequest chatClientRequest,
            ChatClientResponse chatClientResponse,
            ToolExecutionResult toolExecutionResult) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "stream cancelled while a tool was running; not starting another model round");
        }
        return super.doGetNextInstructionsForToolCallStream(
                chatClientRequest, chatClientResponse, toolExecutionResult);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder that keeps the subclass through {@code copy()}, which {@code ChatClient} calls per
     * request.
     */
    public static final class Builder extends ToolCallingAdvisor.Builder<Builder> {

        private Builder() {
            super();
        }

        @Override
        protected Builder newCopy() {
            return new Builder();
        }

        @Override
        public ToolCallingAdvisor build() {
            return new CancellationAwareToolCallingAdvisor(
                    getToolCallingManager(),
                    getToolExecutionEligibilityChecker(),
                    getAdvisorOrder(),
                    isConversationHistoryEnabled());
        }
    }
}
