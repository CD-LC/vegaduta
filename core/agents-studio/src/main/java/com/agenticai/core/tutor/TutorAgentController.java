package com.agenticai.core.tutor;

import com.agenticai.core.tutor.dto.TutorDtos.TutorAgentRequest;
import com.agenticai.core.tutor.dto.TutorDtos.TutorAgentResponse;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/tutor/agent} - returns the caller's own tutor agent, creating it on first use
 * (see {@link TutorAgentProvisioner}). Same flag as the rest of the tutor: with
 * {@code app.tutor.enabled=false} the route is not mapped and answers 404. The caller can only ever
 * get their own agent; nothing in the request names a user.
 */
@RestController
@RequestMapping("/api/tutor/agent")
@ConditionalOnProperty(prefix = "app.tutor", name = "enabled", havingValue = "true")
public class TutorAgentController {

    private final TutorAgentProvisioner provisioner;

    public TutorAgentController(TutorAgentProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @PostMapping
    public TutorAgentResponse ensure(@RequestBody(required = false) TutorAgentRequest request, Principal principal) {
        return provisioner.ensureTutorAgent(
            StudyPlanController.subject(principal), request == null ? null : request.planId());
    }
}
