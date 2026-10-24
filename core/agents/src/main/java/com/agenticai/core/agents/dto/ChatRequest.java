package com.agenticai.core.agents.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * sessionId is optional. Omit it to start a new conversation — the server
 * generates one and returns it in the X-Session-Id response header. Pass the
 * same sessionId back on the next call to continue that conversation with
 * memory intact.
 *
 * <p>Stage 4 (Chat file upload) - attachmentKey/attachmentMimeType are both
 * optional and travel together: attachmentKey is the opaque key returned by
 * {@code POST /api/agents/{agentId}/chat/attachments} ({@code
 * ChatAttachmentResponse.attachmentKey()}), attachmentMimeType is that same
 * response's {@code mimeType}, echoed back rather than re-derived so this
 * turn's model-capability check ({@code AgentChatService}) doesn't need to
 * re-open the stored file just to read its content type. Omit both for a
 * plain text turn - unchanged from before this stage existed.
 *
 * <p>{@code turnRef} is the optional client-generated per-turn id the web
 * Playground also passes to its inline feedback control; when present, the
 * backend records this turn's RL episode under that exact ref so a later
 * thumbs/rating on the same turn links back to it (see {@code core/rl}
 * FeedbackService's agent+session+turnRef match). Omitted by every non-
 * Playground caller (WhatsApp/Telegram/voice/etc.), which simply record no RL
 * episode for the turn.
 *
 * <p>{@code attachments} (multimodal playground contract) is the OPTIONAL
 * inline alternative to attachmentKey: up to 3 base64-encoded files sent
 * directly on this call, validated against the agent's model's modality
 * profile and size caps by {@code InlineAttachmentProcessor} (413/415/400 on
 * violation - see that class). Coexists with attachmentKey (both may appear
 * on one turn); omit for a plain text turn.
 *
 * <p>{@code visionSessionId} (live-vision contract) is the
 * OPTIONAL id returned by {@code POST /api/vision/sessions}: when this turn
 * carries camera/screen frames (see {@code ChatInlineAttachment#source}),
 * passing it attributes the frames to that session's running
 * {@code framesAnalyzed} counter (surfaced by the session's end summary).
 * Omitted by every non-vision caller - the per-user frame quota is enforced
 * with or without it.
 *
 * <p>{@code forceFastDepth} (2026-08-06, reliability blueprint follow-up) is
 * a turn-scoped override: when {@code true}, THIS turn dispatches as {@code
 * ReasoningDepth.FAST} regardless of the agent's own configured reasoning
 * depth (the agent's stored/configured depth is never changed - see {@code
 * AgentChatService#chat}'s matching javadoc). Defaults to {@code false} when
 * omitted from the request body, matching every existing caller's current
 * behavior unchanged. Intended for the LiveKit voice bridge
 * ({@code voice-agent/app/llm_core_bridge.py}), which POSTs to this endpoint
 * directly over HTTP (not a Java method call) and must never let an agent
 * configured for CAREFUL/RIGOROUS/PLANNED blow a voice turn's latency
 * budget; push-to-talk voice ({@code VoiceCallController#turn}) calls the
 * Java overload directly instead of going through this HTTP contract.
 *
 * <p>{@code stepEvents} (2026-08-17, opt-in step-event streaming) is a
 * turn-scoped request for an ACTIVITY TRACE: when {@code true}, this turn's
 * SSE response additionally carries typed, single-line framed chunks
 * ({@code tool_call}, {@code tool_result}, {@code error}) prefixed with
 * {@code AgentStepEventEmitter.STEP_EVENT_MARKER}, interleaved with the
 * normal token chunks, so a client can show what the agent is actually doing
 * while it does it. Defaults to {@code false} when omitted, and when false
 * the response is BYTE-IDENTICAL to before this field existed.
 *
 * <p><b>Why this is a request flag and not simply a change to the stream
 * format</b> - the question worth answering here, because "just always send
 * the events and let clients ignore what they don't recognize" is the obvious
 * design and it is not available. The chunks {@code AgentChatService#chat}
 * produces are consumed by far more than a browser, and almost none of those
 * consumers can opt out of anything new, because they never construct a
 * {@code ChatRequest} at all. The decisive one is the LiveKit voice bridge
 * ({@code voice-agent/app/llm_core_bridge.py}): its SSE parser recognizes
 * exactly one marker ({@code AgentController#STREAM_ERROR_MARKER}) and hands
 * every other payload straight to a LiveKit {@code ChatChunk}, which goes to
 * {@code tts_node} - so an unrecognized framed chunk is not a rendering
 * glitch there, it is the TTS engine reading JSON aloud to a human on a live
 * call. The omnichannel adapters (WhatsApp/Telegram/Slack/Discord/X/email/
 * Twilio) and {@code WorkflowNodeExecutor} are the same story more quietly:
 * they {@code collectList()} the whole stream into a customer-facing message
 * body or a workflow node's output value. Only an HTTP caller that knows this
 * contract can set this flag, which is precisely the set of callers that can
 * also parse the result. See {@code AgentStepEventEmitter}'s javadoc for the
 * full consumer inventory and the framing rules.
 *
 * <p>{@code toolNames} (the playground's "this run only" tool switches) narrows THIS turn to the listed
 * tools of the agent. It is narrowing-only: a name that is not one of the tools the agent resolves for the
 * caller is refused with a 400 that names it, a tool kept is still behind its approval, autonomy and
 * guardrail gates, and the agent itself (its saved tool access) is never changed. Omitted (or {@code null})
 * leaves the agent's tools as they are - byte-identical to before the field existed; an empty list runs the
 * turn without tools. The turn the platform starts by itself after a human decides a gated call of this turn
 * keeps the same selection.
 */
public record ChatRequest(
    String sessionId,
    /**
     * <p><b>{@code @Size} is a DoS control, not cosmetics (2026-07-29).</b>
     * This field is the very first attacker-controlled string of a turn, and
     * it reaches {@code JailbreakDetectorGuardrail} BEFORE the response cache
     * and before the hosted-pool budget check - so an expensive message costs
     * the sender nothing. With that guardrail's old unbounded {@code .*}
     * patterns, an unvalidated 1.6 KB message measured 41 seconds of pinned
     * CPU. The regexes are now linear, which is the real fix; this cap is the
     * outer layer that keeps any future pattern's cost bounded.
     *
     * <p>8,000 characters is roughly 2,000 tokens - far more than anyone
     * types into a chat box, and deliberately generous next to the sibling
     * surfaces ({@code DemoChatController.DemoChatRequest} and the two
     * {@code ShareChatRequest}s all cap at 2,000). Genuinely long content
     * belongs in {@code attachmentKey}/{@code attachments}, which is what
     * those fields exist for. Note this constrains only the HTTP surface:
     * internal callers of {@code AgentChatService#chat} (omnichannel
     * webhooks, workflow nodes) never build a {@code ChatRequest}, which is
     * exactly why the guardrail carries its own cap too.
     */
    @NotBlank(message = "message is required")
    @Size(max = 8000, message = "message is too long (8000 character maximum)")
    String message,
    String attachmentKey,
    String attachmentMimeType,
    String turnRef,
    List<ChatInlineAttachment> attachments,
    String visionSessionId,
    boolean forceFastDepth,
    boolean stepEvents,
    @Size(max = ChatRequest.MAX_TOOL_NAMES, message = "toolNames is too long (200 names maximum)")
    List<@NotBlank(message = "toolNames may not contain a blank name") @Size(max = ChatRequest.MAX_TOOL_NAME_LENGTH,
        message = "a tool name is too long (128 characters maximum)") String> toolNames
) {

    /** Most tool names one request may select; the agent's whole catalog is far smaller than this. */
    public static final int MAX_TOOL_NAMES = 200;

    /** Longest tool name accepted; registered tool names (MCP-namespaced ones included) are well under this. */
    public static final int MAX_TOOL_NAME_LENGTH = 128;

    /** A request that selects no tools (every caller that predates {@code toolNames}). */
    public ChatRequest(
        String sessionId,
        String message,
        String attachmentKey,
        String attachmentMimeType,
        String turnRef,
        List<ChatInlineAttachment> attachments,
        String visionSessionId,
        boolean forceFastDepth,
        boolean stepEvents
    ) {
        this(sessionId, message, attachmentKey, attachmentMimeType, turnRef, attachments, visionSessionId,
            forceFastDepth, stepEvents, null);
    }
}
