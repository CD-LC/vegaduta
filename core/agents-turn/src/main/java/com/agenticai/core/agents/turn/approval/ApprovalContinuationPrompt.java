package com.agenticai.core.agents.turn.approval;

import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.ApprovalContinuation;
import com.agenticai.core.agents.turn.ApprovalContinuation.Outcome;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Writes the message the agent is sent after a human decided one of its gated tool calls: the ONE
 * builder both continuation paths use (the turn the platform starts for a chat session, and the
 * turn an agent job resumes with).
 *
 * <p>The conversation history is text-only, so the approved tool's result cannot be replayed as a
 * provider tool_use/tool_result pair. It is delivered as a faithfully labelled equivalent instead:
 * a message that opens with {@code <approval_result request=".." tool=".." outcome="..">}, restates
 * the user's original request, shows the call and wraps the output in {@code <tool_result>}, with
 * the output cut at {@code app.approvals.continuation.max-result-chars} (not the 500 characters the
 * history note keeps). The wording is configuration; the opening tag is code, because the stored
 * conversation is recognised by it ({@link ApprovalContinuation#MESSAGE_MARKER}).
 *
 * <p>Tool output is untrusted: it is wrapped in a labelled block the message tells the model to
 * treat as data, and any text in it that would close the block or the message is neutralised so
 * output cannot pose as the platform's own words.
 */
@Component
public class ApprovalContinuationPrompt {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final String CLOSE_TAG = "</approval_result>";

    private final ApprovalContinuationProperties properties;

    public ApprovalContinuationPrompt(ApprovalContinuationProperties properties) {
        this.properties = properties;
    }

    /** The full user-role message for {@code continuation}. */
    public String render(ApprovalContinuation continuation) {
        Outcome outcome = continuation.outcome();
        String template =
                switch (outcome) {
                    case COMPLETED -> properties.getCompletedMessage();
                    case FAILED -> properties.getFailedMessage();
                    case DENIED -> properties.getDeniedMessage();
                };
        Map<String, String> values =
                Map.of(
                        "tool", neutralise(nullToEmpty(continuation.toolName())),
                        "requestId", String.valueOf(continuation.requestId()),
                        "approver", approverLabel(continuation.decidedBy()),
                        "goal", neutralise(nullToEmpty(continuation.goal())),
                        "arguments",
                                neutralise(
                                        shorten(
                                                nullToEmpty(continuation.argumentsJson()),
                                                properties.getMaxArgumentsChars())),
                        "result", neutralise(shorten(continuation.result(), properties.getMaxResultChars())),
                        "otherPending", otherPendingBlock(continuation.otherPending()));
        StringBuilder message = new StringBuilder();
        message.append(ApprovalContinuation.MESSAGE_MARKER)
                .append(" request=\"")
                .append(continuation.requestId())
                .append("\" tool=\"")
                .append(attributeSafe(continuation.toolName()))
                .append("\" outcome=\"")
                .append(outcome.name().toLowerCase(Locale.ROOT))
                .append("\">\n")
                .append(fill(template, values))
                .append('\n')
                .append(CLOSE_TAG);
        return message.toString();
    }

    private String otherPendingBlock(List<String> otherPending) {
        if (otherPending == null || otherPending.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("\n").append(properties.getOtherPendingHeader());
        for (String line : otherPending) {
            block.append("\n- ").append(neutralise(line));
        }
        return block.append('\n').toString();
    }

    /** Fills {@code {name}} placeholders in ONE pass, so a value is never itself re-expanded. */
    private static String fill(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Cuts {@code text} at {@code max} characters and says how much was left out. */
    String shorten(String text, int max) {
        String value = nullToEmpty(text);
        if (value.length() <= max) {
            return value;
        }
        String suffix =
                nullToEmpty(properties.getResultShortenedSuffix())
                        .replace("{omitted}", String.valueOf(value.length() - max));
        return value.substring(0, Math.max(0, max)) + suffix;
    }

    /**
     * Text that came from outside the platform (tool output, the user's message, a reviewer's
     * reason) must not be able to end the block it sits in or open a new platform message.
     */
    static String neutralise(String text) {
        return text.replace("</tool_result", "<\\/tool_result")
                .replace("</approval_result", "<\\/approval_result")
                .replace(ApprovalContinuation.MESSAGE_MARKER, "<\\approval_result");
    }

    private String approverLabel(String decidedBy) {
        return decidedBy == null || decidedBy.isBlank()
                ? properties.getApproverUnknown()
                : neutralise(decidedBy.strip());
    }

    private static String attributeSafe(String value) {
        return nullToEmpty(value).replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
