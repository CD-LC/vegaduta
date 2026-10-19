package com.agenticai.core.tutor;

import com.agenticai.core.tutor.dto.TutorDtos.TutorStatus;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tutor's availability probe while the feature is OFF (the default): {@code GET /api/tutor/status}
 * answers 200 {@code {"enabled":false}} instead of a 404 that every browser logs as a console error on
 * each page that asks. Mutually exclusive with {@link StudyPlanController}, which owns the same path
 * (and the rest of the {@code /api/tutor} tree) when {@code app.tutor.enabled=true}; with the flag off
 * every other tutor route is still unmapped. Same auth as the real route: any signed-in member.
 */
@RestController
@ConditionalOnProperty(prefix = "app.tutor", name = "enabled", havingValue = "false", matchIfMissing = true)
public class TutorStatusOffController {

    // Full path on the method (no class-level base path): /api/tutor is StudyPlanController's base path and
    // entity-name-lint rejects two controllers sharing one.
    @GetMapping("/api/tutor/status")
    public TutorStatus status(Principal principal) {
        StudyPlanController.subject(principal);
        return new TutorStatus(false, false, false, null);
    }
}
