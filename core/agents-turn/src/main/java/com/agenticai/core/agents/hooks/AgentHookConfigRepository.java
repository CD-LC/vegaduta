package com.agenticai.core.agents.hooks;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes over {@code agent_hook_config} (migration V440). The primary key is the scope key
 * ({@code tenant} or an agent uuid), so every access is a primary-key lookup.
 */
public interface AgentHookConfigRepository extends JpaRepository<AgentHookConfig, String> {
}
