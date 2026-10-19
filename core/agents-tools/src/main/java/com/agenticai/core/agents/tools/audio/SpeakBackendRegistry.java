package com.agenticai.core.agents.tools.audio;

import com.agenticai.core.agents.tools.capability.CapabilityRegistries;
import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.agentspi.SpeakCapabilityProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves the best available {@link SpeakBackend} for the current tenant/request by trying every
 * {@link SpeakCapabilityProvider} Spring can find, in priority order. Spring autowires {@code
 * providers} with every {@code @Component} implementing the interface - registering provider N+1 is
 * adding one new bean class, not editing this class or {@code AgentToolProvider}. Replaces the
 * hand-written four-block {@code if (backend == null) { ... }} chain that used to live in {@code
 * AgentToolProvider#resolveAudioTools} (see that method's pre-2026-08-09 history) with the same
 * priority order (ElevenLabs BYOK 10, Sarvam BYOK 20, platform ElevenLabs 30, platform Sarvam 40),
 * now expressed as data instead of code.
 */
@Component
public class SpeakBackendRegistry {

    private final List<SpeakCapabilityProvider> providers;

    public SpeakBackendRegistry(List<SpeakCapabilityProvider> providers) {
        this.providers = providers;
    }

    public Optional<SpeakBackend> resolve() {
        return CapabilityRegistries.resolveBest(providers);
    }
}
