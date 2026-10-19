package com.agenticai.core.agents.chatport;

import com.agenticai.core.agents.chatport.ChatExportService.ExportFile;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/agents/{agentId}/sessions/{sessionId}/export?format=md|json|txt} - download a chat session as a
 * file. Thin on purpose: the flag ({@code app.chat-export.enabled}, off = 404), the agent access check and the
 * session lookup all live in {@link ChatExportService}.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/export")
public class ChatExportController {

    private final ChatExportService exportService;

    public ChatExportController(ChatExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping
    public ResponseEntity<byte[]> export(
        @PathVariable UUID agentId,
        @PathVariable String sessionId,
        @RequestParam(value = "format", required = false) String format
    ) {
        return download(exportService.exportSession(agentId, sessionId, format));
    }

    /** An attachment download that is never cached (it is somebody's conversation) and never content-sniffed. */
    static ResponseEntity<byte[]> download(ExportFile file) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_TYPE, file.contentType())
            .body(file.bytes());
    }
}
