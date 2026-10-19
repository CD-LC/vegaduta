package com.agenticai.core.sdlc.dto;

import com.agenticai.core.sdlc.E2bSandboxClient.SandboxHandle;

public record SandboxResponse(String sandboxId, String templateId, String envdVersion) {
    public static SandboxResponse from(SandboxHandle handle) {
        return new SandboxResponse(handle.sandboxId(), handle.templateId(), handle.envdVersion());
    }
}
