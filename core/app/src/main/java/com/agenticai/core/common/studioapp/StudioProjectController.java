package com.agenticai.core.common.studioapp;

import com.agenticai.core.common.studioapp.StudioProjectRules.VersionIn;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/studio-projects} - the studio's server-side project and version
 * history mirror. Plain authenticated-tenant-member access, scoped to the
 * caller's own projects (see {@link StudioProjectService}).
 */
@RestController
@RequestMapping("/api/studio-projects")
public class StudioProjectController {

    public record VersionBody(long at, String note, String source, String html) {
    }

    public record UpsertBody(String name, String emoji, String settings, Long createdAt, List<VersionBody> versions) {
    }

    public record VersionView(long at, String note, String source, String html, String authorSubject) {
    }

    public record ProjectView(
        String id, String name, String emoji, String settings, long createdAt, long updatedAt,
        long versionCount, List<VersionView> versions, String role
    ) {
    }

    private final StudioProjectService service;

    public StudioProjectController(StudioProjectService service) {
        this.service = service;
    }

    /** Lightweight list (no HTML) so a fresh device can decide what to pull. */
    @GetMapping
    public ResponseEntity<List<ProjectView>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.list().stream()
            .map(s -> view(s.project(), s.versionCount(), null, s.role()))
            .toList(), limit, offset, 50, 200);
    }

    @GetMapping("/{id}")
    public ProjectView detail(@PathVariable String id) {
        StudioProjectService.Detail d = service.detail(id);
        return view(d.project(), d.versions().size(), d.versions(), d.role());
    }

    /** Create-or-update; versions already stored (same timestamp) are skipped. */
    @PutMapping("/{id}")
    public ProjectView upsert(@PathVariable String id, @RequestBody UpsertBody body) {
        List<VersionIn> versions = body.versions() == null ? List.of()
            : body.versions().stream()
                .map(v -> new VersionIn(v.at(), v.note(), v.source(), v.html()))
                .toList();
        StudioProjectService.Detail d = service.upsert(id, new StudioProjectService.ProjectIn(
            body.name(), body.emoji(), body.settings(), body.createdAt(), versions));
        return view(d.project(), d.versions().size(), d.versions(), d.role());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(StudioProjectService.StudioProjectException.class)
    public ResponseEntity<Map<String, String>> onDomainError(StudioProjectService.StudioProjectException e) {
        HttpStatus status = switch (e.getMessage()) {
            case "project_not_found", "bad_id" -> HttpStatus.NOT_FOUND;
            case "no_subject" -> HttpStatus.UNAUTHORIZED;
            case "forbidden" -> HttpStatus.FORBIDDEN;
            case "version_too_large", "payload_too_large", "settings_too_large" -> HttpStatus.CONTENT_TOO_LARGE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    static ProjectView view(
        StudioProject p, long versionCount, List<StudioProjectVersion> versions, StudioCollabRules.Role role
    ) {
        return new ProjectView(
            p.getId(), p.getName(), p.getEmoji(), p.getSettings(),
            p.getCreatedAt().toEpochMilli(), p.getUpdatedAt().toEpochMilli(), versionCount,
            versions == null ? null
                : versions.stream()
                    .map(v -> new VersionView(v.getAtMs(), v.getNote(), v.getSource(), v.getHtml(), v.getAuthorSubject()))
                    .toList(),
            role.name());
    }
}
