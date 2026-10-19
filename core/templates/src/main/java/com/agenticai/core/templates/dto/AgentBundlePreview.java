package com.agenticai.core.templates.dto;

import java.util.List;

/**
 * What importing a bundle would do, before it does it: the tools the agent may use, the autonomy it
 * will really get, what was changed for safety, and the publishing-quality report (informational
 * here; only publishing to the gallery requires it to pass).
 */
public record AgentBundlePreview(
        String title,
        List<String> tools,
        String autonomyTier,
        List<String> adjustments,
        TemplateReadinessReport readiness) {}
