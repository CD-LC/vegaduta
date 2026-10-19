package com.agenticai.core.agents.tools.capability;

import com.agenticai.core.agentspi.CapabilityProvider;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Shared selection logic every per-capability registry (e.g. {@code
 * com.agenticai.core.agents.tools.audio.SpeakBackendRegistry}) delegates to: try each {@link
 * CapabilityProvider} in ascending priority order, return the first one that resolves. One place
 * for this rule so every capability's registry behaves identically rather than re-implementing the
 * same sort-then-first-match loop.
 */
public final class CapabilityRegistries {

    private CapabilityRegistries() {}

    public static <T> Optional<T> resolveBest(List<? extends CapabilityProvider<T>> providers) {
        return providers.stream()
                .sorted(Comparator.comparingInt(CapabilityProvider::priority))
                .map(CapabilityProvider::resolve)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }
}
