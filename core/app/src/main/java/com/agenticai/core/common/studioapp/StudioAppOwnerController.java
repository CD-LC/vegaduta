package com.agenticai.core.common.studioapp;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /api/studio-apps} - the OWNER half of published Phone-App-Studio apps: publish/update,
 * list, disable/delete, and manage the app's own end users. Plain authenticated-tenant-member
 * access (the same level the studio itself runs at); per-connection authorization is enforced
 * inside {@link StudioAppService#publish} - wiring an agent you don't own fails the same
 * owner-or-admin check that minting its public share does.
 *
 * <p>The public half (what an anonymous phone hitting the app link can do) is {@link
 * StudioAppPublicController}.
 */
@RestController
@RequestMapping("/api/studio-apps")
public class StudioAppOwnerController {

    private final StudioAppService appService;
    private final StudioAppDomainService domainService;
    private final String appPublicUrl;

    public StudioAppOwnerController(
            StudioAppService appService,
            StudioAppDomainService domainService,
            @Value("${app.web.public-url:}") String appPublicUrl) {
        this.appService = appService;
        this.domainService = domainService;
        this.appPublicUrl =
                appPublicUrl != null && appPublicUrl.endsWith("/")
                        ? appPublicUrl.substring(0, appPublicUrl.length() - 1)
                        : appPublicUrl;
    }

    public record AppSummary(
            String id,
            String slug,
            String name,
            String status,
            String authMode,
            boolean aiEnabled,
            int publishedVersion,
            String updatedAt,
            long userCount,
            int connectionCount,
            String publicUrl) {}

    public record ConnectionView(
            String alias,
            String kind,
            String targetId,
            String shareId,
            String displayName,
            String requestTemplate,
            String responsePath,
            boolean requiresAuth) {
        static ConnectionView of(StudioAppConnection c) {
            return new ConnectionView(
                    c.getAlias(),
                    c.getKind().name(),
                    c.getTargetId().toString(),
                    c.getShareId(),
                    c.getDisplayName(),
                    c.getRequestTemplate(),
                    c.getResponsePath(),
                    c.isRequiresAuth());
        }
    }

    public record AppDetail(
            String id,
            String slug,
            String name,
            String status,
            String authMode,
            boolean aiEnabled,
            String aiSystemPrompt,
            int aiMaxTokens,
            int publishedVersion,
            String updatedAt,
            String publicUrl,
            List<ConnectionView> connections) {}

    public record PublishResponse(
            String appId, String slug, String publicUrl, int publishedVersion) {}

    public record EndUserView(
            String id,
            String email,
            String displayName,
            String status,
            String createdAt,
            String lastLoginAt) {
        static EndUserView of(StudioAppUser u) {
            return new EndUserView(
                    u.getId().toString(),
                    u.getEmail(),
                    u.getDisplayName(),
                    u.getStatus().name(),
                    u.getCreatedAt().toString(),
                    u.getLastLoginAt() == null ? null : u.getLastLoginAt().toString());
        }
    }

    @GetMapping
    public List<AppSummary> list() {
        return appService.listApps().stream()
                .map(
                        a ->
                                new AppSummary(
                                        a.getId().toString(),
                                        a.getSlug(),
                                        a.getName(),
                                        a.getStatus().name(),
                                        a.getAuthMode().name(),
                                        a.isAiEnabled(),
                                        a.getPublishedVersion(),
                                        a.getUpdatedAt().toString(),
                                        appService.countUsers(a.getId()),
                                        appService.connectionsOf(a.getId()).size(),
                                        publicUrl(a)))
                .toList();
    }

    @GetMapping("/{appId}")
    public ResponseEntity<?> detail(@PathVariable String appId) {
        return appService
                .findOwned(parseId(appId))
                .<ResponseEntity<?>>map(
                        a ->
                                ResponseEntity.ok(
                                        new AppDetail(
                                                a.getId().toString(),
                                                a.getSlug(),
                                                a.getName(),
                                                a.getStatus().name(),
                                                a.getAuthMode().name(),
                                                a.isAiEnabled(),
                                                a.getAiSystemPrompt(),
                                                a.getAiMaxTokens(),
                                                a.getPublishedVersion(),
                                                a.getUpdatedAt().toString(),
                                                publicUrl(a),
                                                appService.connectionsOf(a.getId()).stream()
                                                        .map(ConnectionView::of)
                                                        .toList())))
                .orElseGet(
                        () ->
                                ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of("error", "app_not_found")));
    }

    /**
     * Create-or-update: {@code appId} inside the body continues an existing app (same slug, same
     * end users).
     */
    @PostMapping("/publish")
    public ResponseEntity<?> publish(@Valid @RequestBody StudioAppService.PublishRequest request) {
        try {
            StudioApp app = appService.publish(request);
            return ResponseEntity.ok(
                    new PublishResponse(
                            app.getId().toString(),
                            app.getSlug(),
                            publicUrl(app),
                            app.getPublishedVersion()));
        } catch (StudioAppService.StudioAppException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        }
        // requireOwnedAgent/requireOwnedWorkflow denials (AgentNotFoundException,
        // ResourceAccessDeniedException) fall through to GlobalExceptionHandler's
        // own 404 mapping - deliberately not caught here.
    }

    @PostMapping("/{appId}/disable")
    public ResponseEntity<?> disable(@PathVariable String appId) {
        appService.setStatus(parseId(appId), StudioApp.Status.DISABLED);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{appId}/enable")
    public ResponseEntity<?> enable(@PathVariable String appId) {
        appService.setStatus(parseId(appId), StudioApp.Status.LIVE);
        return ResponseEntity.noContent().build();
    }

    /**
     * Full delete - drops the app, its connections AND its end-user accounts, and unlinks the slug.
     */
    @DeleteMapping("/{appId}")
    public ResponseEntity<?> delete(@PathVariable String appId) {
        domainService.releaseAll(parseId(appId));
        appService.delete(parseId(appId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{appId}/users")
    public ResponseEntity<?> users(@PathVariable String appId) {
        return appService
                .findOwned(parseId(appId))
                .<ResponseEntity<?>>map(
                        a ->
                                ResponseEntity.ok(
                                        appService.listUsers(a.getId()).stream()
                                                .map(EndUserView::of)
                                                .toList()))
                .orElseGet(
                        () ->
                                ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of("error", "app_not_found")));
    }

    @PostMapping("/{appId}/users/{userId}/disable")
    public ResponseEntity<?> disableUser(@PathVariable String appId, @PathVariable String userId) {
        appService.setUserStatus(parseId(appId), parseId(userId), StudioAppUser.Status.DISABLED);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{appId}/users/{userId}/enable")
    public ResponseEntity<?> enableUser(@PathVariable String appId, @PathVariable String userId) {
        appService.setUserStatus(parseId(appId), parseId(userId), StudioAppUser.Status.ACTIVE);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{appId}/users/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable String appId, @PathVariable String userId) {
        appService.deleteUser(parseId(appId), parseId(userId));
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            StudioAppService.StudioAppException.class)
    public ResponseEntity<?> onDomainError(StudioAppService.StudioAppException e) {
        HttpStatus status =
                switch (e.getMessage()) {
                    case "app_not_found", "user_not_found", "bad_id" -> HttpStatus.NOT_FOUND;
                    default -> HttpStatus.UNPROCESSABLE_ENTITY;
                };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    // ------------------------------------------------------------------

    private String publicUrl(StudioApp app) {
        return appPublicUrl + "/run-app.html#a=" + app.getSlug();
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new StudioAppService.StudioAppException("bad_id");
        }
    }
}
