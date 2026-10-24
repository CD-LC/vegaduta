package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.TurnToolActivity.Snapshot;
import com.agenticai.core.agents.turn.TurnToolActivity.Tally;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a CAREFUL / RIGOROUS / PLANNED turn that ran out of time AFTER its tools ran tells the person:
 * which steps completed and which were still running, from {@link TurnToolActivity}. The turn used to
 * be thrown away and answered again at FAST depth with no tools and no tool results, so the model could
 * claim whatever it liked about work it had not seen. The wording is {@code
 * app.chat.resilience.depth-timeout-partial-notice}.
 */
final class DepthTimeoutReport {

    private DepthTimeoutReport() {}

    /** The notice for {@code depth} having stopped after {@code limit} with {@code ran} behind it. */
    static String text(ChatResilienceProperties res, String depth, Duration limit, Snapshot ran) {
        return res.renderPartialNotice(depth, limit, completed(res, ran), running(res, ran));
    }

    /** "send_email, get_order x2": the tools that reached their implementation, with repeat counts. */
    static String completed(ChatResilienceProperties res, Snapshot ran) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Tally> entry : ran.finished().entrySet()) {
            Tally tally = entry.getValue();
            if (tally.reachedTool() == 0) {
                continue;
            }
            StringBuilder part = new StringBuilder(entry.getKey());
            if (tally.reachedTool() > 1) {
                part.append(" x").append(tally.reachedTool());
            }
            parts.add(part.toString());
        }
        return parts.isEmpty() ? res.getListNone() : String.join(", ", parts);
    }

    /** The tools still running when the turn stopped, with repeat counts. */
    static String running(ChatResilienceProperties res, Snapshot ran) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        ran.inFlight().forEach(tool -> counts.merge(tool, 1, Integer::sum));
        List<String> parts = new ArrayList<>();
        counts.forEach((tool, n) -> parts.add(n > 1 ? tool + " x" + n : tool));
        return parts.isEmpty() ? res.getListNone() : String.join(", ", parts);
    }
}
