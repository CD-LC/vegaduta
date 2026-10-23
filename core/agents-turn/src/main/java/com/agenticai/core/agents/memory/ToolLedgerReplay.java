package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * How a stored turn's {@link ToolLedger} is shown to the model: folded into the text of the assistant
 * turn it belongs to, ahead of the reply, so the history keeps its plain USER/ASSISTANT alternation (an
 * extra message could put two assistant messages in a row, or a tool message ahead of a call, which
 * providers reject) and no provider-specific tool-call shape has to be rebuilt from a summary.
 *
 * <p>Why text and not a replayed tool-use / tool-result pair: those messages carry provider ids and, on
 * Gemini, thought signatures that a summary cannot recreate; a synthetic pair is exactly the history the
 * thought-signature workaround exists for. A bracketed record in the assistant text works on every
 * provider, and the system note ({@link ToolLedgerProperties#getTrustNote()}) tells the model it is the
 * platform's record and may be relied on.
 *
 * <p>The block comes first so the 2,000-character per-turn excerpts the context-compaction summariser and
 * the rolling session summary read keep it even when the reply is long.
 */
public final class ToolLedgerReplay {

    private ToolLedgerReplay() {}

    /** The rendered block of {@code ledger}, or {@code ""} when it records nothing. */
    public static String render(ToolLedger ledger, ToolLedgerProperties p) {
        if (ledger == null || ledger.none()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        int budget = p.getMaxReplayCharsPerTurn() - p.getHeader().length();
        int notListed = ledger.omitted();
        int used = 0;
        List<ToolLedger.Entry> entries = ledger.entries();
        for (int i = 0; i < entries.size(); i++) {
            String line = renderLine(entries.get(i), p);
            // Always list the first call; after that, stop at the block's character budget.
            if (!lines.isEmpty() && used + line.length() + 1 > budget) {
                notListed += entries.size() - i;
                break;
            }
            lines.add(line);
            used += line.length() + 1;
        }
        StringBuilder out = new StringBuilder(p.getHeader());
        for (String line : lines) {
            out.append('\n').append(line);
        }
        if (notListed > 0) {
            out.append('\n').append(p.getOmittedLine().replace("{count}", Integer.toString(notListed)));
        }
        return out.toString();
    }

    private static String renderLine(ToolLedger.Entry e, ToolLedgerProperties p) {
        String detail = e.result() == null || e.result().isBlank() ? "" : p.getDetail().replace("{result}", e.result());
        String approval =
                e.approval() == null || e.approval().isBlank()
                        ? ""
                        : p.getApprovalDetail().replace("{approval}", e.approval());
        return p.getLine()
                .replace("{tool}", e.tool() == null ? "?" : e.tool())
                .replace("{arguments}", e.arguments() == null ? "" : e.arguments())
                .replace("{status}", p.statusLabel(e.status() == null ? ToolLedger.Status.OK : e.status()))
                .replace("{detail}", detail + approval);
    }

    /**
     * {@code turn} as the model reads it. An assistant turn with a ledger gets the rendered block ahead of
     * its text when {@code showLedger}; a failed reply (kept in the transcript for the person, never shown
     * to the model as something it said) is dropped unless it carries a ledger, in which case the model is
     * shown the ledger and the failure note - the tools ran, and it must not run them again blindly.
     * Everything else is returned without a ledger; a returned turn still carries {@code tools} only when the record was folded in, so a caller can tell.
     */
    public static Optional<ChatTurn> modelView(ChatTurn turn, ToolLedgerProperties p, boolean showLedger) {
        if (turn == null) {
            return Optional.empty();
        }
        boolean failed = ChatTurn.isFailedReply(turn);
        boolean ledgerShown =
                showLedger
                        && turn.tools() != null
                        && p.isEnabled()
                        && p.isReplayEnabled()
                        && turn.role() == MessageRole.ASSISTANT;
        if (failed && !ledgerShown) {
            return Optional.empty();
        }
        if (!ledgerShown) {
            // No record is folded in, so none is left on the turn for a caller to mistake for one.
            return Optional.of(turn.tools() == null ? turn : turn.withTools(null));
        }
        String block = render(turn.tools(), p);
        String body = failed ? p.getFailedReplyNote() : turn.content() == null ? "" : turn.content();
        String text = body.isBlank() ? block : block + "\n\n" + body;
        return Optional.of(new ChatTurn(turn.role(), text, turn.timestamp(), turn.tools()));
    }

    /**
     * The replay of a stored window, oldest first: every failed reply without a ledger removed, and the
     * ledger shown on the newest {@link ToolLedgerProperties#getReplayTurns()} assistant turns that carry
     * one (older ones are left to the rolling summary and to context compaction).
     */
    public static List<ChatTurn> modelView(List<ChatTurn> stored, ToolLedgerProperties p) {
        int shown = 0;
        boolean[] show = new boolean[stored.size()];
        for (int i = stored.size() - 1; i >= 0; i--) {
            ChatTurn t = stored.get(i);
            if (t != null && t.tools() != null && t.role() == MessageRole.ASSISTANT && shown < p.getReplayTurns()) {
                show[i] = true;
                shown++;
            }
        }
        List<ChatTurn> out = new ArrayList<>(stored.size());
        for (int i = 0; i < stored.size(); i++) {
            modelView(stored.get(i), p, show[i]).ifPresent(out::add);
        }
        return out;
    }
}
