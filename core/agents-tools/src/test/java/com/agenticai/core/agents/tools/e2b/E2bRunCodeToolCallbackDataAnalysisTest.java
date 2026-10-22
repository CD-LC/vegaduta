package com.agenticai.core.agents.tools.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.sdlc.SdlcSandboxService;
import com.agenticai.core.sdlc.dto.RunCodeRequest;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.agenticai.core.sdlc.dto.SandboxArtifact;
import com.agenticai.core.storage.FileStorageService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class E2bRunCodeToolCallbackDataAnalysisTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String INPUT = "{\"code\":\"print(1)\",\"language\":\"python\"}";

    private final UUID agent = UUID.randomUUID();
    private final SdlcSandboxService sandbox = mock(SdlcSandboxService.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final SandboxFileRegistry registry =
            new SandboxFileRegistry(
                    true, 6, 1_000_000, 2_000_000, 60_000, System::currentTimeMillis);

    @Test
    void mountsSessionFilesAndStoresReturnedArtifacts() throws Exception {
        registry.register(agent, "s1", "sales.csv", "text/csv", "a,b\n1,2\n".getBytes());
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
                                                "chart.png", "image/png", new byte[] {1, 2, 3}),
                                        new SandboxArtifact(
                                                "evil.exe",
                                                "application/octet-stream",
                                                new byte[] {9}),
                                        new SandboxArtifact(
                                                "result.xlsx",
                                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                                new byte[] {4}))));

        E2bRunCodeToolCallback callback =
                new E2bRunCodeToolCallback(sandbox, registry, storage, agent, "s1");
        JsonNode out = MAPPER.readTree(callback.call(INPUT));

        ArgumentCaptor<RunCodeRequest> req = ArgumentCaptor.forClass(RunCodeRequest.class);
        verify(sandbox).runCode(req.capture());
        assertEquals(1, req.getValue().files().size());
        assertEquals("sales.csv", req.getValue().files().get(0).name());

        assertEquals("sales.csv", out.path("mountedFiles").get(0).asString());
        JsonNode files = out.path("files");
        assertEquals(2, files.size(), "the .exe is not allow-listed and must be dropped");
        assertEquals("chart.png", files.get(0).path("filename").asString());
        assertTrue(files.get(0).path("url").asString().startsWith(SandboxArtifacts.URL_PREFIX));
        assertTrue(out.path("instructions").asString().contains("url"));
        // bytes + name sidecar per stored artifact
        verify(storage, org.mockito.Mockito.times(4)).put(any(), any(), any());
        verify(storage).put(any(), eq(new byte[] {1, 2, 3}), eq("image/png"));
    }

    @Test
    void withoutRegistryBehavesLikeClassicRunCode() throws Exception {
        when(sandbox.runCode(any())).thenReturn(new RunCodeResponse("hi", "", 0, false, "sbx"));
        E2bRunCodeToolCallback callback = new E2bRunCodeToolCallback(sandbox);
        JsonNode out = MAPPER.readTree(callback.call(INPUT));
        assertEquals("hi", out.path("stdout").asString());
        assertFalse(out.has("files"));
        assertFalse(out.has("mountedFiles"));
        assertFalse(callback.getToolDefinition().description().contains("DATA ANALYSIS"));
    }

    @Test
    void disabledRegistryKeepsToolDescriptionAndRequestClean() throws Exception {
        SandboxFileRegistry off =
                new SandboxFileRegistry(false, 6, 1_000, 1_000, 60_000, System::currentTimeMillis);
        when(sandbox.runCode(any())).thenReturn(new RunCodeResponse("hi", "", 0, false, "sbx"));
        E2bRunCodeToolCallback callback =
                new E2bRunCodeToolCallback(sandbox, off, storage, agent, "s1");
        assertFalse(callback.getToolDefinition().description().contains("DATA ANALYSIS"));
        callback.call(INPUT);
        ArgumentCaptor<RunCodeRequest> req = ArgumentCaptor.forClass(RunCodeRequest.class);
        verify(sandbox).runCode(req.capture());
        assertNull(req.getValue().files());
        verify(storage, never()).put(any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private SandboxFileRegistry registryWithTenant(boolean active, boolean template) {
        com.agenticai.core.sdlc.E2bCredentialService creds =
                mock(com.agenticai.core.sdlc.E2bCredentialService.class);
        when(creds.isActive()).thenReturn(active);
        when(creds.hasCodeExecutionTemplate()).thenReturn(template);
        org.springframework.beans.factory.ObjectProvider<
                        com.agenticai.core.sdlc.E2bCredentialService>
                p = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(creds);
        SandboxFileRegistry r =
                new SandboxFileRegistry(
                        true, 6, 1_000_000, 2_000_000, 60_000, System::currentTimeMillis);
        r.setCredentialService(p);
        return r;
    }

    @Test
    void tenantWithoutE2bKeyOrTemplateGetsNoDataAnalysisNoteAndNoMounting() throws Exception {
        for (SandboxFileRegistry r :
                List.of(registryWithTenant(false, true), registryWithTenant(true, false))) {
            when(sandbox.runCode(any())).thenReturn(new RunCodeResponse("hi", "", 0, false, "sbx"));
            E2bRunCodeToolCallback callback =
                    new E2bRunCodeToolCallback(sandbox, r, storage, agent, "s1");
            assertFalse(callback.getToolDefinition().description().contains("DATA ANALYSIS"));
            assertFalse(MAPPER.readTree(callback.call(INPUT)).has("mountedFiles"));
        }
        E2bRunCodeToolCallback ok =
                new E2bRunCodeToolCallback(
                        sandbox, registryWithTenant(true, true), storage, agent, "s1");
        assertTrue(ok.getToolDefinition().description().contains("DATA ANALYSIS"));
    }

    @Test
    void descriptionIsStaticSoItIsTheSameBeforeAndAfterTheUploadIsRegistered() {
        // Tools resolve BEFORE the turn's uploads are registered, so the description must not
        // depend on registry contents (no race) - names come back in each result's mountedFiles.
        E2bRunCodeToolCallback callback =
                new E2bRunCodeToolCallback(sandbox, registry, storage, agent, "s1");
        String before = callback.getToolDefinition().description();
        registry.register(agent, "s1", "sales.csv", "text/csv", new byte[] {1});
        String after = callback.getToolDefinition().description();
        assertEquals(before, after);
        assertTrue(after.contains("DATA ANALYSIS"));
        assertTrue(after.contains("mountedFiles"));
        assertFalse(after.contains("sales.csv"));
        // D4: the note names the libraries the template ships and the headless chart recipe.
        for (String lib : List.of("pandas", "matplotlib", "openpyxl", "xlsxwriter", "pypdf")) {
            assertTrue(after.contains(lib), lib);
        }
        assertTrue(after.contains("Agg"));
        assertTrue(after.contains("plt.savefig"));
    }

    @Test
    void artifactsAreTrackedForRetentionAndGetOneMetaSidecar() throws Exception {
        SandboxArtifactRetention retention =
                new SandboxArtifactRetention(storage, 1_000, System::currentTimeMillis);
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
                                                "chart.png", "image/png", new byte[] {1}))));
        E2bRunCodeToolCallback callback =
                new E2bRunCodeToolCallback(sandbox, registry, storage, retention, agent, "s1");
        JsonNode out = MAPPER.readTree(callback.call(INPUT));
        assertEquals(1, out.path("files").size());
        assertEquals(1, retention.pendingCount());
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(storage, org.mockito.Mockito.times(2)).put(keys.capture(), any(), any());
        assertTrue(keys.getAllValues().get(0).endsWith(".png"));
        assertTrue(keys.getAllValues().get(1).endsWith(".meta"));
    }
}
