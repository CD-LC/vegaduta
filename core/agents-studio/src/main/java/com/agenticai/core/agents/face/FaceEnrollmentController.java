package com.agenticai.core.agents.face;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Face recognition (2026-08-06) - "agent should remember who's in view if we add a photo in the
 * knowledge base with that person's name." Every write and read here is gated on {@code
 * Agent.biometricConsentEnabled} (see {@link FaceEnrollmentService#enroll}/{@link
 * FaceEnrollmentService#listForCollection}).
 *
 * <p>Access (authorization review 2026-10-10): the consent flag is a policy switch, not an access
 * check - before this, any tenant member could name any consent-enabled agent and pull every face
 * template of any collection, or enrol under it. Now the caller needs {@code RUNNER} on the agent
 * to list (matching happens client-side for a chat they are allowed to have) and {@code EDITOR} to
 * enrol (knowledge authoring); enrol and delete are also gated on {@code PERM_MANAGE_AGENTS} in
 * {@code SecurityConfig}. {@code @Profile("knowledge")} matches {@code KnowledgeController} since
 * this is meaningless without the knowledge module active (every enrollment references a {@code
 * collectionId}).
 *
 * <p>All ML inference (detection, embedding, matching) happens client-side ({@code
 * web/app/lib/edge/face.ts}) - this controller only ever receives an already-computed 512-float
 * embedding, never an image. Frames/photos never reach this backend for face-recognition purposes.
 */
@RestController
@RequestMapping("/api/face-enrollments")
@Profile("knowledge")
public class FaceEnrollmentController {

    private static final int EMBEDDING_DIMENSIONS = 512;

    private final FaceEnrollmentService faceEnrollmentService;
    private final AgentService agentService;

    public FaceEnrollmentController(
            FaceEnrollmentService faceEnrollmentService, AgentService agentService) {
        this.faceEnrollmentService = faceEnrollmentService;
        this.agentService = agentService;
    }

    public record FaceEnrollmentRequest(
            @NotNull(message = "agentId is required") UUID agentId,
            @NotNull(message = "collectionId is required") UUID collectionId,
            UUID documentId,
            @NotBlank(message = "personName is required") String personName,
            @NotNull(message = "embedding is required") List<Double> embedding,
            // No default - an omitted/false value is a hard rejection (see
            // FaceEnrollmentService#enroll), not silently treated as consent.
            boolean consented) {}

    public record FaceEnrollmentResponse(
            UUID id, String personName, List<Double> embedding, Instant createdAt) {
        static FaceEnrollmentResponse from(FaceEnrollmentService.FaceEnrollmentRecord record) {
            return new FaceEnrollmentResponse(
                    record.id(), record.personName(), record.embedding(), record.createdAt());
        }
    }

    @PostMapping
    public ResponseEntity<FaceEnrollmentResponse> enroll(
            @Valid @RequestBody FaceEnrollmentRequest request) {
        agentService.requireAccess(request.agentId(), PermissionLevel.EDITOR);
        if (request.embedding().size() != EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException(
                    "embedding must have exactly "
                            + EMBEDDING_DIMENSIONS
                            + " dimensions (got: "
                            + request.embedding().size()
                            + ")");
        }
        UUID id =
                faceEnrollmentService.enroll(
                        request.agentId(),
                        request.collectionId(),
                        request.documentId(),
                        request.personName(),
                        request.embedding(),
                        request.consented());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new FaceEnrollmentResponse(
                                id,
                                request.personName().trim(),
                                request.embedding(),
                                Instant.now()));
    }

    /**
     * The web Playground calls this ONCE per session (not per-frame) and caches the result -
     * matching itself happens client-side against this list, so a live camera turn never makes a
     * network call for it.
     */
    @GetMapping
    public ResponseEntity<List<FaceEnrollmentResponse>> list(
            @RequestParam UUID agentId, @RequestParam UUID collectionId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        return ListPaging.window(
            faceEnrollmentService.listForCollection(agentId, collectionId).stream()
                .map(FaceEnrollmentResponse::from)
                .toList(),
            limit, offset, 50, 500);
    }

    @DeleteMapping("/{enrollmentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID enrollmentId) {
        faceEnrollmentService.delete(enrollmentId);
        return ResponseEntity.noContent().build();
    }
}
