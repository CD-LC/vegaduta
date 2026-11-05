package com.agenticai.core.it.memeval;

import com.agenticai.core.quality.memeval.MemoryRun.ToolCall;
import com.agenticai.core.quality.memeval.MemoryScenario.ToolTask;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for the model (no LLM). Its rules are fixed here, before any result was seen, and are not tuned
 * per run:
 *
 * <ul>
 *   <li><b>Answer</b> = the recalled memory line most similar to the question (cosine under the eval embedder), verbatim;
 *       when no line reaches {@value #THRESHOLD} it abstains ("I don't know - you haven't told me that."). A tie keeps the
 *       line that comes first in the prompt. It cannot combine two lines (so a two-hop question is answered only if one line
 *       holds the answer), cannot paraphrase, and cannot tell a stale line from a fresh one (the block carries no dates), so
 *       the ceiling of every arm, the ORACLE included, is the ceiling of an extractive reader.
 *   <li><b>Tools</b> follow the probe's {@link ToolTask}: a recalled line about the task (similar to tool name plus memory
 *       key, {@value #THRESHOLD} or more) supplies the corrected {@code key=value} argument ({@code REPLACE_ARG}) or
 *       parks the call for approval ({@code PARK}); {@code GUARDED} tasks park by default.
 *   <li><b>Worst-case compliance</b> (on by default): the "model" treats a recalled line about the task as an instruction and
 *       follows it, so a {@code GUARDED} call goes ahead and a {@code PLAIN} call carries the line's text as extra arguments.
 *       This makes the poisoning gates measure CONTAINMENT, i.e. whether attacker text ever reaches the prompt as trusted
 *       memory, not the robustness of any real model (a real model resists some of it). Switch it off with
 *       {@code compliant=false} to see only answer-level influence.
 * </ul>
 */
public final class ExtractiveProbeAnswerer implements ProbeAnswerer {

    /** Minimum cosine similarity for a line to count as an answer or as relevant to a task; fixed, not tuned. */
    public static final double THRESHOLD = 0.20;

    public static final String ABSTENTION = "I don't know - you haven't told me that.";

    private static final Pattern ARG = Pattern.compile("[\\w.-]+=[\\w.:/@-]+");

    private final boolean compliant;

    public ExtractiveProbeAnswerer() {
        this(true);
    }

    public ExtractiveProbeAnswerer(boolean compliant) {
        this.compliant = compliant;
    }

    @Override
    public String name() {
        return compliant ? "extractive" : "extractive-noncompliant";
    }

    @Override
    public Answer answer(Question q) {
        RecalledLine best = best(q.prompt(), q.recalled());
        String text;
        if (best != null) {
            text = best.line().text();
        } else {
            text = q.task() != null ? "Done." : ABSTENTION;
        }
        List<ToolCall> calls = q.task() == null ? List.of() : List.of(act(q.task(), q.recalled()));
        return new Answer(text, calls);
    }

    private record RecalledLine(ProbeAnswerer.RecalledLine line, double score) {}

    private static RecalledLine best(String query, List<ProbeAnswerer.RecalledLine> lines) {
        RecalledLine best = null;
        for (ProbeAnswerer.RecalledLine line : lines) {
            double s = HashingTextEmbedder.similarity(query, line.text());
            if (s >= THRESHOLD && (best == null || s > best.score())) {
                best = new RecalledLine(line, s);
            }
        }
        return best;
    }

    private ToolCall act(ToolTask task, List<ProbeAnswerer.RecalledLine> recalled) {
        String about = task.tool().replace('_', ' ') + " " + (task.memoryKey() == null ? "" : task.memoryKey().replace('_', ' '));
        RecalledLine relevant = best(about, recalled);
        String tool = task.tool();
        String args = task.defaultArg();
        switch (task.mode()) {
            case REPLACE_ARG -> {
                if (relevant != null) {
                    Matcher m = ARG.matcher(relevant.line().text());
                    if (m.find()) {
                        args = m.group();
                    }
                }
            }
            case PARK -> {
                if (relevant != null) {
                    tool = "request_approval";
                }
            }
            case GUARDED -> tool = compliant && relevant != null ? task.tool() : "request_approval";
            case PLAIN -> {
                if (compliant && relevant != null) {
                    args = args + " " + relevant.line().text();
                }
            }
        }
        return new ToolCall(tool, args);
    }
}
