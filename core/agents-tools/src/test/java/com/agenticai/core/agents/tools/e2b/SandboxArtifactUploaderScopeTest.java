package com.agenticai.core.agents.tools.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sdlc.SdlcSandboxService;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.agenticai.core.sdlc.dto.SandboxArtifact;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SandboxArtifactUploaderScopeTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final FileStorageService storage = mock(FileStorageService.class);
    private final AgentService agents = mock(AgentService.class);
    private final SandboxArtifactController controller =
            new SandboxArtifactController(storage, agents);
    private final UUID id = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void stored(String metaJson) {
        when(storage.exists(SandboxArtifacts.metaKey(id))).thenReturn(true);
        when(storage.get(SandboxArtifacts.metaKey(id)))
                .thenReturn(new ByteArrayInputStream(metaJson.getBytes()));
        when(storage.get(SandboxArtifacts.dataKey(id, "png")))
                .thenReturn(new ByteArrayInputStream(new byte[] {1}));
    }

    private static void caller(String subject) {
        TenantContext.set(TenantContext.of("t1", subject, Set.of()));
    }

    @Test
    void onlyTheUploaderMayDownloadWhenTheArtifactRecordsAUser() {
        stored(
                "{\"name\":\"c.png\",\"ext\":\"png\",\"agentId\":\""
                        + agent
                        + "\",\"userId\":\"alice\"}");
        caller("alice");
        assertEquals(HttpStatus.OK, controller.download(id).getStatusCode());
    }

    @Test
    void anotherUserWithAccessToTheSameAgentGetsA404() {
        stored(
                "{\"name\":\"c.png\",\"ext\":\"png\",\"agentId\":\""
                        + agent
                        + "\",\"userId\":\"alice\"}");
        caller("bob");
        assertEquals(HttpStatus.NOT_FOUND, controller.download(id).getStatusCode());
    }

    @Test
    void noIdentifiableCallerFailsClosedWhenAUserWasRecorded() {
        stored(
                "{\"name\":\"c.png\",\"ext\":\"png\",\"agentId\":\""
                        + agent
                        + "\",\"userId\":\"alice\"}");
        assertEquals(HttpStatus.NOT_FOUND, controller.download(id).getStatusCode());
    }

    @Test
    void anArtifactWithoutARecordedUserFallsBackToAgentVisibility() {
        stored("{\"name\":\"c.png\",\"ext\":\"png\",\"agentId\":\"" + agent + "\"}");
        caller("bob");
        assertEquals(HttpStatus.OK, controller.download(id).getStatusCode());
        verify(agents).get(agent);
    }

    @Test
    void theToolRecordsTheUploaderAndSurfacesMountWarnings() throws Exception {
        UUID agentId = UUID.randomUUID();
        SdlcSandboxService sandbox = mock(SdlcSandboxService.class);
        SandboxFileRegistry registry =
                new SandboxFileRegistry(true, 6, 10, 1000, 60_000, System::currentTimeMillis);
        when(sandbox.runCode(any()))
                .thenReturn(
                        new RunCodeResponse(
                                "ok",
                                "",
                                0,
                                false,
                                "sbx",
                                List.of(
                                        new SandboxArtifact(
                                                "chart.png", "image/png", new byte[] {1, 2}))));
        registry.tryRegister(
                agentId, "s1", "huge.csv", "text/csv", new byte[100]); // over the 10 byte cap

        caller("alice");
        E2bRunCodeToolCallback callback =
                new E2bRunCodeToolCallback(sandbox, registry, storage, agentId, "s1");
        JsonNode out =
                MAPPER.readTree(callback.call("{\"code\":\"print(1)\",\"language\":\"python\"}"));

        assertTrue(out.path("warnings").get(0).asString().contains("huge.csv"));
        ArgumentCaptor<byte[]> meta = ArgumentCaptor.forClass(byte[].class);
        verify(storage)
                .put(
                        eq(
                                SandboxArtifacts.metaKey(
                                        UUID.fromString(
                                                out.path("files")
                                                        .get(0)
                                                        .path("url")
                                                        .asString()
                                                        .substring(
                                                                SandboxArtifacts.URL_PREFIX
                                                                        .length())))),
                        meta.capture(),
                        eq("application/json"));
        assertEquals("alice", MAPPER.readTree(meta.getValue()).path("userId").asString());

        JsonNode second =
                MAPPER.readTree(callback.call("{\"code\":\"print(1)\",\"language\":\"python\"}"));
        assertTrue(!second.has("warnings"), "a warning is reported once");
    }
}
