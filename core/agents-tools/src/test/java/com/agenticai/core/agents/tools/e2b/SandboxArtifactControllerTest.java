package com.agenticai.core.agents.tools.e2b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SandboxArtifactControllerTest {

    private final FileStorageService storage = mock(FileStorageService.class);
    private final AgentService agents = mock(AgentService.class);
    private final SandboxArtifactController controller =
            new SandboxArtifactController(storage, agents);
    private final UUID id = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    private void stored(String metaJson, byte[] data) {
        when(storage.exists(SandboxArtifacts.metaKey(id))).thenReturn(true);
        when(storage.get(SandboxArtifacts.metaKey(id)))
                .thenReturn(new ByteArrayInputStream(metaJson.getBytes()));
        when(storage.get(SandboxArtifacts.dataKey(id, "png")))
                .thenReturn(new ByteArrayInputStream(data));
    }

    @Test
    void servesAsAttachmentWithHardenedHeadersAfterOneMetaLookup() {
        stored(
                "{\"name\":\"chart.png\",\"ext\":\"png\",\"agentId\":\"" + agent + "\"}",
                new byte[] {1, 2, 3});
        ResponseEntity<byte[]> res = controller.download(id);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertArrayEquals(new byte[] {1, 2, 3}, res.getBody());
        assertTrue(res.getHeaders().getFirst("Content-Disposition").startsWith("attachment"));
        assertEquals("nosniff", res.getHeaders().getFirst("X-Content-Type-Options"));
        verify(agents).get(agent);
        // exactly one exists() probe (the sidecar), not one per allow-listed extension
        verify(storage, org.mockito.Mockito.times(1)).exists(any());
    }

    @Test
    void callerWhoCannotViewTheProducingAgentGetsA404() {
        stored(
                "{\"name\":\"chart.png\",\"ext\":\"png\",\"agentId\":\"" + agent + "\"}",
                new byte[] {1});
        when(agents.get(agent)).thenThrow(new RuntimeException("no access"));
        assertEquals(HttpStatus.NOT_FOUND, controller.download(id).getStatusCode());
        verify(storage, never()).get(SandboxArtifacts.dataKey(id, "png"));
    }

    @Test
    void missingSidecarOrMissingAgentIdOrUnknownExtensionIs404() {
        assertEquals(HttpStatus.NOT_FOUND, controller.download(id).getStatusCode());
        stored("{\"name\":\"x.png\",\"ext\":\"png\"}", new byte[] {1});
        assertEquals(HttpStatus.NOT_FOUND, controller.download(id).getStatusCode());
        stored(
                "{\"name\":\"x.exe\",\"ext\":\"exe\",\"agentId\":\"" + agent + "\"}",
                new byte[] {1});
        assertEquals(HttpStatus.NOT_FOUND, controller.download(id).getStatusCode());
    }
}
