package com.agenticai.core.providers;

import com.agenticai.core.providers.dto.LocalOpenAiProbeRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tool-calling probe for the tenant's connected local / OpenAI-compatible server - see {@link
 * LocalOpenAiProbeService}. A separate controller (own base path) so {@link ProviderController}'s
 * constructor and its callers stay unchanged.
 */
@RestController
@RequestMapping("/api/providers/local-openai")
public class LocalOpenAiProbeController {

    private final LocalOpenAiProbeService probeService;

    public LocalOpenAiProbeController(LocalOpenAiProbeService probeService) {
        this.probeService = probeService;
    }

    /** Grades how {@code model} on the connected server makes tool calls; advisory, changes nothing. */
    @PostMapping("/probe")
    public LocalOpenAiProbeResponse probe(@Valid @RequestBody LocalOpenAiProbeRequest request) {
        return probeService.probe(request.model());
    }
}
