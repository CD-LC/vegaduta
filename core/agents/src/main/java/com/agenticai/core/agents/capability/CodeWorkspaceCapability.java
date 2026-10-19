package com.agenticai.core.agents.capability;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.codeintel.CodeIntelClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Code-intelligence write/execute tools (create/apply/run/diff/
 * delete_code_workspace) - the capability-ized form of the old {@code
 * code_workspace_enabled} column + {@code PATCH /api/agents/{id}/code-workspace}
 * endpoint. The flag alone still grants access to no repo - the per-agent
 * repo allow-list ({@code PATCH .../code-intel-repo-access}, deny-all when
 * empty) is a separate, unchanged control.
 *
 * <p>Availability replaces the workbench's old "is {@code search_code} in
 * {@code /available-tools}" probe with the direct signal that probe was
 * approximating: the {@link CodeIntelClient} bean only exists when {@code
 * code-intel.enabled=true} on this deployment.
 */
@Component
public class CodeWorkspaceCapability implements AgentCapability {

    private final ObjectProvider<CodeIntelClient> codeIntelClient;

    public CodeWorkspaceCapability(ObjectProvider<CodeIntelClient> codeIntelClient) {
        this.codeIntelClient = codeIntelClient;
    }

    @Override
    public String key() {
        return "code-workspace";
    }

    @Override
    public String label() {
        return "Code workspace tools";
    }

    @Override
    public String description() {
        return "Let this agent make and run changes in the repositories you explicitly grant it access to.";
    }

    @Override
    public String category() {
        return "tools";
    }

    @Override
    public CapabilityAvailability availability() {
        return codeIntelClient.getIfAvailable() != null
            ? CapabilityAvailability.AVAILABLE
            : CapabilityAvailability.UNAVAILABLE;
    }

    @Override
    public String availabilityHint() {
        return "Code intelligence is not enabled on this deployment.";
    }

    @Override
    public boolean isEnabled(Agent agent) {
        return agent.isCodeWorkspaceEnabled();
    }

    @Override
    public void apply(Agent agent, boolean enabled) {
        agent.setCodeWorkspaceEnabled(enabled);
    }
}
