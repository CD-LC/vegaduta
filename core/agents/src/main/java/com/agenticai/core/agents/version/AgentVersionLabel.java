package com.agenticai.core.agents.version;

/**
 * A movable pointer naming one {@link AgentVersion} as the blessed snapshot for
 * a given purpose. At most one version per agent may hold each label - enforced
 * by migration V212's partial unique index on {@code (agent_id, label)}, and by
 * {@link AgentVersionService#setLabel} clearing the previous holder first.
 *
 * <p>Deliberately just two values, and deliberately NOT an open-ended free-text
 * tag. A label is a pointer with an invariant ("exactly one production version")
 * and every additional value multiplies the states an operator has to reason
 * about; free text would also mean the partial unique index could no longer be
 * declared per known label. Two is what the item asked for and two is what a
 * promote-through-environments flow actually needs.
 *
 * <p>Neither label CHANGES what the agent does. The agent's live configuration
 * always lives on {@code Agent} itself, exactly as {@code PromptVersion}'s
 * javadoc says of prompt history - labelling a version is bookkeeping about which
 * snapshot a human considers good, not a deployment switch that routes traffic.
 * Restoring is the only thing that changes behavior, and it is a separate,
 * explicit action (see {@link AgentVersionService#restore}). Anything that later
 * wants "run the production version" must resolve the label and restore it, not
 * assume the label alone did something.
 */
public enum AgentVersionLabel {

    /** A snapshot a tenant is currently trying out before promoting it. */
    STAGING,

    /** The snapshot a tenant considers known-good - the one to roll back TO. */
    PRODUCTION
}
