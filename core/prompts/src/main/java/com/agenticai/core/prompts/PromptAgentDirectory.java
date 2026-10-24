package com.agenticai.core.prompts;

import java.util.UUID;

/**
 * Port: does an agent exist in the current tenant? An agent-scope override is accepted only for an
 * agent of the calling thread's tenant, so an id belonging to another tenant (or to nothing) is
 * rejected. Implemented by the agents module.
 */
public interface PromptAgentDirectory {

    boolean existsInCurrentTenant(UUID agentId);

    /**
     * Whether the CURRENT CALLER may read the agent (agent VIEWER or better: owner, share grant, tenant
     * admin), not merely whether it exists in the tenant. A read path that hands a caller text taken from an
     * agent's own scope must ask this, so a tenant member with no access to an agent cannot read what its
     * owner configured for it. The default is {@link #existsInCurrentTenant} for implementations that have no
     * access model (tests); the agents module overrides it with the real check.
     */
    default boolean canViewInCurrentTenant(UUID agentId) {
        return existsInCurrentTenant(agentId);
    }
}
