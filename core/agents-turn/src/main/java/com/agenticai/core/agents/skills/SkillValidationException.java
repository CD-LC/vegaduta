package com.agenticai.core.agents.skills;

/**
 * A SKILL.md, skill archive or skill field broke the format rules. The message is person-facing.
 */
public class SkillValidationException extends IllegalArgumentException {

    public SkillValidationException(String message) {
        super(message);
    }
}
