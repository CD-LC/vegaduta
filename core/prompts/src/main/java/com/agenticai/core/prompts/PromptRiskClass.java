package com.agenticai.core.prompts;

/**
 * How much a prompt text matters, so the console can warn before an edit and the preview can group
 * blocks. Declared per key in the default resource file ({@code risk:}).
 */
public enum PromptRiskClass {
    /** Part of the system text of (nearly) every turn of an agent that has the feature. */
    STEERS_EVERY_TURN,
    /** The instruction of a secondary model call: summarizer, judge, verifier, router, planner. */
    SECONDARY_CALL,
    /** Text the end user reads: a refusal, a fallback, a failure notice. */
    USER_VISIBLE,
    /** The description (or a parameter description) of a tool, sent in every request's tool list. */
    TOOL_DESCRIPTION
}
