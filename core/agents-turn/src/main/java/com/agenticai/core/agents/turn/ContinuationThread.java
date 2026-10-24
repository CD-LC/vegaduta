package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.ToolLoopTranscript;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The conversation a turn's continuation passes are sent: what the model has actually been shown and
 * said so far, so that "carry on" resumes the task instead of restarting it from a summary.
 *
 * <p>A continuation used to be built as the history, the user's message as plain text (its attachments
 * dropped), the model's last text, and a nudge carrying up to 4000 characters of tool output as a text
 * block. The tool-use and tool-result messages - with their ids, and with provider metadata such as
 * Gemini thought signatures that a replay needs - were gone, so the model could not tell which of its
 * calls had been answered and ran them again. Now the baseline is the real transcript: after a pass
 * that ran tool rounds it is the conversation Spring AI's loop recorded ({@link ToolLoopTranscript}),
 * system message included; after a pass that ran none it is what that pass was sent. Either way the
 * original request keeps its attachments.
 *
 * <p>One instance per turn and per reasoning path; not thread-safe, used by one pass at a time.
 */
final class ContinuationThread {

    /**
     * What one pass is sent: the system prompt (null when {@code messages} already begins with it),
     * the messages so far, and the nudge as the next user message.
     */
    record Request(String systemPrompt, List<Message> messages, String user) {}

    private final String systemPrompt;
    private List<Message> baseline;
    private boolean systemIncluded;
    private List<Message> pending;

    ContinuationThread(
            String systemPrompt,
            List<Message> history,
            String userMessage,
            List<ChatAttachmentPayload> attachments) {
        this.systemPrompt = systemPrompt;
        this.baseline = new ArrayList<>(history == null ? List.of() : history);
        this.baseline.add(ChatAttachments.userMessage(userMessage, attachments));
    }

    /**
     * The request for the next pass: the conversation so far, {@code replySoFar} as the assistant's
     * latest message (omitted when empty - providers reject an empty assistant turn), then {@code nudge}
     * as the user's.
     */
    Request next(String replySoFar, String nudge) {
        List<Message> messages = new ArrayList<>(baseline);
        if (replySoFar != null && !replySoFar.isBlank()) {
            messages.add(new AssistantMessage(replySoFar));
        }
        List<Message> sent = new ArrayList<>(messages);
        sent.add(new UserMessage(nudge));
        pending = sent;
        return new Request(systemIncluded ? null : systemPrompt, messages, nudge);
    }

    /**
     * Records that the pass just run has finished, with the transcript its tool loop produced (an empty
     * one when it ran no tool round). Call it for the first pass of the turn too, before the first
     * {@link #next}, so the tool work that pass did is part of what the continuation is sent.
     */
    void passFinished(ToolLoopTranscript transcript) {
        if (transcript != null && transcript.hasRounds()) {
            baseline = new ArrayList<>(transcript.latest());
            systemIncluded = true;
        } else if (pending != null) {
            baseline = pending;
        }
        pending = null;
    }
}
