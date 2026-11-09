package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An ordinary question must not switch the skills block (about 700 tokens with its tools) on: the skill gate
 * matches on a skill's name and distinctive description words, not on words every question uses.
 */
class SkillMatchPrecisionTest {

    private static SkillService.SkillMetadata skill(String name, String description) {
        return new SkillService.SkillMetadata(
                UUID.randomUUID(), name, description, SkillSource.BUILT_IN, true, 0);
    }

    private final List<SkillService.SkillMetadata> skills =
            List.of(
                    skill(
                            "generative-ui",
                            "Render an interactive interface instead of plain text. Use when the user wants a quick, short answer"
                                    + " shown as cards or a dashboard."),
                    skill("media-use", "Fetch or transform images, audio and video files."),
                    skill("docx", "Create Word documents as downloadable .docx files."),
                    skill("email-writer", "Write and reply to professional email with a clear subject."));

    @Test
    void wordsEveryQuestionUsesDoNotSwitchSkillsOn() {
        assertFalse(SkillPromptBlock.anyMatch(skills, "What is the capital of France? Answer in one short sentence."));
        assertFalse(SkillPromptBlock.anyMatch(skills, "Use your tools to add 1234 and 4321 and tell me the result."));
        assertFalse(SkillPromptBlock.anyMatch(skills, "Reply with exactly the single word OK and nothing else."));
    }

    @Test
    void realSkillRequestsStillMatch() {
        assertTrue(SkillPromptBlock.anyMatch(skills, "Write me a Word document with the quarterly summary"));
        assertTrue(SkillPromptBlock.anyMatch(skills, "Save the report as a Word file"));
        assertTrue(SkillPromptBlock.anyMatch(skills, "Export this to Word"));
        assertTrue(SkillPromptBlock.anyMatch(skills, "Draft an email to the customer about the delay"));
        assertTrue(SkillPromptBlock.anyMatch(skills, "Pull the audio out of this video"));
    }
}
