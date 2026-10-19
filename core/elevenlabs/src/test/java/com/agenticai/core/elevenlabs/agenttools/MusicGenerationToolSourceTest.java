package com.agenticai.core.elevenlabs.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.MediaToolSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * generate_music through the kernel's MediaToolSource: offered only when some ElevenLabs key can
 * pay.
 */
class MusicGenerationToolSourceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MusicGenerationService> provider(MusicGenerationService service) {
        ObjectProvider<MusicGenerationService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(service);
        return p;
    }

    @Test
    void isTheMusicSlot() {
        assertEquals(
                MediaToolSource.Kind.MUSIC, new MusicGenerationToolSource(provider(null)).kind());
    }

    @Test
    void nothingWithoutTheServiceOrAKey() {
        assertTrue(new MusicGenerationToolSource(provider(null)).rawTools().isEmpty());

        MusicGenerationService service = mock(MusicGenerationService.class);
        when(service.isAvailable()).thenReturn(false);
        assertTrue(new MusicGenerationToolSource(provider(service)).rawTools().isEmpty());
    }

    @Test
    void generateMusicOnceAKeyIsAvailable() {
        MusicGenerationService service = mock(MusicGenerationService.class);
        when(service.isAvailable()).thenReturn(true);
        assertEquals(
                "generate_music",
                new MusicGenerationToolSource(provider(service))
                        .rawTools()
                        .get(0)
                        .getToolDefinition()
                        .name());
    }
}
