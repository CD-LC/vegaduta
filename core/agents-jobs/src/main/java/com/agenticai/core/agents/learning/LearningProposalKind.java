package com.agenticai.core.agents.learning;

/**
 * What a learning proposal would change once approved. Each kind has (at most) one {@link
 * LearningProposalApplier}; a kind with none cannot be approved (409).
 *
 * <p>Payload conventions (JSON object, all keys optional unless stated):
 *
 * <ul>
 *   <li>{@link #LESSON}: {@code agentId} (uuid, required when the proposal has no agent) - the agent whose
 *       private memory keeps the lesson.
 *   <li>{@link #PREFERENCE}: none. The body is appended as a bullet to the person's {@code user_profile} block.
 *   <li>{@link #ROUTINE}, {@link #CONSOLIDATION}: defined by the loop that registers the applier.
 * </ul>
 */
public enum LearningProposalKind {
    LESSON,
    PREFERENCE,
    ROUTINE,
    CONSOLIDATION,
    DESKTOP_RECIPE
}
