package com.agenticai.core.agents.skills;

import java.util.Map;
import java.util.UUID;

/**
 * One resolved skill, whichever place it came from. {@code files} maps a normalised relative path
 * to its text content and never includes SKILL.md itself.
 *
 * @param id null for a built-in skill (it has no row), the {@code skill.id} for a custom one
 */
public record SkillDefinition(
        UUID id,
        String name,
        String description,
        String body,
        Map<String, String> files,
        SkillSource source) {

    public SkillDefinition {
        files = files == null ? Map.of() : Map.copyOf(files);
    }

    public boolean builtIn() {
        return source == SkillSource.BUILT_IN;
    }
}
