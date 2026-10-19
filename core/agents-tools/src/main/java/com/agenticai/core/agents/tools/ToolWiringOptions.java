package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.a2a.A2aRemoteAgentToolSource;
import com.agenticai.core.agents.collab.SwarmVerifierConfig;
import com.agenticai.core.agents.tools.e2b.PlatformSandboxRunQuota;
import com.agenticai.core.agents.tools.e2b.SandboxArtifactRetention;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import com.agenticai.core.sdlc.E2bAccessResolver;
import com.agenticai.core.storage.FileStorageService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The optional collaborators that used to reach {@code AgentToolProvider} through a dozen
 * {@code @Autowired} setters and were forwarded to the resolvers one by one. Everything is an
 * {@link ObjectProvider} (or a plain config value): an absent bean means the feature is off, never
 * a wiring failure. Each resolver bean takes this once and applies the part that concerns it.
 */
@Component
final class ToolWiringOptions {

    private final ObjectProvider<A2aRemoteAgentToolSource> a2aClient;
    private final SwarmVerifierConfig swarmVerifier;
    private final ObjectProvider<MeterRegistry> meters;
    private final ObjectProvider<SandboxFileRegistry> sandboxFiles;
    private final ObjectProvider<FileStorageService> storage;
    private final ObjectProvider<SandboxArtifactRetention> sandboxRetention;
    private final ObjectProvider<E2bAccessResolver> e2bAccessResolver;
    private final ObjectProvider<PlatformSandboxRunQuota> platformSandboxQuota;

    ToolWiringOptions(
            ObjectProvider<A2aRemoteAgentToolSource> a2aClient,
            SwarmVerifierConfig swarmVerifier,
            ObjectProvider<MeterRegistry> meters,
            ObjectProvider<SandboxFileRegistry> sandboxFiles,
            ObjectProvider<FileStorageService> storage,
            ObjectProvider<SandboxArtifactRetention> sandboxRetention,
            ObjectProvider<E2bAccessResolver> e2bAccessResolver,
            ObjectProvider<PlatformSandboxRunQuota> platformSandboxQuota) {
        this.a2aClient = a2aClient;
        this.swarmVerifier = swarmVerifier;
        this.meters = meters;
        this.sandboxFiles = sandboxFiles;
        this.storage = storage;
        this.sandboxRetention = sandboxRetention;
        this.e2bAccessResolver = e2bAccessResolver;
        this.platformSandboxQuota = platformSandboxQuota;
    }

    void applyTo(AgentIntegrationToolResolver resolver) {
        resolver.setA2aClient(a2aClient);
        resolver.setSandboxDataSupport(sandboxFiles, storage, sandboxRetention);
        resolver.setPlatformSandboxSupport(e2bAccessResolver, platformSandboxQuota);
    }

    void applyTo(AgentCustomToolResolver resolver) {
        resolver.setSwarmVerifier(swarmVerifier, meters);
    }
}
