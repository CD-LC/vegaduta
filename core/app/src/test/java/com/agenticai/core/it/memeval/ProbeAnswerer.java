package com.agenticai.core.it.memeval;

import com.agenticai.core.quality.memeval.MemoryRun.ToolCall;
import com.agenticai.core.quality.memeval.MemoryScenario.ToolTask;
import java.util.List;

/**
 * The "model" of the real-platform memory eval: given a probe prompt and the memory lines the
 * platform put in the prompt, it produces the answer and the tool calls the scorers look at. The
 * shipped implementation is {@link ExtractiveProbeAnswerer} (deterministic, no LLM); a real LLM
 * answerer implements this same interface and is handed to {@link PlatformEvalSubject}, the only
 * change a model-backed run needs.
 */
public interface ProbeAnswerer {

    /** Short name that ends up in the scorecard's subject name. */
    String name();

    Answer answer(Question question);

    /**
     * One memory line as the model sees it; {@code origin} is {@code fact}, {@code shared}, {@code
     * episode} or {@code oracle}.
     */
    record RecalledLine(String text, String origin) {}

    /**
     * @param prompt the user's probe message
     * @param recalled the memory lines of the prompt block, in prompt order (empty when memory is
     *     off or found nothing)
     * @param task the tool task of the probe, or null for a plain question
     */
    record Question(String prompt, List<RecalledLine> recalled, ToolTask task) {
        public Question {
            recalled = recalled == null ? List.of() : List.copyOf(recalled);
        }
    }

    record Answer(String text, List<ToolCall> toolCalls) {
        public Answer {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }
}
