package com.agenticai.core.agents.skills;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.settings.SettingsRig;
import com.agenticai.core.settings.SettingDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code skills.prompt.maxSkills} and {@code skills.prompt.maxBlockChars}: the skill index of a prompt follows the tenant's
 * setting, {@code app.skills.prompt.*} when there is none. Through the static {@link SkillPromptBlock} every caller uses,
 * with the overlay {@link SkillPromptConfiguration} installs.
 */
class SkillPromptSettingsTest {

    private final SkillPromptSettings contributor = new SkillPromptSettings();
    private final SettingsRig rig = new SettingsRig(contributor);

    @AfterEach
    void restore() {
        SkillPromptProperties.useTenantOverlay(null);
        SkillPromptProperties.activate(new SkillPromptProperties());
        SettingsRig.clear();
    }

    private void installOverlay() {
        SkillPromptProperties.useTenantOverlay(bound -> SkillPromptSettings.effective(bound, rig.resolver()));
    }

    private static List<SkillService.SkillMetadata> skills(int count, String description) {
        List<SkillService.SkillMetadata> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new SkillService.SkillMetadata(
                    UUID.randomUUID(), String.format("skill-%03d", i), description, SkillSource.CUSTOM, true, 3));
        }
        return out;
    }

    private static long listed(String block) {
        return block.lines().filter(l -> l.startsWith("- skill-")).count();
    }

    @Test
    void theEntriesAnswerTheBoundPropertiesAndAreBounded() {
        SkillPromptProperties bound = new SkillPromptProperties();
        bound.setMaxSkills(33);
        bound.setMaxBlockChars(4_321);
        SkillPromptProperties.activate(bound);
        for (SettingDefinition d : contributor.definitions()) {
            assertThat(d.platformValue()).as(d.key()).isEqualTo(d.key().endsWith("maxSkills") ? 33L : 4_321L);
            assertThat(d.min()).isNotNull();
            assertThat(d.max()).isNotNull();
        }
    }

    @Test
    void aTenantsMaxSkillsLimitsItsPromptAndAnotherTenantKeepsTheDefault() {
        installOverlay();
        rig.tenant("tenant-a", "skills.prompt.maxSkills", 5);
        List<SkillService.SkillMetadata> many = skills(20, "Does a thing.");

        SettingsRig.inTenant("tenant-a", () -> assertThat(listed(SkillPromptBlock.render(many))).isEqualTo(5));
        SettingsRig.inTenant("tenant-b", () -> assertThat(listed(SkillPromptBlock.render(many))).isEqualTo(20));
        assertThat(listed(SkillPromptBlock.render(many))).as("no tenant on the thread: the platform's").isEqualTo(20);
    }

    @Test
    void aTenantsBlockBudgetShrinksTheLaterEntriesToBareNames() {
        installOverlay();
        rig.tenant("tenant-a", "skills.prompt.maxBlockChars", 1_000);
        List<SkillService.SkillMetadata> many = skills(60, "word ".repeat(25).strip());

        String[] tenant = new String[1];
        String[] platform = new String[1];
        SettingsRig.inTenant("tenant-a", () -> tenant[0] = SkillPromptBlock.render(many));
        SettingsRig.inTenant("tenant-b", () -> platform[0] = SkillPromptBlock.render(many));

        assertThat(tenant[0].length()).isLessThan(platform[0].length());
        assertThat(tenant[0]).contains("- skill-000: word").contains("- skill-059\n").doesNotContain("- skill-059: word");
        assertThat(platform[0]).contains("- skill-059: word");
    }

    @Test
    void theCompactListFollowsTheSameLimit() {
        installOverlay();
        rig.tenant("tenant-a", "skills.prompt.maxSkills", 2);

        SettingsRig.inTenant("tenant-a", () ->
                assertThat(SkillPromptBlock.renderCompact(skills(5, "x"))).contains("skill-000, skill-001").doesNotContain("skill-002"));
    }

    @Test
    void aFailingCatalogLeavesThePlatformProperties() {
        SkillPromptProperties.useTenantOverlay(bound -> {
            throw new IllegalStateException("settings down");
        });
        assertThat(listed(SkillPromptBlock.render(skills(20, "Does a thing.")))).isEqualTo(20);
    }

    @Test
    void theOtherLimitsOfTheBoundPropertiesAreKept() {
        SkillPromptProperties bound = new SkillPromptProperties();
        bound.setMaxDescriptionWords(3);
        bound.setHistoryTurns(9);
        SkillPromptProperties.activate(bound);
        installOverlay();
        rig.tenant("tenant-a", "skills.prompt.maxSkills", 5);

        SettingsRig.inTenant("tenant-a", () -> {
            SkillPromptProperties effective = SkillPromptProperties.active();
            assertThat(effective.getMaxSkills()).isEqualTo(5);
            assertThat(effective.getMaxDescriptionWords()).isEqualTo(3);
            assertThat(effective.getHistoryTurns()).isEqualTo(9);
        });
    }
}
