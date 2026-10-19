package com.agenticai.core.workflow.portability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.workflow.portability.WorkflowPortabilityService.ExportedFile;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Prod signed-in pass 2026-10-05, bug 3: {@code GET /api/workflows/{id}/export} answered {@code
 * Content-Disposition: attachment; filename="=?UTF-8?Q?e2e-workflow.vegaduta-workflow.json?=";
 * filename*=...} and the browser saved the file under the encoded-word.
 */
class WorkflowExportControllerTest {

    private static ResponseEntity<byte[]> exportNamed(String fileName) {
        WorkflowPortabilityService service = mock(WorkflowPortabilityService.class);
        UUID id = UUID.randomUUID();
        when(service.export(id)).thenReturn(new ExportedFile(fileName, "{}"));
        return new WorkflowExportController(service).export(id);
    }

    @Test
    void exportNamesTheFilePlainlyWithNoMimeEncodedWord() {
        ResponseEntity<byte[]> response = exportNamed("e2e-workflow.vegaduta-workflow.json");

        assertEquals(
                "attachment; filename=\"e2e-workflow.vegaduta-workflow.json\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void aNonAsciiWorkflowNameRoundTripsThroughTheRfc5987Parameter() {
        ResponseEntity<byte[]> response = exportNamed("Bestellübersicht.vegaduta-workflow.json");

        String header = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertEquals(
                "attachment; filename=\"Bestell_bersicht.vegaduta-workflow.json\"; "
                        + "filename*=UTF-8''Bestell%C3%BCbersicht.vegaduta-workflow.json",
                header);
        assertEquals(
                "Bestellübersicht.vegaduta-workflow.json",
                ContentDisposition.parse(header).getFilename());
    }
}
