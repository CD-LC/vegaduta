package com.agenticai.core.agents.tools.platformguide;

import com.agenticai.core.agentspi.SetupStatusProvider;
import com.agenticai.core.agentspi.SetupStatusProvider.Status;
import com.agenticai.core.agentspi.WorkspaceToolSource;
import com.agenticai.core.json.JsonMappers;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Offers {@code vegaduta_setup_guide} on every install (shared server and personal alike): the guide
 * is read-only, the catalogue and docs are the same for everyone, and the live status it adds is read
 * for the calling tenant. Off with {@code app.assistant.platform-guide.enabled=false}.
 */
@Component
public class SetupGuideToolSource implements WorkspaceToolSource {

    private static final Logger log = LoggerFactory.getLogger(SetupGuideToolSource.class);

    private final List<ToolCallback> tools;

    public SetupGuideToolSource(
            ObjectProvider<SetupStatusProvider> providers,
            McpSetupAdvisor mcp,
            @Value("${app.assistant.platform-guide.enabled:true}") boolean enabled) {
        List<ToolCallback> built = List.of();
        if (enabled) {
            try {
                PlatformGuide guide = PlatformGuide.load(JsonMappers.plain());
                built = List.of(new SetupGuideToolCallback(guide, () -> collect(providers), mcp));
            } catch (IOException | RuntimeException e) {
                log.warn("vegaduta_setup_guide is off: the platform guide could not be loaded", e);
            }
        }
        this.tools = built;
    }

    private static Map<String, Status> collect(ObjectProvider<SetupStatusProvider> providers) {
        Map<String, Status> all = new HashMap<>();
        providers.orderedStream().forEach(p -> {
            try {
                all.putAll(p.statuses());
            } catch (RuntimeException e) {
                log.debug("A setup status provider failed", e);
            }
        });
        return all;
    }

    @Override
    public List<ToolCallback> rawTools() {
        return tools;
    }
}
