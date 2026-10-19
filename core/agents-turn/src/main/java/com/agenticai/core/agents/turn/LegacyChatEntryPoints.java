package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * The positional chat entry points that predate {@link ChatTurnRequest}, moved out of {@link
 * AgentChatService} (extract-only split) so the turn service itself stays one responsibility. Every
 * overload builds the same request and calls {@link #chat(ChatTurnRequest)}, which takes the turn
 * context from the calling thread and calls {@link #chat(TurnContext, ChatTurnRequest)}
 * (implemented by {@link AgentChatService}); they are inherited public methods of that service.
 * Production entries no longer use them (ADR-0022 stage 4).
 */
public abstract class LegacyChatEntryPoints {

    /**
     * The turn entry point (ADR-0022 stage 4); see {@link AgentChatService#chat(TurnContext,
     * ChatTurnRequest)}. The caller builds {@code context} with {@link TurnContext#forEntry} from
     * the tenant it already knows.
     */
    public abstract Flux<String> chat(TurnContext context, ChatTurnRequest request);

    /**
     * @deprecated build the {@link TurnContext} at the entry ({@link TurnContext#forEntry}) and
     *     call {@link #chat(TurnContext, ChatTurnRequest)}. This overload takes the context from
     *     the calling thread's {@code TenantContext} ({@code TurnContext.atEntry}); it remains for
     *     the positional overloads below, reflective callers and tests.
     */
    @Deprecated
    public Flux<String> chat(ChatTurnRequest request) {
        return chat(TurnContext.atEntry(request), request);
    }

    /**
     * @deprecated build a {@link ChatTurnRequest}. Text turn with an optional stored attachment.
     */
    @Deprecated
    public Flux<String> chat(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            String attachmentKey,
            String attachmentMimeType) {
        return chat(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                attachmentKey,
                attachmentMimeType,
                false);
    }

    /**
     * @deprecated build a {@link ChatTurnRequest} with {@code forceFastDepth}. Voice callers
     *     (push-to-talk and the LiveKit bridge) pass {@code true} so an agent configured for a
     *     slower depth cannot blow a voice turn's latency budget; the agent's stored depth is never
     *     modified.
     */
    @Deprecated
    public Flux<String> chat(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            String attachmentKey,
            String attachmentMimeType,
            boolean forceFastDepth) {
        return chat(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                attachmentKey,
                attachmentMimeType,
                null,
                List.of(),
                null,
                null,
                forceFastDepth);
    }

    /**
     * @deprecated build a {@link ChatTurnRequest} with {@code extraTools}. Quick Task offers other
     *     relevant agents as one-off delegation tools without touching the agent's saved
     *     collaborators.
     */
    @Deprecated
    public Flux<String> chat(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            String attachmentKey,
            String attachmentMimeType,
            List<ToolCallback> extraTools) {
        return chat(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                attachmentKey,
                attachmentMimeType,
                null,
                extraTools,
                null);
    }

    /**
     * @deprecated build a {@link ChatTurnRequest}. No inline attachments.
     */
    @Deprecated
    public Flux<String> chat(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            String attachmentKey,
            String attachmentMimeType,
            List<ToolCallback> extraTools,
            String turnRef) {
        return chat(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                attachmentKey,
                attachmentMimeType,
                null,
                extraTools,
                turnRef);
    }

    /**
     * @deprecated build a {@link ChatTurnRequest} with inline attachments and a turn ref.
     */
    @Deprecated
    public Flux<String> chat(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            String attachmentKey,
            String attachmentMimeType,
            List<ChatInlineAttachment> inlineAttachments,
            List<ToolCallback> extraTools,
            String turnRef) {
        return chat(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                attachmentKey,
                attachmentMimeType,
                inlineAttachments,
                extraTools,
                turnRef,
                null);
    }

    /**
     * @deprecated build a {@link ChatTurnRequest} with a vision session id.
     */
    @Deprecated
    public Flux<String> chat(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            String attachmentKey,
            String attachmentMimeType,
            List<ChatInlineAttachment> inlineAttachments,
            List<ToolCallback> extraTools,
            String turnRef,
            String visionSessionId) {
        return chat(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                attachmentKey,
                attachmentMimeType,
                inlineAttachments,
                extraTools,
                turnRef,
                visionSessionId,
                false);
    }

    /**
     * @deprecated build a {@link ChatTurnRequest} with {@code forceFastDepth}.
     */
    @Deprecated
    public Flux<String> chat(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            String attachmentKey,
            String attachmentMimeType,
            List<ChatInlineAttachment> inlineAttachments,
            List<ToolCallback> extraTools,
            String turnRef,
            String visionSessionId,
            boolean forceFastDepth) {
        return chat(
                agentId,
                sessionId,
                subjectKey,
                userMessage,
                attachmentKey,
                attachmentMimeType,
                inlineAttachments,
                extraTools,
                turnRef,
                visionSessionId,
                forceFastDepth,
                AgentStepEventEmitter.disabled());
    }

    /**
     * @deprecated build a {@link ChatTurnRequest} with {@code stepEvents}. The emitter is never
     *     null; a disabled one makes {@code AgentToolProvider} skip its wrapper entirely.
     */
    @Deprecated
    public Flux<String> chat(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            String attachmentKey,
            String attachmentMimeType,
            List<ChatInlineAttachment> inlineAttachments,
            List<ToolCallback> extraTools,
            String turnRef,
            String visionSessionId,
            boolean forceFastDepth,
            AgentStepEventEmitter stepEvents) {
        return chat(
                new ChatTurnRequest(
                        agentId,
                        sessionId,
                        subjectKey,
                        userMessage,
                        attachmentKey,
                        attachmentMimeType,
                        inlineAttachments,
                        extraTools,
                        turnRef,
                        visionSessionId,
                        forceFastDepth,
                        stepEvents,
                        false));
    }

    /**
     * A TOOL-LESS turn: the agent's resolved tool set is withheld for this one turn and no {@code
     * extraTools} are passed, so the model can only produce text. Exists for {@code
     * AgentJobExecution}'s structured-output REPAIR turns: a repair must reshape an existing answer
     * into JSON, and with tools attached it could re-execute side-effecting tools ({@code
     * send_channel_message}, {@code generate_document}, any {@code mcp_tool.*}) the goal turn had
     * already run. Budget metering, moderation, session persistence and the kill-switch are
     * unchanged - a tool-less turn is never a cheaper or less-guarded way to run a model.
     */
    public Flux<String> chatTextOnly(
            UUID agentId, String sessionId, String subjectKey, String userMessage) {
        return chat(
                ChatTurnRequest.builder(agentId, sessionId, subjectKey, userMessage)
                        .suppressAgentTools(true)
                        .build());
    }
}
