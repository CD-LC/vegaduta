package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface of the unified-memory store (ADR-0028 WP6): the review queue and privacy operations
 * over {@code memory_item}. No LLM call happens here; reads are projections of the store, decisions
 * go through {@link MemoryItemReviewService} (the only path to TRUSTED + ACTIVE) and erasure
 * through its lineage cascade.
 *
 * <p>Authorization mirrors {@code AgentMemoryController}, through the {@link MemoryAgentAccess}
 * port (the adapter lives in agents-turn; this package must not reach {@code agents.core}, see the
 * port's javadoc): listing and reading an agent's items takes VIEWER on the agent, but a VIEWER
 * only ever sees ACTIVE items (which are TRUSTED by the {@code memory_item_active_trusted_chk}
 * constraint) - quarantined, pending or otherwise untrusted rows are the review queue and take
 * EDITOR. Approve, reject, revert and erase all take EDITOR and record the authenticated principal
 * as the human actor in {@code memory_event}.
 *
 * <p>Flag gating: reads answer 404 while {@link MemoryUnifiedGate#enabled} is off for the agent
 * (the same platform AND tenant AND agent flags as the write path, all default OFF), so the surface
 * stays dark with the feature. Decisions and erasure are NOT gated: a person must always be able to
 * review and erase, even with the platform switch off (ADR-0028 rollout: data is retained but
 * erasable), matching {@link MemoryItemReviewService}, which never consults the flags. Edit and pin
 * create or change recall-relevant state, so they ARE gated: 404 while the feature is dark.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/memory-items")
public class MemoryItemController {

    private static final int MAX_LIMIT = 500;

    private final ObjectProvider<MemoryAgentAccess> access;
    private final MemoryUnifiedGate gate;
    private final MemoryItemReviewService review;

    public MemoryItemController(
            ObjectProvider<MemoryAgentAccess> access,
            MemoryUnifiedGate gate,
            MemoryItemReviewService review) {
        this.access = access;
        this.gate = gate;
        this.review = review;
    }

    /** One {@code memory_item} row as the API shows it (no embedding). */
    public record ItemView(
            UUID id,
            UUID agentId,
            String kind,
            String content,
            String trust,
            String status,
            String writer,
            String sourceType,
            String sourceRef,
            List<String> sourceTaint,
            boolean modelGenerated,
            int generation,
            List<String> subjectKeys,
            int version,
            UUID supersedesId,
            Instant createdAt,
            Instant expiresAt,
            String approvedBy,
            Instant approvedAt,
            boolean pinned) {

        static ItemView of(MemoryItem i) {
            return new ItemView(
                    i.id(),
                    i.agentId(),
                    i.kind().name(),
                    i.content(),
                    i.trust().name(),
                    i.status().name(),
                    i.writer().name(),
                    i.sourceType(),
                    i.sourceRef(),
                    i.sourceTaint(),
                    i.modelGenerated(),
                    i.generation(),
                    i.subjectKeys(),
                    i.version(),
                    i.supersedesId(),
                    i.createdAt(),
                    i.expiresAt(),
                    i.approvedBy(),
                    i.approvedAt(),
                    i.pinned());
        }
    }

    /** Body of the edit: the new text of the item. */
    public record EditRequest(String content) {}

    public record SourceView(String sourceTable, String sourceKey, String sourceTrust) {

        static SourceView of(MemoryItemSource s) {
            return new SourceView(s.sourceTable(), s.sourceKey(), s.sourceTrust().name());
        }
    }

    public record EventView(
            UUID id,
            String event,
            String actorType,
            String actorRef,
            String fromStatus,
            String toStatus,
            Instant at) {

        static EventView of(MemoryEvent e) {
            return new EventView(
                    e.id(),
                    e.event().name(),
                    e.actor().type().name(),
                    e.actor().ref(),
                    e.fromStatus() == null ? null : e.fromStatus().name(),
                    e.toStatus() == null ? null : e.toStatus().name(),
                    e.at());
        }
    }

    /**
     * The item plus its lineage and event history; the two latter are populated for EDITOR only.
     */
    public record DetailView(ItemView item, List<SourceView> lineage, List<EventView> events) {}

    /**
     * Lists an agent's items, newest first. No {@code status} means ACTIVE for a VIEWER and every
     * status for an EDITOR; asking for a non-ACTIVE status takes EDITOR outright (the review queue
     * is a moderation surface, not something a read-only grantee browses).
     */
    @GetMapping
    public List<ItemView> list(
            @PathVariable UUID agentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "100") int limit) {
        access().requireViewer(agentId);
        requireEnabled(agentId);
        MemoryItemStatus st = parseStatus(status);
        MemoryKind k = parseKind(kind);
        if (!access().isEditor(agentId)) {
            if (st != null && st != MemoryItemStatus.ACTIVE) {
                access().requireEditor(agentId);
            }
            st = MemoryItemStatus.ACTIVE;
        }
        return review.list(agentId, st, k, clamp(limit)).stream().map(ItemView::of).toList();
    }

    /**
     * One item. A VIEWER gets 404 for anything but an ACTIVE + TRUSTED row, so existence of a
     * quarantined item is not leaked; an EDITOR also gets its lineage and event history.
     */
    @GetMapping("/{itemId}")
    public ResponseEntity<DetailView> get(@PathVariable UUID agentId, @PathVariable UUID itemId) {
        access().requireViewer(agentId);
        requireEnabled(agentId);
        return review.get(agentId, itemId)
                .map(
                        item -> {
                            boolean editor = access().isEditor(agentId);
                            if (!editor
                                    && (item.status() != MemoryItemStatus.ACTIVE
                                            || item.trust() != MemoryTrustLevel.TRUSTED)) {
                                return ResponseEntity.notFound().<DetailView>build();
                            }
                            List<SourceView> lineage =
                                    editor
                                            ? review.lineage(agentId, itemId).stream()
                                                    .map(SourceView::of)
                                                    .toList()
                                            : List.of();
                            List<EventView> events =
                                    editor
                                            ? review.events(agentId, itemId).stream()
                                                    .map(EventView::of)
                                                    .toList()
                                            : List.of();
                            return ResponseEntity.ok(
                                    new DetailView(ItemView.of(item), lineage, events));
                        })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** PENDING to TRUSTED + ACTIVE; EDITOR, recorded as the approver. */
    @PostMapping("/{itemId}/approve")
    public ResponseEntity<Void> approve(
            @PathVariable UUID agentId, @PathVariable UUID itemId, Principal principal) {
        return decide(agentId, itemId, principal, review::approve);
    }

    /** PENDING to REJECTED; EDITOR. */
    @PostMapping("/{itemId}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable UUID agentId, @PathVariable UUID itemId, Principal principal) {
        return decide(agentId, itemId, principal, review::reject);
    }

    /** ACTIVE to REVERTED: recall stops returning it at once; EDITOR. */
    @PostMapping("/{itemId}/revert")
    public ResponseEntity<Void> revert(
            @PathVariable UUID agentId, @PathVariable UUID itemId, Principal principal) {
        return decide(agentId, itemId, principal, review::revert);
    }

    /**
     * Edit = a NEW version, never an in-place change: a new PENDING row (version + 1, {@code
     * supersedesId} set, writer HUMAN, re-screened and re-embedded, taint and generation kept, the
     * old row in its lineage) that needs its own approval. Answers 200 with the new version. 404
     * while the feature is dark, 400 for blank text, 404 for an unknown item, 409 when the item is
     * not PENDING or ACTIVE, 422 when the text fails the content screen. EDITOR.
     */
    @PatchMapping("/{itemId}")
    public ResponseEntity<ItemView> edit(
            @PathVariable UUID agentId,
            @PathVariable UUID itemId,
            @RequestBody(required = false) EditRequest body,
            Principal principal) {
        access().requireEditor(agentId);
        requireEnabled(agentId);
        if (body == null || !StringUtils.hasText(body.content())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Memory text cannot be empty.");
        }
        MemoryItemReviewService.Outcome outcome =
                review.edit(agentId, itemId, body.content().strip(), actor(principal));
        if (!outcome.ok()) {
            return refusal(outcome);
        }
        return review.get(agentId, outcome.itemId())
                .map(i -> ResponseEntity.ok(ItemView.of(i)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    /**
     * Pins an ACTIVE item so consolidation never forgets it; a person only (an agent has no route
     * here). EDITOR.
     */
    @PostMapping("/{itemId}/pin")
    public ResponseEntity<Void> pin(
            @PathVariable UUID agentId, @PathVariable UUID itemId, Principal principal) {
        access().requireEditor(agentId);
        requireEnabled(agentId);
        return decide(agentId, itemId, principal, review::pin);
    }

    /** Clears a pin. EDITOR. */
    @DeleteMapping("/{itemId}/pin")
    public ResponseEntity<Void> unpin(
            @PathVariable UUID agentId, @PathVariable UUID itemId, Principal principal) {
        access().requireEditor(agentId);
        requireEnabled(agentId);
        return decide(agentId, itemId, principal, review::unpin);
    }

    /**
     * Erasure with the lineage cascade: the item, every item derived from it, and each erased row's
     * lineage and event history go; one ERASED tombstone per erased id stays. EDITOR.
     */
    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> erase(
            @PathVariable UUID agentId, @PathVariable UUID itemId, Principal principal) {
        return decide(agentId, itemId, principal, review::erase);
    }

    // ------------------------------------------------------------------ helpers

    private interface Decision {
        MemoryItemReviewService.Outcome run(UUID agentId, UUID itemId, MemoryActor actor);
    }

    private ResponseEntity<Void> decide(
            UUID agentId, UUID itemId, Principal principal, Decision op) {
        access().requireEditor(agentId);
        MemoryItemReviewService.Outcome outcome = op.run(agentId, itemId, actor(principal));
        if (outcome.ok()) {
            return ResponseEntity.noContent().build();
        }
        return refusal(outcome);
    }

    private static <T> ResponseEntity<T> refusal(MemoryItemReviewService.Outcome outcome) {
        String reason = outcome.refusal();
        if (reason.startsWith("SCREEN_")) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
        }
        return switch (reason) {
            case "NOT_FOUND" -> ResponseEntity.notFound().build();
            case "NOT_HUMAN", "NO_TENANT" -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case "NO_EMBEDDER" -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            default -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    /** The access adapter; fail closed (404, like a dark feature) when no adapter is deployed. */
    private MemoryAgentAccess access() {
        MemoryAgentAccess a = access.getIfAvailable();
        if (a == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return a;
    }

    private void requireEnabled(UUID agentId) {
        if (!gate.enabled(agentId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Unified memory is not enabled for this agent.");
        }
    }

    private static MemoryActor actor(Principal principal) {
        return MemoryActor.human(principal == null ? null : principal.getName());
    }

    private static MemoryItemStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return MemoryItemStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown memory item status.");
        }
    }

    private static MemoryKind parseKind(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            MemoryKind kind = MemoryKind.valueOf(value.trim().toUpperCase());
            if (kind == MemoryKind.SEMANTIC) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "SEMANTIC memory is listed by the agent-memory endpoints.");
            }
            return kind;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown memory kind.");
        }
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
