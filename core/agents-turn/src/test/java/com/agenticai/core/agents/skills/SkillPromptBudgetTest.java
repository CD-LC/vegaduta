package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Progressive disclosure at scale: only name + description of a skill is in the prompt, and that
 * index has a hard size budget however many skills a workspace holds.
 */
class SkillPromptBudgetTest {

    private static List<SkillService.SkillMetadata> skills(int count, int descriptionWords) {
        List<SkillService.SkillMetadata> out = new ArrayList<>();
        String description = "word ".repeat(descriptionWords).strip();
        for (int i = 0; i < count; i++) {
            out.add(
                    new SkillService.SkillMetadata(
                            UUID.randomUUID(),
                            String.format("skill-%03d", i),
                            description + " Use when case " + i + ".",
                            SkillSource.CUSTOM,
                            true,
                            3));
        }
        return out;
    }

    @Test
    void twoHundredSkillsStayInsideTheBudget() {
        String block = SkillPromptBlock.render(skills(200, 400));
        int ceiling =
                new SkillPromptProperties().getMaxBlockChars() + new SkillPromptProperties().getNamesOnlyHeadroom() + 400 /* header + footer */;
        assertTrue(block.length() <= ceiling, "block is " + block.length() + " chars");
        // Roughly 2-3k tokens, not the ~100k that 200 full descriptions would be.
        assertTrue(block.length() < 12_000);
        long listed = block.lines().filter(l -> l.startsWith("- skill-")).count();
        assertTrue(listed <= new SkillPromptProperties().getMaxSkills(), "listed " + listed);
        assertTrue(block.contains("more skills are installed but not listed here"));
    }

    @Test
    void theIndexNeverCarriesBodiesOrFiles() {
        // SkillMetadata has no body and no file content at all; the block can only contain what the
        // metadata holds. A body marker placed in the description is the only text that can appear.
        List<SkillService.SkillMetadata> one =
                List.of(
                        new SkillService.SkillMetadata(
                                UUID.randomUUID(),
                                "refund-replies",
                                "Drafts refund replies.",
                                SkillSource.CUSTOM,
                                true,
                                9));
        String block = SkillPromptBlock.render(one);
        assertTrue(block.contains("- refund-replies: Drafts refund replies."));
        assertFalse(block.contains("9"), "file counts are not advertised");
    }

    @Test
    void descriptionsShrinkToNamesBeforeTheListIsCut() {
        // 40 skills with ~30-word descriptions do not fit in 9,000 chars with full text, but all
        // 40 names do: every one stays loadable.
        List<SkillService.SkillMetadata> many = skills(40, 60);
        String block = SkillPromptBlock.render(many);
        for (SkillService.SkillMetadata m : many) {
            assertTrue(block.contains("- " + m.name()), m.name() + " missing");
        }
        assertFalse(block.contains("not listed here"));
        assertTrue(block.length() <= new SkillPromptProperties().getMaxBlockChars() + 2_000 + 400);
    }

    @Test
    void aSmallCatalogIsRenderedExactlyAsBefore() {
        List<SkillService.SkillMetadata> few = skills(5, 8);
        String block = SkillPromptBlock.render(few);
        assertEquals(5, block.lines().filter(l -> l.startsWith("- skill-")).count());
        assertTrue(block.lines().filter(l -> l.startsWith("- skill-")).allMatch(l -> l.contains(": word")));
        assertFalse(block.contains("not listed here"));
    }

    @Test
    void theBlockIsByteStableTurnToTurn() {
        List<SkillService.SkillMetadata> catalog = skills(200, 100);
        assertEquals(SkillPromptBlock.render(catalog), SkillPromptBlock.render(catalog));
        assertEquals(
                SkillPromptBlock.renderCompact(catalog), SkillPromptBlock.renderCompact(catalog));
    }

    @Test
    void compactModeIsNamesOnlyAndCapped() {
        String compact = SkillPromptBlock.renderCompact(skills(200, 400));
        assertTrue(compact.length() < 2_000, "compact is " + compact.length());
        assertFalse(compact.contains("word"));
    }

    @Test
    void nothingToOfferIsAnEmptyString() {
        assertEquals("", SkillPromptBlock.render(List.of()));
        assertEquals("", SkillPromptBlock.render(null));
    }
}
