package com.agenticai.core.common.agentgen;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prompt-to-agent endpoint (Tier-3): {@code POST /api/agents/generate}
 * with {@code {"description": "..."}} creates a real agent through {@link
 * AgentGenerationService} - see that class's javadoc for the safety
 * posture (conservative spec application, no channel hijacking,
 * reviewable output).
 *
 * <p>Sits under the same {@code /api/agents} umbrella as the rest of the
 * agent surface, inheriting the same SecurityConfig matchers - nothing
 * about "who may generate an agent" is decided here.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentGenerationController {

    public record AgentGenerationRequest(
        @NotBlank(message = "description is required") String description
    ) {
    }

    private final AgentGenerationService generationService;

    public AgentGenerationController(AgentGenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping("/generate")
    public Map<String, Object> generate(@Valid @RequestBody AgentGenerationRequest request) {
        return generationService.generate(request.description());
    }

    @ExceptionHandler(AgentGenerationException.class)
    public ResponseEntity<Map<String, String>> generationFailed(AgentGenerationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
