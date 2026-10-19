package com.agenticai.core.agents.tools.code.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** {@code GET /health} - liveness plus what the running sandbox can actually do. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxHealthResponse(
        String status, List<String> languages, String seccomp, String version) {}
