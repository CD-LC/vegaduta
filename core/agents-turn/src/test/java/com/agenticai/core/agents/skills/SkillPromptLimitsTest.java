package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The skill index in the prompt is bounded by {@link SkillPromptProperties}, not by constants. */
class SkillPromptLimitsTest {

    @AfterEach
    void restoreDefaults() {
        SkillPromptProperties.activate(new SkillPromptProperties());
    }

    private static List<SkillService.SkillMetadata> skills(int count, String description) {
        List<SkillService.SkillMetadata> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new SkillService.SkillMetadata(
                    UUID.randomUUID(), String.format("skill-%03d", i), description, SkillSource.CUSTOM, true, 3));
        }
        return out;
    }

    @Test
    void theDefaultsAreTheOldConstants() {
        SkillPromptProperties p = new SkillPromptProperties();
        assertEquals(60, p.getMaxSkills());
        assertEquals(9_000, p.getMaxBlockChars());
        assertEquals(2_000, p.getNamesOnlyHeadroom());
        assertEquals(30, p.getMaxDescriptionWords());
        assertEquals(6, p.getHistoryTurns());
        assertEquals(2_000, p.getMaxTrackedSessions());
    }

    @Test
    void theNumberOfListedSkillsIsConfigurable() {
        SkillPromptProperties p = new SkillPromptProperties();
        p.setMaxSkills(3);

        String block = SkillPromptBlock.render(skills(10, "Does a thing."), p);

        assertEquals(3, block.lines().filter(l -> l.startsWith("- skill-")).count());
        assertTrue(block.contains("(7 more skills are installed but not listed here)"));
    }

    @Test
    void theBlockBudgetIsConfigurable() {
        SkillPromptProperties p = new SkillPromptProperties();
        p.setMaxBlockChars(600);
        p.setNamesOnlyHeadroom(0);

        String block = SkillPromptBlock.render(skills(50, "word ".repeat(25).strip()), p);

        assertTrue(block.length() <= 600 + 100, "block is " + block.length());
        assertTrue(block.contains("more skills are installed but not listed here"));
    }

    @Test
    void theDescriptionLengthIsConfigurable() {
        SkillPromptProperties p = new SkillPromptProperties();
        p.setMaxDescriptionWords(3);

        String block = SkillPromptBlock.render(skills(1, "one two three four five"), p);

        assertTrue(block.contains("- skill-000: one two three...\n"), block);
        assertFalse(block.contains("four"));
    }

    @Test
    void theCompactListHonoursTheSkillLimit() {
        SkillPromptProperties p = new SkillPromptProperties();
        p.setMaxSkills(2);

        String block = SkillPromptBlock.renderCompact(skills(5, "x"), p);

        assertTrue(block.contains("skill-000, skill-001."), block);
        assertFalse(block.contains("skill-002"));
    }

    @Test
    void theActivatedSettingsApplyToTheOneArgumentRender() {
        SkillPromptProperties p = new SkillPromptProperties();
        p.setMaxSkills(1);
        SkillPromptProperties.activate(p);

        String block = SkillPromptBlock.render(skills(4, "Does a thing."));

        assertEquals(1, block.lines().filter(l -> l.startsWith("- skill-")).count());
    }

    @Test
    void theStickyGateRemembersTheConfiguredNumberOfTurns() {
        SkillPromptProperties p = new SkillPromptProperties();
        p.setHistoryTurns(1);
        List<SkillService.SkillMetadata> one = List.of(new SkillService.SkillMetadata(
                UUID.randomUUID(), "xlsx", "Spreadsheet files.", SkillSource.CUSTOM, true, 3));
        SkillPromptBlock.SessionGate gate = new SkillPromptBlock.SessionGate(() -> p);

        assertTrue(gate.shouldOffer("s", one, "make a spreadsheet"));
        assertTrue(gate.shouldOffer("s", one, "yes"));
        assertFalse(gate.shouldOffer("s", one, "and then"), "the matching message is past the one-turn memory");
    }
}
