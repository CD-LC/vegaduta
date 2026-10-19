package com.agenticai.core.agents.capability;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.sdlc.E2bAccessResolver;
import com.agenticai.core.sdlc.E2bCredentialService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The E2B {@code run_code} sandbox tool - the capability-ized form of the old
 * {@code e2b_enabled} column + {@code PATCH /api/agents/{id}/e2b} endpoint.
 * Availability mirrors {@code AgentIntegrationToolResolver#resolveE2bTools}'s
 * own double gate: an ACTIVE E2B credential AND a configured code-execution
 * template, both tenant-level - this flag alone was never enough. With the
 * platform-managed fallback switched on ({@code app.sandbox.platform-e2b.enabled})
 * a configured platform credential also counts - {@link E2bAccessResolver#codeExecutionReady()}
 * is the one predicate both places ask.
 */
@Component
public class E2bCodeExecutionCapability implements AgentCapability {

    private final ObjectProvider<E2bCredentialService> e2bCredentialService;
    private final ObjectProvider<E2bAccessResolver> e2bAccessResolver;

    public E2bCodeExecutionCapability(
        ObjectProvider<E2bCredentialService> e2bCredentialService,
        ObjectProvider<E2bAccessResolver> e2bAccessResolver
    ) {
        this.e2bCredentialService = e2bCredentialService;
        this.e2bAccessResolver = e2bAccessResolver;
    }

    @Override
    public String key() {
        return "e2b-code-execution";
    }

    @Override
    public String label() {
        return "Code execution";
    }

    @Override
    public String description() {
        return "Run short Python/Node/Bash snippets in a disposable sandbox (the run_code tool).";
    }

    @Override
    public String category() {
        return "tools";
    }

    @Override
    public CapabilityAvailability availability() {
        E2bAccessResolver resolver = e2bAccessResolver.getIfAvailable();
        boolean ready;
        if (resolver != null) {
            ready = resolver.codeExecutionReady();
        } else {
            E2bCredentialService service = e2bCredentialService.getIfAvailable();
            ready = service != null && service.isActive() && service.hasCodeExecutionTemplate();
        }
        return ready ? CapabilityAvailability.AVAILABLE : CapabilityAvailability.NEEDS_SETUP;
    }

    @Override
    public String availabilityHint() {
        E2bAccessResolver resolver = e2bAccessResolver.getIfAvailable();
        if (resolver != null && resolver.platformFallbackEnabled()) {
            return "Code execution is not available yet for this workspace. To use it now, activate your own "
                + "E2B key with a code-execution template on the Providers page.";
        }
        return "Activate an E2B key with a code-execution template on the Providers page first.";
    }

    @Override
    public boolean isEnabled(Agent agent) {
        return agent.isE2bEnabled();
    }

    @Override
    public void apply(Agent agent, boolean enabled) {
        agent.setE2bEnabled(enabled);
    }
}
