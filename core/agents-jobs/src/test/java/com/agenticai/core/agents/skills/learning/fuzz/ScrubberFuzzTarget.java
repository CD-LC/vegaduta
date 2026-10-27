package com.agenticai.core.agents.skills.learning.fuzz;

import com.agenticai.core.agents.skills.learning.SkillDraftScrubber;
import java.nio.charset.StandardCharsets;

/**
 * Jazzer entry point for {@link SkillDraftScrubber#basic()}'s {@code scrub} - the regex chain that
 * strips credential shapes out of drafted skills before they reach a provider. Same contract as the
 * chat redaction target: rejections are fine, hangs are findings.
 */
public final class ScrubberFuzzTarget {

    private static final SkillDraftScrubber SCRUBBER = SkillDraftScrubber.basic();

    private ScrubberFuzzTarget() {}

    public static void fuzzerTestOneInput(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        String input = new String(data, StandardCharsets.UTF_8);
        try {
            SCRUBBER.scrub(input);
        } catch (RuntimeException | StackOverflowError expected) {
            // Scrubbing is allowed to reject input; only hangs/crashes are findings.
        }
    }
}
