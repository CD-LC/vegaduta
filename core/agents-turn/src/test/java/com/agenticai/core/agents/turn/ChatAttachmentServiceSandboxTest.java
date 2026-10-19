package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.modality.ChatAttachmentCategory;
import com.agenticai.core.agents.modality.ModelModalities;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.modality.UnsupportedAttachmentTypeException;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.storage.FileStorageService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.MimeType;

class ChatAttachmentServiceSandboxTest {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final UUID agentId = UUID.randomUUID();
    private final AgentService agentService = mock(AgentService.class);
    private final ModelModalitiesService modalities = mock(ModelModalitiesService.class);
    private final Agent agent = mock(Agent.class);
    private final ChatAttachmentService service =
            new ChatAttachmentService(
                    agentService,
                    mock(ProviderModelCatalogService.class),
                    modalities,
                    mock(FileStorageService.class));

    @SuppressWarnings("unchecked")
    private void registry(boolean enabled) {
        ObjectProvider<SandboxFileRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable())
                .thenReturn(new SandboxFileRegistry(enabled, 6, 1000, 2000, 60));
        service.setSandboxFileRegistry(provider);
    }

    private void agent(boolean e2b, boolean modelTakesDocuments) {
        when(agentService.requireAccess(agentId, PermissionLevel.RUNNER)).thenReturn(agent);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("m");
        when(agent.isE2bEnabled()).thenReturn(e2b);
        when(modalities.resolve(any(), any()))
                .thenReturn(new ModelModalities(false, false, false, modelTakesDocuments));
    }

    private MockMultipartFile file(String type, String name) {
        return new MockMultipartFile("file", name, type, new byte[] {1, 2, 3});
    }

    @Test
    void xlsxIsAcceptedOnlyWithCodeExecutionAndTheSandboxFlagOn() {
        agent(true, false);
        registry(true);
        assertEquals(
                ChatAttachmentCategory.SPREADSHEET,
                service.upload(agentId, file(XLSX, "q1.xlsx")).category());

        registry(false);
        assertThrows(
                UnsupportedAttachmentTypeException.class,
                () -> service.upload(agentId, file(XLSX, "q1.xlsx")));

        agent(false, false);
        registry(true);
        assertThrows(
                UnsupportedAttachmentTypeException.class,
                () -> service.upload(agentId, file(XLSX, "q1.xlsx")));
    }

    @Test
    void csvIsAcceptedForTheSandboxEvenWhenTheModelHasNoDocumentInput() {
        agent(true, false);
        registry(true);
        assertEquals(
                ChatAttachmentCategory.DOCUMENT,
                service.upload(agentId, file("text/csv", "d.csv")).category());
    }

    @Test
    void csvIsStillRejectedWithoutTheSandboxWhenTheModelHasNoDocumentInput() {
        agent(true, false);
        registry(false);
        assertThrows(
                UnsupportedAttachmentTypeException.class,
                () -> service.upload(agentId, file("text/csv", "d.csv")));
    }

    @Test
    void dataFilesAreWithheldFromADocumentlessModelButKeptOtherwise() {
        ChatAttachmentPayload csv =
                new ChatAttachmentPayload(
                        MimeType.valueOf("text/csv"),
                        new ByteArrayResource(new byte[] {1}),
                        "d.csv");
        ChatAttachmentPayload png =
                new ChatAttachmentPayload(
                        MimeType.valueOf("image/png"),
                        new ByteArrayResource(new byte[] {1}),
                        "p.png");
        assertEquals(List.of(png), ChatAttachments.withoutSandboxOnly(List.of(csv, png), true));
        assertEquals(
                List.of(csv, png), ChatAttachments.withoutSandboxOnly(List.of(csv, png), false));
        assertTrue(ChatAttachmentCategory.isDocument("application/pdf; x=y"));
        assertFalse(ChatAttachmentCategory.isDocument("image/png"));
    }
}
