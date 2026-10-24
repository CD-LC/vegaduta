package com.agenticai.core.agents.promptdirectives;

import com.agenticai.core.agents.turn.PromptPreviewService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/agents/{agentId}/prompt-preview}: the real system prompt and tool list of an
 * agent, as a turn would build them, with each block tagged by registry key, scope, version and
 * source. Nothing is sent to a model or stored. The caller needs EDITOR access to the agent
 * (checked in {@link PromptPreviewService}); the preview shows prompts, the tool list and (when
 * asked) the caller's recalled memory, which a viewer must not read.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/prompt-preview")
public class AgentPromptPreviewController {

    private final PromptPreviewService service;

    public AgentPromptPreviewController(PromptPreviewService service) {
        this.service = service;
    }

    @PostMapping
    public PromptPreviewService.Preview preview(
            @PathVariable UUID agentId, @RequestBody(required = false) PromptPreviewService.Request body) {
        return service.preview(agentId, body);
    }
}
