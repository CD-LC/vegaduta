package com.agenticai.core.agents.tools.apiguide;

import com.agenticai.core.agentspi.ApiSpecProvider;
import com.agenticai.core.agentspi.WorkspaceToolSource;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Offers {@code vegaduta_api_guide} on every install, next to {@code vegaduta_setup_guide}: the spec
 * is read-only and the same for everyone, and the tool never calls the API (a live result runs in the
 * console with the signed-in user's own session). Off with {@code app.assistant.api-guide.enabled=false}.
 */
@Component
public class ApiGuideToolSource implements WorkspaceToolSource {

    private final List<ToolCallback> tools;

    public ApiGuideToolSource(
            ApiSpecProvider spec,
            @Value("${app.assistant.api-guide.enabled:true}") boolean enabled) {
        this.tools = enabled
                ? List.of(new ApiGuideToolCallback(spec))
                : List.of();
    }

    @Override
    public List<ToolCallback> rawTools() {
        return tools;
    }
}
