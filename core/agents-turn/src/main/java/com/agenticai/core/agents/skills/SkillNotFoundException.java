package com.agenticai.core.agents.skills;

public class SkillNotFoundException extends RuntimeException {

    public SkillNotFoundException(String nameOrId) {
        super("No skill \"" + nameOrId + "\" in this workspace.");
    }
}
