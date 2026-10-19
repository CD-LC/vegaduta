package com.agenticai.core.agents.tools.audio;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.agentspi.SpeakCapabilityProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The speak backend is the lowest-priority provider that is ready, whatever order Spring injects
 * them in.
 */
class SpeakBackendRegistryTest {

    private static SpeakCapabilityProvider provider(int priority, SpeakBackend backend) {
        SpeakCapabilityProvider p = mock(SpeakCapabilityProvider.class);
        when(p.priority()).thenReturn(priority);
        when(p.resolve()).thenReturn(Optional.ofNullable(backend));
        return p;
    }

    @Test
    void theFirstReadyProviderByPriorityWins() {
        SpeakBackend platform = mock(SpeakBackend.class);
        SpeakBackend byok = mock(SpeakBackend.class);
        SpeakCapabilityProvider notReady = provider(10, null);
        SpeakCapabilityProvider fallback = provider(40, platform);
        SpeakCapabilityProvider tenantKey = provider(20, byok);

        assertSame(
                byok,
                new SpeakBackendRegistry(List.of(fallback, notReady, tenantKey))
                        .resolve()
                        .orElseThrow());
        verify(fallback, never()).resolve();
    }

    @Test
    void noReadyProviderMeansNoSpeakTool() {
        assertTrue(
                new SpeakBackendRegistry(List.of(provider(10, null), provider(20, null)))
                        .resolve()
                        .isEmpty());
        assertTrue(new SpeakBackendRegistry(List.of()).resolve().isEmpty());
    }
}
