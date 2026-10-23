package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.runtime.RuntimeLimits;
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
 * @param continuation set only on the turn the platform starts after a human decided a gated tool
 *     call: {@code userMessage} is then the platform-written continuation message and this carries
 *     what the pipeline must not derive from it (the original goal, the tools to keep on offer);
 *     {@code null} on every ordinary turn
 * @param caps runtime limits this one run carries with it (an agent job's remaining tool-call budget);
 *     the request layer of the runtime settings, which can only tighten what the platform, tenant and
 *     agent layers allow, never loosen it; {@code null} on every ordinary turn
 * @param toolNames the run-scoped tool selection (the playground's "this run only" tool switches): the
 *     run may use only these of the agent's tools. It can only NARROW the set the agent resolves for the
 *     caller - a name that is not among those tools refuses the request, and a tool kept is still wrapped
 *     by its approval, autonomy and guardrail gates. {@code null} means no selection (every ordinary turn),
 *     an empty list a run without tools. The continuation after an approval carries the same selection
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
        boolean suppressAgentTools,
        ApprovalContinuation continuation,
        RuntimeLimits caps,
        List<String> toolNames) {

    /** A request with no run-scoped tool selection (every caller but the chat endpoint and its continuation). */
    public ChatTurnRequest(
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
            boolean suppressAgentTools,
            ApprovalContinuation continuation,
            RuntimeLimits caps) {
        this(
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
                suppressAgentTools,
                continuation,
                caps,
                null);
    }

    /** A request with no run-level caps (every caller but an agent job). */
    public ChatTurnRequest(
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
            boolean suppressAgentTools,
            ApprovalContinuation continuation) {
        this(
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
                suppressAgentTools,
                continuation,
                null);
    }

    /** The request every existing caller builds: an ordinary turn, no approval continuation. */
    public ChatTurnRequest(
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
        this(
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
                suppressAgentTools,
                null);
    }

    public ChatTurnRequest {
        toolNames = toolNames == null ? null : List.copyOf(toolNames);
    }

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
        private ApprovalContinuation continuation;
        private RuntimeLimits caps;
        private List<String> toolNames;

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

        /** Marks this as the continuation turn after a human decided a gated tool call. */
        public Builder continuation(ApprovalContinuation value) {
            this.continuation = value;
            return this;
        }

        /**
         * Caps this run carries (an agent job's remaining tool-call budget): applied as the request
         * layer of the runtime settings, so they can lower a limit but never raise it.
         */
        public Builder caps(RuntimeLimits value) {
            this.caps = value;
            return this;
        }

        /**
         * Narrows this run to {@code value} of the agent's tools (see {@link ChatTurnRequest#toolNames()});
         * {@code null} leaves the agent's tools as they are.
         */
        public Builder toolNames(List<String> value) {
            this.toolNames = value;
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
                    suppressAgentTools,
                    continuation,
                    caps,
                    toolNames);
        }
    }
}
