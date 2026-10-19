package com.agenticai.core.agents.learning;

import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in person's learning proposals: what the assistant would like to learn, for them to approve or
 * reject. Scoped to the caller's own subject (the principal name, as {@code /api/memory} does). Exists only with
 * {@code app.learning.proposals.enabled=true}; otherwise the routes 404.
 */
@RestController
@RequestMapping("/api/learning/proposals")
@ConditionalOnProperty(name = "app.learning.proposals.enabled", havingValue = "true")
public class LearningProposalController {

    private final LearningProposalService service;

    public LearningProposalController(LearningProposalService service) {
        this.service = service;
    }

    /** {@code body} is the person's edit of the proposed text; omit it to approve as proposed. */
    public record ApproveRequest(String body) {}

    @GetMapping
    public List<LearningProposalView> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "limit", required = false) Integer limit,
            Principal principal) {
        return service.list(
                subject(principal),
                parse(LearningProposalStatus.class, status),
                parse(LearningProposalKind.class, kind),
                limit);
    }

    @PostMapping("/{id}/approve")
    public LearningProposalView approve(
            @PathVariable UUID id, @RequestBody(required = false) ApproveRequest body, Principal principal) {
        return service.approve(subject(principal), id, body == null ? null : body.body());
    }

    @PostMapping("/{id}/reject")
    public LearningProposalView reject(@PathVariable UUID id, Principal principal) {
        return service.reject(subject(principal), id);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown filter value.");
        }
    }

    private static String subject(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to review suggestions.");
        }
        return principal.getName();
    }
}
