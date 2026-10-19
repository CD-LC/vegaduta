package com.agenticai.core.agents.dto;

import com.agenticai.core.security.ExperienceMode;

/** Null clears the override, going back to "inherit the tenant's current default." */
public record AgentExperienceModeUpdateRequest(ExperienceMode experienceMode) {
}
