package com.agenticai.core.agents.tools.apiguide;

import com.agenticai.core.agentspi.ApiSpecProvider;
import com.agenticai.core.agentspi.WorkspaceToolSource;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Offers {@code vegaduta_api_guide} on every install, next to {@code vegaduta_setup_guide}: the
 * spec is read-only and the same for everyone, and the tool never calls the API (a live result runs
 * in the console with the signed-in user's own session). Off with {@code
 * app.assistant.api-guide.enabled=false}.
 *
 * <p>The spec provider is the API server's {@code ApiSpecController}, a REST controller, so the
 * jobs and worker shapes (which scan no controllers) have none: the tool is then simply not offered
 * there instead of failing the start of the whole context.
 */
@Component
public class ApiGuideToolSource implements WorkspaceToolSource {

    private final List<ToolCallback> tools;

    public ApiGuideToolSource(
            ObjectProvider<ApiSpecProvider> spec,
            @Value("${app.assistant.api-guide.enabled:true}") boolean enabled) {
        ApiSpecProvider provider = enabled ? spec.getIfAvailable() : null;
        this.tools = provider != null ? List.of(new ApiGuideToolCallback(provider)) : List.of();
    }

    @Override
    public List<ToolCallback> rawTools() {
        return tools;
    }
}
