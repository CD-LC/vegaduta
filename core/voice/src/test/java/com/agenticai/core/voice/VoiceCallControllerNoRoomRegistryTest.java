package com.agenticai.core.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.voice.dto.VoiceTokenResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A lean (personal) edition does not register {@link VoiceCallRoomRegistry}; the authenticated call
 * token endpoint must still mint a token, it just records no room owner.
 */
@DisplayName("Voice Call Controller - no room registry (lean edition)")
class VoiceCallControllerNoRoomRegistryTest extends VoiceCallControllerTestBase {

    @Test
    @DisplayName("issueToken() works and records nothing when the room registry is absent")
    void issueTokenWorksWithoutARoomRegistry() {
        controller = newController(Optional.empty(), null, Optional.empty());
        when(tokenService.issueToken(anyString(), anyString(), anyString(), any(Map.class)))
                .thenReturn("jwt");
        when(tokenService.getUrl()).thenReturn("wss://livekit.example");

        VoiceTokenResponse response = controller.issueToken(testAgentId, null, null, null);

        assertNotNull(response);
        assertEquals("jwt", response.token());
        verify(tokenService).issueToken(anyString(), anyString(), anyString(), any(Map.class));
        verifyNoInteractions(roomRegistry);
    }
}
