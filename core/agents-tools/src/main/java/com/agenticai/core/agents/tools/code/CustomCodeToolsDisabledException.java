package com.agenticai.core.agents.tools.code;

/**
 * This deployment has {@code tool-sandbox.enabled=false}, so no {@link ToolSandboxClient} bean
 * exists and nothing can be validated or run. A 400 (operator configuration state the caller can be
 * told about plainly), not a 502 - nothing failed; the feature was simply never turned on.
 */
public class CustomCodeToolsDisabledException extends RuntimeException {

    public CustomCodeToolsDisabledException() {
        super(
                "Custom code tools are not enabled on this deployment (set TOOL_SANDBOX_ENABLED=true).");
    }
}
