package com.agenticai.core.agents.skills;

/** Where a custom skill's content came from (migration V590). */
public enum SkillOrigin {
    /** Uploaded as an Agent Skills archive or a single SKILL.md, or installed from the registry. */
    IMPORTED,
    /** Drafted by an agent: the {@code propose_skill} tool, distillation or the learning loop. */
    LEARNED,
    /** Written in the console editor or through the API. */
    AUTHORED
}
