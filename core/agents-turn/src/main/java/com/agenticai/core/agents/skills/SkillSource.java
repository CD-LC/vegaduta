package com.agenticai.core.agents.skills;

public enum SkillSource {
    /** Ships on core's classpath - reviewed platform code, present for every tenant. */
    BUILT_IN,
    /** Uploaded or authored by this tenant - untrusted content. */
    CUSTOM
}
