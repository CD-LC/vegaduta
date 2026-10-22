package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.chatport.ChatExportService.ExportFile;
import com.agenticai.core.agents.chatport.ChatPortDtos.Capacity;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportResponse;
import com.agenticai.core.agents.chatport.ChatPortDtos.JsonUpload;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewResponse;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewStats;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

@SuppressWarnings("unchecked")
class ChatPortControllersTest {

    private final Principal alice = () -> "alice";
    private final ChatImportService importService = mock(ChatImportService.class);
    private final ChatExportService exportService = mock(ChatExportService.class);
    private final ChatImportController controller = new ChatImportController(importService, exportService);

    private static MockMultipartFile upload(String content) {
        return new MockMultipartFile("file", "conversations.json", "application/json", bytes(content));
    }

    @Test
    void anonymousCallersAreRejectedBeforeAnyService() {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> controller.features(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class, () -> controller.previewUpload(upload("[]"), () -> " ")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> controller.list(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class, () -> controller.exportAll("md", null)).getStatusCode());
        verifyNoInteractions(importService, exportService);
    }

    @Test
    void previewAndImportDelegateTheUploadedBytesAndTheCallersName() {
        when(importService.maxBytes()).thenReturn(1_000_000L);
        PreviewResponse preview = new PreviewResponse("chatgpt", List.of(), new PreviewStats(0, 0, 0, false), new Capacity(0, 10, 5), List.of());
        when(importService.preview(eq("alice"), any(byte[].class))).thenReturn(preview);
        assertSame(preview, controller.previewUpload(upload("[1]"), alice));

        ImportResponse imported = new ImportResponse(1, List.of(), List.of(), List.of());
        when(importService.importChats(eq("alice"), any(byte[].class), any())).thenReturn(imported);
        ResponseEntity<ImportResponse> r = controller.importUpload(upload("[2]"), List.of("a", "b"), alice);
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertSame(imported, r.getBody());

        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(importService).importChats(eq("alice"), sent.capture(), eq(List.of("a", "b")));
        assertArrayEquals(bytes("[2]"), sent.getValue());
        verify(importService, org.mockito.Mockito.times(2)).requireEnabled();
    }

    @Test
    void theJsonBodyRouteDelegatesToo() {
        when(importService.maxBytes()).thenReturn(1_000_000L);
        ImportResponse imported = new ImportResponse(0, List.of(), List.of(), List.of());
        when(importService.importChats(eq("alice"), any(byte[].class), any())).thenReturn(imported);
        assertSame(imported, controller.importJson(new JsonUpload("[3]", List.of("x")), alice).getBody());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> controller.importJson(new JsonUpload(" ", List.of()), alice)).getStatusCode());
    }

    @Test
    void anEmptyOrOversizedUploadIsRefusedBeforeTheService() {
        when(importService.maxBytes()).thenReturn(10L);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> controller.previewUpload(upload(""), alice)).getStatusCode());
        assertEquals(HttpStatus.CONTENT_TOO_LARGE,
            assertThrows(ResponseStatusException.class, () -> controller.previewUpload(upload("[0123456789012345]"), alice)).getStatusCode());
    }

    @Test
    void withTheFlagOffAnUploadIsRefusedBeforeItsBytesAreRead() {
        ImportedChatRepository repository = mock(ImportedChatRepository.class);
        ObjectProvider<com.agenticai.core.agents.memory.importer.MemoryImportService> memory = mock(ObjectProvider.class);
        ObjectProvider<com.agenticai.core.audit.AuditLogService> audit = mock(ObjectProvider.class);
        ChatImportService off = new ChatImportService(repository, memory, audit, false, false, 1_000_000L, 500, 5000, 2000,
            50_000, 500_000, 20_000_000L, 2000, 20_000, 10_000, 209_715_200L, 100, 1_000_000, 16_000_000L,
            262_144_000L, 200, 26_214_400L);
        ChatImportController offController = new ChatImportController(off, exportService);

        MockMultipartFile file = mock(MockMultipartFile.class);
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> offController.previewUpload(file, alice)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> offController.importUpload(file, List.of("x"), alice)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> offController.importJson(new JsonUpload("[]", List.of("x")), alice)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> offController.list(alice)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> offController.get(UUID.randomUUID(), alice)).getStatusCode());
        verifyNoInteractions(file, repository);
        // the probe is the one route that answers while off
        assertTrue(!offController.features(alice).importEnabled());
    }

    @Test
    void downloadsCarryAnAttachmentDispositionNoStoreAndNoSniff() {
        ExportFile file = new ExportFile(bytes("# Hi"), "text/markdown; charset=utf-8", "chat-20261004.md");
        ResponseEntity<byte[]> r = ChatExportController.download(file);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("attachment; filename=\"chat-20261004.md\"", r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("nosniff", r.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("text/markdown; charset=utf-8", r.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertTrue(r.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL).contains("no-store"));
        assertArrayEquals(bytes("# Hi"), r.getBody());
    }

    @Test
    void theSessionExportControllerPassesPathAndFormatThrough() {
        ChatExportService svc = mock(ChatExportService.class);
        UUID agentId = UUID.randomUUID();
        when(svc.exportSession(agentId, "s1", "json")).thenReturn(new ExportFile(bytes("{}"), "application/json", "x.json"));
        ResponseEntity<byte[]> r = new ChatExportController(svc).export(agentId, "s1", "json");
        assertEquals("application/json", r.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        verify(svc).exportSession(agentId, "s1", "json");
    }

    @Test
    void exportRoutesOnTheImportControllerDelegate() {
        UUID id = UUID.randomUUID();
        when(exportService.exportImportedChat("alice", id, "txt")).thenReturn(new ExportFile(bytes("t"), "text/plain; charset=utf-8", "t.txt"));
        when(exportService.exportAllImported("alice", null)).thenReturn(new ExportFile(bytes("all"), "text/markdown; charset=utf-8", "a.md"));
        assertEquals("t.txt", controller.exportOne(id, "txt", alice).getHeaders().getContentDisposition().getFilename());
        assertArrayEquals(bytes("all"), controller.exportAll(null, alice).getBody());
        verify(exportService).exportAllImported("alice", null);
    }
}
