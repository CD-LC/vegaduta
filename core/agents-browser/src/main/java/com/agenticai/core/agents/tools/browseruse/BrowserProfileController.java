package com.agenticai.core.agents.tools.browseruse;

import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.CreateProfileRequest;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.ProfileView;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.UpdateProfileRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser profile management: list, create, rename/re-scope, delete, sign out, and attach to /
 * detach from an agent. Thin on purpose - visibility, permissions, validation and every status code
 * are decided in {@link BrowserProfileService}, which throws only types {@code
 * GlobalExceptionHandler} already maps ({@code IllegalArgumentException} 400, {@code
 * ResponseStatusException} 403/404/409/503, and {@code ResourceAccessDeniedException} 404 from the
 * agent access check).
 *
 * <p>No response here carries the saved cookies or localStorage - only metadata (see {@link
 * ProfileView}). The whole surface answers 404 until {@code app.browser.profiles.enabled} is
 * switched on. No {@code SecurityConfig} entry: the paths fall through to {@code
 * anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/api/browser/profiles")
public class BrowserProfileController {

    private final BrowserProfileService service;

    public BrowserProfileController(BrowserProfileService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ProfileView>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            service.list(),
            limit, offset, 50, 500);
    }

    @PostMapping
    public ResponseEntity<ProfileView> create(@RequestBody CreateProfileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PatchMapping("/{id}")
    public ProfileView update(@PathVariable UUID id, @RequestBody UpdateProfileRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Forgets the saved login (cookies + localStorage) but keeps the profile and its attachments.
     */
    @PostMapping("/{id}/clear-state")
    public ResponseEntity<Void> clearState(@PathVariable UUID id) {
        service.clearState(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/agents/{agentId}")
    public ProfileView attach(@PathVariable UUID id, @PathVariable UUID agentId) {
        return service.attach(id, agentId);
    }

    @DeleteMapping("/{id}/agents/{agentId}")
    public ResponseEntity<Void> detach(@PathVariable UUID id, @PathVariable UUID agentId) {
        service.detach(id, agentId);
        return ResponseEntity.noContent().build();
    }
}
