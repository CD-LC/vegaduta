package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Parameter object for one chat turn, replacing the eight positional {@code
 * AgentChatService#chat(...)} overloads (6 to 13 arguments). Build one with {@link #builder(UUID,
 * String, String, String)} and pass it to {@link AgentChatService#chat (ChatTurnRequest)}; the
 * legacy overloads are thin deprecated delegates that fill the same fields.
 *
 * <p>The record does not normalise anything: a field is exactly what the caller (or the legacy
 * overload) supplied, so a delegating overload is byte-identical to the positional call it
 * replaces. Defaults applied by the {@link Builder} mirror the most common legacy shape (no
 * attachments, no extra tools, no RL turn ref, no forced depth, step events disabled).
 *
 * @param agentId the agent the caller asked for (experiment routing may substitute a clone)
 * @param sessionId conversation id scoping short-term history
 * @param subjectKey stable cross-session identity for long-term memory scoping; callers without a
 *     stronger identity pass the session id
 * @param userMessage the user's text
 * @param attachmentKey opaque key from {@code POST /{agentId}/chat/attachments}; re-derived and
 *     verified server-side (see {@code requireOwnAttachmentKey}), null for a plain text turn
 * @param attachmentMimeType required alongside {@code attachmentKey}
 * @param inlineAttachments optional base64 attachments from {@code ChatRequest.attachments}
 * @param extraTools one-off extra tools offered for this turn only (e.g. Quick Task delegation)
 * @param turnRef optional client per-turn id; when present the turn consults the RL bandit and
 *     records an episode under it
 * @param visionSessionId optional active vision session the turn's camera/screen frames are
 *     attributed to
 * @param forceFastDepth dispatch this one turn as {@link ReasoningDepth#FAST}
 * @param stepEvents this turn's step-event emitter (never null; {@link
 *     AgentStepEventEmitter#disabled()} for none). Events travel out-of-band, never in the reply
 *     Flux - see {@code AgentController#chat}
 * @param suppressAgentTools withhold the agent's resolved tool set (text-only turn); set by {@link
 *     AgentChatService#chatTextOnly} and by {@code AgentJobTurns}' structured-output repair turn,
 *     which builds the same request itself to pass its own {@link TurnContext}
 */
public record ChatTurnRequest(
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
        AgentStepEventEmitter stepEvents,
        boolean suppressAgentTools) {

    /** Starts a request with the four fields every turn has; the rest default as documented. */
    public static Builder builder(
            UUID agentId, String sessionId, String subjectKey, String userMessage) {
        return new Builder(agentId, sessionId, subjectKey, userMessage);
    }

    /** Mutable assembler for {@link ChatTurnRequest}; not thread-safe, single use. */
    public static final class Builder {
        private final UUID agentId;
        private final String sessionId;
        private final String subjectKey;
        private final String userMessage;
        private String attachmentKey;
        private String attachmentMimeType;
        private List<ChatInlineAttachment> inlineAttachments;
        private List<ToolCallback> extraTools = List.of();
        private String turnRef;
        private String visionSessionId;
        private boolean forceFastDepth;
        private AgentStepEventEmitter stepEvents = AgentStepEventEmitter.disabled();
        private boolean suppressAgentTools;

        private Builder(UUID agentId, String sessionId, String subjectKey, String userMessage) {
            this.agentId = agentId;
            this.sessionId = sessionId;
            this.subjectKey = subjectKey;
            this.userMessage = userMessage;
        }

        public Builder attachment(String key, String mimeType) {
            this.attachmentKey = key;
            this.attachmentMimeType = mimeType;
            return this;
        }

        public Builder inlineAttachments(List<ChatInlineAttachment> value) {
            this.inlineAttachments = value;
            return this;
        }

        public Builder extraTools(List<ToolCallback> value) {
            this.extraTools = value;
            return this;
        }

        public Builder turnRef(String value) {
            this.turnRef = value;
            return this;
        }

        public Builder visionSessionId(String value) {
            this.visionSessionId = value;
            return this;
        }

        public Builder forceFastDepth(boolean value) {
            this.forceFastDepth = value;
            return this;
        }

        public Builder stepEvents(AgentStepEventEmitter value) {
            this.stepEvents = value;
            return this;
        }

        public Builder suppressAgentTools(boolean value) {
            this.suppressAgentTools = value;
            return this;
        }

        public ChatTurnRequest build() {
            return new ChatTurnRequest(
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
                    suppressAgentTools);
        }
    }
}
