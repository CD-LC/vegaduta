package com.agenticai.core.agents.memory.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.importer.MemoryImportDtos.Capacity;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportFact;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportRequest;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportResponse;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.PreviewRequest;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.PreviewResponse;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.PreviewStats;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class MemoryImportControllerTest {

    private final MemoryImportService service = mock(MemoryImportService.class);
    private final MemoryImportController controller = new MemoryImportController(service);
    private final Principal alice = () -> "alice";

    @Test
    void previewDelegatesWithThePrincipalName() {
        PreviewRequest body = new PreviewRequest("- Likes tea", "chatgpt");
        PreviewResponse expected = new PreviewResponse(List.of(), new PreviewStats(1, 1, 0, 0), new Capacity(50, 25), "chatgpt");
        when(service.preview("alice", body)).thenReturn(expected);
        assertSame(expected, controller.preview(body, alice));
    }

    @Test
    void importAnswers201() {
        ImportRequest body = new ImportRequest(List.of(new ImportFact("Likes tea", "shared", null)), "chatgpt");
        ImportResponse expected = new ImportResponse(1, List.of(), "chatgpt");
        when(service.importFacts("alice", body)).thenReturn(expected);
        ResponseEntity<ImportResponse> r = controller.importFacts(body, alice);
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertSame(expected, r.getBody());
    }

    @Test
    void limitsDelegateAndNeedASignedInCaller() {
        MemoryImportDtos.ImportLimits expected = new MemoryImportDtos.ImportLimits(200, 20000);
        when(service.limits()).thenReturn(expected);
        assertSame(expected, controller.limits(alice));
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class, () -> controller.limits(null)).getStatusCode());
    }

    @Test
    void anonymousCallersAreRejectedBeforeTheService() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class, () -> controller.preview(new PreviewRequest("x", null), null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class, () -> controller.importFacts(new ImportRequest(List.of(), null), () -> " ")).getStatusCode());
        verifyNoInteractions(service);
    }
}
