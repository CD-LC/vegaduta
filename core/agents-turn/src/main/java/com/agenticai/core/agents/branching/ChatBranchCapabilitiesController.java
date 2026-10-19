package com.agenticai.core.agents.branching;

import com.agenticai.core.agents.branching.ChatBranchDtos.Capabilities;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Discovery for the web: does this deployment offer edit / regenerate / branch switching? Same
 * shape as {@code AgentLabController#capabilities}. Answers 200 with {@code enabled=false} when the
 * flag is off, so a flag-off chat load costs one quiet call instead of a 404 in the console.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat-branching")
public class ChatBranchCapabilitiesController {

    private final ChatBranchService service;

    public ChatBranchCapabilitiesController(ChatBranchService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities(@PathVariable UUID agentId) {
        return service.capabilities(agentId);
    }
}
