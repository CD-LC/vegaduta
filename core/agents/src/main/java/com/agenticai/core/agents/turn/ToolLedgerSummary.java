package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.RedactingToolExecutionExceptionProcessor;
import java.util.function.UnaryOperator;

/**
 * Builds one {@link ToolLedger.Entry} from what a tool call received and returned: masks it, compacts it
 * to a single line and cuts it to the {@link ToolLedgerProperties} limits.
 *
 * <p>Masking is the same pair the rest of the tool path uses. Credential-shaped text is replaced by
 * {@code RedactingToolExecutionExceptionProcessor#redact} (what the tool-call log applies to a failure
 * message and the model-facing failure text applies to an exception), and, when the tenant's gateway
 * policy asks for it, personal data is masked by the same redactor the model-facing tool result goes
 * through - the ledger is replayed to the model, so it must not carry what the model was not allowed to
 * see the first time. Masking runs on the text before it is cut, so a secret that straddles the cut is not
 * left half-visible.
 */
public final class ToolLedgerSummary {

    /** The credential scrub caps its input at 500 characters; so do the limits that feed it. */
    public static final int MAX_FIELD_CHARS = 500;

    /** Extra characters read past the limit so the masking sees a whole token at the cut. */
    private static final int MASKING_LOOKAHEAD = 64;

    private ToolLedgerSummary() {}

    /**
     * @param piiRedactor the tenant gateway policy's redactor for model-facing text, or {@code null}
     * @param approval the approval state of a call held for a human, or {@code null}
     */
    public static ToolLedger.Entry entry(
            String tool,
            String arguments,
            String result,
            ToolLedger.Status status,
            String approval,
            ToolLedgerProperties limits,
            UnaryOperator<String> piiRedactor) {
        return new ToolLedger.Entry(
                tool == null ? "?" : tool,
                compact(arguments, limits.getMaxArgumentChars(), limits.getCutMarker(), piiRedactor),
                compact(result, limits.getMaxResultChars(), limits.getCutMarker(), piiRedactor),
                status,
                approval == null || approval.isBlank() ? null : approval);
    }

    /** {@code text}, masked, on one line and at most {@code max} characters plus the cut marker. */
    static String compact(String text, int max, String cutMarker, UnaryOperator<String> piiRedactor) {
        if (text == null || text.isBlank() || max <= 0) {
            return "";
        }
        int bounded = Math.min(max, MAX_FIELD_CHARS);
        String head = text.length() > bounded + MASKING_LOOKAHEAD ? text.substring(0, bounded + MASKING_LOOKAHEAD) : text;
        if (piiRedactor != null) {
            try {
                String masked = piiRedactor.apply(head);
                head = masked != null ? masked : head;
            } catch (RuntimeException ignored) {
                // The redactor fails open on its own contract; the credential scrub below still runs.
            }
        }
        String scrubbed = RedactingToolExecutionExceptionProcessor.redact(head);
        String oneLine = scrubbed.replaceAll("\\s+", " ").strip();
        if (oneLine.length() <= bounded) {
            return oneLine;
        }
        return oneLine.substring(0, bounded) + (cutMarker == null ? "" : cutMarker);
    }
}
