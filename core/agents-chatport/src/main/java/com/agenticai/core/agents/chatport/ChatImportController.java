package com.agenticai.core.agents.chatport;

import com.agenticai.core.agents.chatport.ChatPortDtos.Features;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportResponse;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedChatView;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedSummary;
import com.agenticai.core.agents.chatport.ChatPortDtos.JsonUpload;
import com.agenticai.core.agents.chatport.ChatPortDtos.MemoryCandidatesRequest;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewResponse;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chat portability: import conversations from ChatGPT / Claude exports and take imported chats back out - see
 * {@link ChatImportService} / {@link ChatExportService}. Everything is scoped to the caller's own principal name.
 * {@code app.chat-import.enabled} off = every route here except {@code GET /features} answers 404 (the uploads are
 * refused before the file is read).
 */
@RestController
@RequestMapping("/api/chat-import")
public class ChatImportController {

    private final ChatImportService importService;
    private final ChatExportService exportService;

    public ChatImportController(ChatImportService importService, ChatExportService exportService) {
        this.importService = importService;
        this.exportService = exportService;
    }

    /** Which halves are switched on, plus the limits the card shows. The only route that does not 404 when off. */
    @GetMapping("/features")
    public Features features(Principal principal) {
        subject(principal);
        return importService.features();
    }

    /** Lists what the uploaded file holds. Nothing is stored. */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewResponse previewUpload(@RequestParam("file") MultipartFile file, Principal principal) {
        String subject = subject(principal);
        importService.requireEnabled();
        return importService.preview(subject, bytes(file));
    }

    /**
     * The JSON-body variant (text of conversations.json, no zip) for API callers; the web uses multipart. Jackson's own
     * string limit (about 20M chars by default) can reject a very large body with a plain 400 before the 413 size
     * check below runs; multipart is the route for files near {@code app.chat-import.max-bytes}.
     */
    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PreviewResponse previewJson(@RequestBody JsonUpload body, Principal principal) {
        String subject = subject(principal);
        importService.requireEnabled();
        return importService.preview(subject, bytes(body));
    }

    /** Stores the chosen conversations ({@code ids} are the keys the preview returned; comma-separated or repeated). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResponse> importUpload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "ids", required = false) List<String> ids,
        Principal principal
    ) {
        String subject = subject(principal);
        importService.requireEnabled();
        return ResponseEntity.status(HttpStatus.CREATED).body(importService.importChats(subject, bytes(file), ids));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportResponse> importJson(@RequestBody JsonUpload body, Principal principal) {
        String subject = subject(principal);
        importService.requireEnabled();
        return ResponseEntity.status(HttpStatus.CREATED).body(importService.importChats(subject, bytes(body), body.ids()));
    }

    @GetMapping("/chats")
    public List<ImportedSummary> list(Principal principal) {
        return importService.list(subject(principal));
    }

    @GetMapping("/chats/{id}")
    public ImportedChatView get(@PathVariable UUID id, Principal principal) {
        return importService.get(subject(principal), id);
    }

    @DeleteMapping("/chats/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Principal principal) {
        importService.delete(subject(principal), id);
        return ResponseEntity.noContent().build();
    }

    /** One imported chat as md | json | txt. */
    @GetMapping("/chats/{id}/export")
    public ResponseEntity<byte[]> exportOne(
        @PathVariable UUID id,
        @RequestParam(value = "format", required = false) String format,
        Principal principal
    ) {
        return ChatExportController.download(exportService.exportImportedChat(subject(principal), id, format));
    }

    /** Every imported chat of the caller in one file (md | json | txt). */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAll(@RequestParam(value = "format", required = false) String format, Principal principal) {
        return ChatExportController.download(exportService.exportAllImported(subject(principal), format));
    }

    /**
     * Opt-in "extract memories": proposes first-person statements from the chosen chats as a memory-import preview.
     * Saves nothing; the web then saves what the user keeps through {@code POST /api/memory/import}.
     */
    @PostMapping("/memory-candidates")
    public MemoryImportDtos.PreviewResponse memoryCandidates(@RequestBody MemoryCandidatesRequest body, Principal principal) {
        return importService.memoryCandidates(subject(principal), body == null ? null : body.chatIds());
    }

    private byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a conversations file first.");
        }
        if (file.getSize() > importService.maxBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "That file is too large to import.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That file could not be read.");
        }
    }

    private byte[] bytes(JsonUpload body) {
        if (body == null || !StringUtils.hasText(body.text())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a conversations file first.");
        }
        // UTF-8 length can exceed the char count; the service re-checks the exact byte size.
        if (body.text().length() > importService.maxBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "That file is too large to import.");
        }
        return body.text().getBytes(StandardCharsets.UTF_8);
    }

    private static String subject(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to import or export chats.");
        }
        return principal.getName();
    }
}
