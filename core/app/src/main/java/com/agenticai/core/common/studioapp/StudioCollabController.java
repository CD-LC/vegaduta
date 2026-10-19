package com.agenticai.core.common.studioapp;

import com.agenticai.core.common.studioapp.StudioProjectService.StudioProjectException;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Members and comments of a studio project:
 * {@code /api/studio-projects/{id}/members} (owner manages, any member reads)
 * and {@code /api/studio-projects/{id}/comments} (any member).
 */
@RestController
@RequestMapping("/api/studio-projects/{id}")
public class StudioCollabController {

    public record InviteBody(String email, String role) {
    }

    public record MemberView(String subject, String name, String email, String role, Long addedAt) {
    }

    public record CommentBody(String body, Long versionAt) {
    }

    public record ResolveBody(Boolean resolved) {
    }

    public record CommentView(
        String id, Long versionAt, String authorSubject, String authorName, String body, long createdAt,
        boolean resolved
    ) {
    }

    private final StudioCollabService service;

    public StudioCollabController(StudioCollabService service) {
        this.service = service;
    }

    @GetMapping("/members")
    public ResponseEntity<List<MemberView>> members(@PathVariable String id, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.listMembers(id).stream().map(StudioCollabController::view).toList(), limit, offset, 50, 200);
    }

    @PostMapping("/members")
    public ResponseEntity<MemberView> invite(@PathVariable String id, @RequestBody InviteBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(service.invite(id, body.email(), body.role())));
    }

    @DeleteMapping("/members/{subject}")
    public ResponseEntity<Void> remove(@PathVariable String id, @PathVariable String subject) {
        service.removeMember(id, subject);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/comments")
    public ResponseEntity<List<CommentView>> comments(@PathVariable String id, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.listComments(id).stream().map(StudioCollabController::view).toList(), limit, offset, 50, 200);
    }

    @PostMapping("/comments")
    public ResponseEntity<CommentView> comment(@PathVariable String id, @RequestBody CommentBody body) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(view(service.addComment(id, body.body(), body.versionAt())));
    }

    /** Resolve / re-open a comment thread. */
    @PatchMapping("/comments/{commentId}")
    public CommentView resolve(@PathVariable String id, @PathVariable UUID commentId, @RequestBody ResolveBody body) {
        return view(service.setResolved(id, commentId, body.resolved() == null || body.resolved()));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable String id, @PathVariable UUID commentId) {
        service.deleteComment(id, commentId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(StudioProjectException.class)
    public ResponseEntity<Map<String, String>> onDomainError(StudioProjectException e) {
        HttpStatus status = switch (e.getMessage()) {
            case "project_not_found", "bad_id", "comment_not_found" -> HttpStatus.NOT_FOUND;
            case "no_subject" -> HttpStatus.UNAUTHORIZED;
            case "forbidden" -> HttpStatus.FORBIDDEN;
            case "directory_unavailable" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    private static MemberView view(StudioCollabService.MemberRow r) {
        StudioMemberDirectory.Person p = r.person();
        if (r.member() == null) {
            return new MemberView(p.subject(), p.name(), p.email(), "OWNER", null);
        }
        return new MemberView(
            r.member().getMemberSubject(), p.name(), p.email(), r.member().getRole(),
            r.member().getCreatedAt().toEpochMilli());
    }

    private static CommentView view(StudioProjectComment c) {
        return new CommentView(
            c.getId().toString(), c.getVersionAtMs(), c.getAuthorSubject(), c.getAuthorName(), c.getBody(),
            c.getCreatedAt().toEpochMilli(), c.isResolved());
    }
}
