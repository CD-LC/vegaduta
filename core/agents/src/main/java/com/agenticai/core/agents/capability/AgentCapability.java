package com.agenticai.core.agents.capability;

import com.agenticai.core.agents.core.Agent;

/**
 * One attachable per-agent capability (a tool group or integration an agent
 * can be opted into) - the SPI that replaced the per-provider
 * {@code PATCH /api/agents/{id}/sarvam|e2b|xai-server-tools|code-workspace}
 * endpoints and their bespoke workbench sections.
 *
 * <p>Adding a new provider/capability is intentionally a server-side-only
 * change: implement this interface as a {@code @Component} (or extend {@link
 * StoredAgentCapability} when the flag has no dedicated {@code agent} column)
 * and it appears in {@code GET /api/agents/{id}/capabilities} - the workbench
 * renders whatever the catalog returns, so no frontend edit is needed.
 *
 * <p>Labels/descriptions are plain English text served by the catalog, not
 * client-side translation keys: a capability must render correctly the moment
 * it exists, before any locale file knows about it.
 */
public interface AgentCapability {

    /**
     * Stable wire key - the {@code {key}} path segment of {@code PATCH
     * /api/agents/{id}/capabilities/{key}} and (for {@link
     * StoredAgentCapability}) the {@code agent_capability.capability_key}
     * value. Lower-kebab-case, e.g. {@code "sarvam-tools"}.
     */
    String key();

    /** Short display name, e.g. "Sarvam tools". */
    String label();

    /** One or two sentences explaining what enabling it does and what setup it needs. */
    String description();

    /**
     * Grouping hint for catalog consumers that want sections (the workbench
     * currently renders one flat list) - e.g. {@code "tools"},
     * {@code "integrations"}.
     */
    String category();

    /**
     * Whether this capability is even meaningful for the given agent - the
     * server-side replacement for the workbench's old {@code providerName ===
     * "GROK"} client gate. Non-applicable capabilities are simply omitted
     * from the catalog response for that agent.
     */
    default boolean appliesTo(Agent agent) {
        return true;
    }

    /** See {@link CapabilityAvailability}. */
    CapabilityAvailability availability();

    /**
     * Optional short pointer for NEEDS_SETUP/UNAVAILABLE states, e.g.
     * "Activate the provider key on the Providers page." Null when
     * {@link #availability()} is AVAILABLE.
     */
    default String availabilityHint() {
        return null;
    }

    /** Current on/off state for this agent. */
    boolean isEnabled(Agent agent);

    /**
     * Mutate the agent's state for this capability. Column-backed
     * implementations call the matching {@code Agent} setter; {@link
     * StoredAgentCapability} writes an {@code agent_capability} row instead.
     * The caller ({@code AgentService#setCapabilityEnabled}) owns access
     * checks, edit-lock, save and version-snapshotting - implementations only
     * mutate state.
     */
    void apply(Agent agent, boolean enabled);
}
