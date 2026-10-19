package com.agenticai.core.sdlc.dto;

import com.agenticai.core.sdlc.E2bSandboxClient.ListedSandbox;
import java.time.Instant;

public record SandboxSummaryResponse(String sandboxId, String templateId, String state, Instant startedAt, Instant endAt) {
    public static SandboxSummaryResponse from(ListedSandbox listed) {
        return new SandboxSummaryResponse(listed.sandboxId(), listed.templateId(), listed.state(), listed.startedAt(), listed.endAt());
    }
}
