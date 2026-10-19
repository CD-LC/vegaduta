package com.agenticai.core.agents.memory.importer;

import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportRequest;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportResponse;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.PreviewRequest;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.PreviewResponse;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Import memories from another assistant - see {@link MemoryImportService}. Kept in its own controller and
 * base path (next to {@code UserMemoryController}'s {@code /api/memory}) so the two change independently.
 * Everything is scoped to the caller's own principal name; the flag
 * {@code app.memory.import.enabled} off = every route here answers 404 (the two POSTs, plus the read-only
 * {@code GET /limits} the page uses to decide whether to show the card at all).
 */
@RestController
@RequestMapping("/api/memory/import")
public class MemoryImportController {

    private final MemoryImportService importService;

    public MemoryImportController(MemoryImportService importService) {
        this.importService = importService;
    }

    /** Feature probe + limits for the page; 404 when {@code app.memory.import.enabled} is off. */
    @GetMapping("/limits")
    public MemoryImportDtos.ImportLimits limits(Principal principal) {
        subject(principal);
        return importService.limits();
    }

    /** Parses and screens a paste. No side effects: nothing is saved. */
    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody PreviewRequest body, Principal principal) {
        return importService.preview(subject(principal), body);
    }

    /** Saves the selected facts through the same path as {@code POST /api/memory/facts}. */
    @PostMapping
    public ResponseEntity<ImportResponse> importFacts(@RequestBody ImportRequest body, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(importService.importFacts(subject(principal), body));
    }

    private static String subject(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to manage your memory.");
        }
        return principal.getName();
    }
}
