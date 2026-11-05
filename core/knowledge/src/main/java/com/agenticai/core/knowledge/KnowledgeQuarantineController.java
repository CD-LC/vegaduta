package com.agenticai.core.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review of quarantined knowledge-base writes (see {@link KnowledgeQuarantineService}). The
 * permission model is the one every knowledge write already has: reads are open to members, and the
 * POSTs below fall under the {@code /api/knowledge/**} writes that need MANAGE_AGENTS (see {@code
 * Permission}).
 */
@RestController
@RequestMapping("/api/knowledge/quarantine")
@Profile("knowledge")
public class KnowledgeQuarantineController {

    private static final int MAX_LIMIT = 200;

    private final KnowledgeQuarantineService service;

    public KnowledgeQuarantineController(KnowledgeQuarantineService service) {
        this.service = service;
    }

    @GetMapping
    public List<KnowledgeQuarantineService.QuarantinedChunk> list(
            @RequestParam(required = false) UUID collectionId,
            @RequestParam(defaultValue = "50") int limit) {
        return service.list(collectionId, Math.min(limit, MAX_LIMIT));
    }

    /** Makes the chunk retrievable; 404 when nothing was quarantined under that id. */
    @PostMapping("/{chunkId}/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID chunkId) {
        return service.approve(chunkId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Deletes the quarantined chunk; 404 when nothing was quarantined under that id. */
    @PostMapping("/{chunkId}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID chunkId) {
        return service.reject(chunkId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Approves every quarantined chunk of one saved note (a note can span several chunks). */
    @PostMapping("/notes/approve")
    public ResponseEntity<Void> approveNote(
            @RequestParam UUID collectionId, @RequestParam String source) {
        return service.approveNote(collectionId, source) > 0
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/notes/reject")
    public ResponseEntity<Void> rejectNote(
            @RequestParam UUID collectionId, @RequestParam String source) {
        return service.rejectNote(collectionId, source) > 0
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
