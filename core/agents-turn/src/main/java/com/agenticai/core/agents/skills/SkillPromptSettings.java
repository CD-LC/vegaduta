package com.agenticai.core.agents.skills;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import com.agenticai.core.settings.SettingsResolver;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * How large the skill index in the system prompt may be, as catalog entries ({@code skills.prompt.maxSkills} and {@code
 * skills.prompt.maxBlockChars}), over {@code app.skills.prompt.*}. A workspace with many skills can list more of them;
 * a workspace that wants a lean prompt can list fewer.
 *
 * <p>Tenant scope only: {@link SkillPromptBlock} is a static helper called from several modules with only the skill
 * list in hand, so the one thing every caller has is the calling thread's tenant. {@link SkillPromptProperties#active()}
 * applies the overlay {@link #effective} makes, installed by {@link SkillPromptConfiguration}; a thread without a tenant,
 * a missing catalog or a failing read leaves the platform values.
 */
@Component
public class SkillPromptSettings implements SettingsContributor {

    private static final Logger log = LoggerFactory.getLogger(SkillPromptSettings.class);

    static final String MAX_SKILLS = "skills.prompt.maxSkills";
    static final String MAX_BLOCK_CHARS = "skills.prompt.maxBlockChars";

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                SettingDefinition.builder(MAX_SKILLS, "skills", SettingType.INT)
                        .unit(SettingUnit.COUNT)
                        .range(1, 200)
                        .risk(SettingRisk.LOW)
                        .scopes(SettingScope.TENANT)
                        .platform(() -> SkillPromptProperties.platform().getMaxSkills())
                        .property("app.skills.prompt.max-skills")
                        .docsAnchor("behaviour-settings")
                        .build(),
                SettingDefinition.builder(MAX_BLOCK_CHARS, "skills", SettingType.INT)
                        .unit(SettingUnit.CHARS)
                        .range(1_000, 30_000)
                        .risk(SettingRisk.LOW)
                        .scopes(SettingScope.TENANT)
                        .platform(() -> SkillPromptProperties.platform().getMaxBlockChars())
                        .property("app.skills.prompt.max-block-chars")
                        .docsAnchor("behaviour-settings")
                        .build());
    }

    /** {@code bound} with the calling thread's tenant's settings applied; {@code bound} itself when nothing differs. */
    static SkillPromptProperties effective(SkillPromptProperties bound, SettingsResolver catalog) {
        if (catalog == null) {
            return bound;
        }
        try {
            int maxSkills = catalog.getInt(MAX_SKILLS, SettingContext.tenant());
            int maxBlockChars = catalog.getInt(MAX_BLOCK_CHARS, SettingContext.tenant());
            if (maxSkills == bound.getMaxSkills() && maxBlockChars == bound.getMaxBlockChars()) {
                return bound;
            }
            return bound.with(maxSkills, maxBlockChars);
        } catch (RuntimeException e) {
            log.warn("Could not read the skills.prompt.* settings; using app.skills.prompt.*: {}", e.toString());
            return bound;
        }
    }
}
