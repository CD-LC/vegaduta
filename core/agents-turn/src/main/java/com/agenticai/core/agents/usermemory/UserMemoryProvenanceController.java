package com.agenticai.core.agents.usermemory;

import com.agenticai.core.agents.memory.MemoryProvenanceService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Where did this memory come from?" for one of the caller's own facts - the read-only companion to
 * {@link UserMemoryController}. Kept in its own controller (and its own base path) so the two can change
 * independently. Scoped to the caller's principal name exactly like {@link UserMemoryController}: a fact
 * that is not theirs answers 404. Shares {@code app.memory.self-service.enabled} with the other
 * self-service endpoints: off = 404 and the tool-call log is never read.
 */
@RestController
@RequestMapping("/api/memory/facts")
public class UserMemoryProvenanceController {

    private final MemoryProvenanceService provenanceService;
    private final boolean selfServiceEnabled;

    public UserMemoryProvenanceController(
        MemoryProvenanceService provenanceService,
        @Value("${app.memory.self-service.enabled:true}") boolean selfServiceEnabled
    ) {
        this.provenanceService = provenanceService;
        this.selfServiceEnabled = selfServiceEnabled;
    }

    @GetMapping("/{scope}/{id}/source")
    public MemoryProvenanceService.FactSource source(@PathVariable String scope, @PathVariable UUID id, Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to manage your memory.");
        }
        if (!selfServiceEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory self-service is disabled.");
        }
        try {
            return provenanceService.sourceOf(scope, id, principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory not found."));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
