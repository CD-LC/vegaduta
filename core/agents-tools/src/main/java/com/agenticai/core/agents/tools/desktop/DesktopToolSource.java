package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.agentspi.WorkspaceToolSource;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Offers the {@code local_desktop_*} tools - the personal install's computer-use surface - through
 * the same {@link WorkspaceToolSource} port as the terminal tools, so each tool is wrapped in the
 * tool guard like every other tool.
 *
 * <p>Offered only when ALL of: this is a single-user install ({@code personal} profile or {@code
 * app.assistant.local-shell.acknowledge-shared-host=true} - the same rule as the terminal, since
 * these tools act on the host outside any sandbox); and the desktop-agent sidecar is configured
 * ({@code app.assistant.desktop.agent-url} plus {@code app.assistant.desktop.agent-secret}). The
 * URL being set IS the opt-in: nobody runs the agent by accident, because it lives on the laptop,
 * not in the compose stack.
 */
@Component
public class DesktopToolSource implements WorkspaceToolSource {

    private static final Logger log = LoggerFactory.getLogger(DesktopToolSource.class);

    /** The actuating tools - read-only ones (screen, elements, notify, clipboard) are never pinned. */
    public static final Set<String> GATED_NAMES =
            Set.of(
                    DesktopToolCallback.Action.PRESS.toolName,
                    DesktopToolCallback.Action.CLICK.toolName,
                    DesktopToolCallback.Action.TYPE.toolName,
                    DesktopToolCallback.Action.KEY.toolName,
                    DesktopToolCallback.Action.OPEN.toolName,
                    DesktopToolCallback.Action.ACTIVATE.toolName);

    private final boolean enabled;
    private final boolean singleUser;
    private final List<ToolCallback> tools;
    private final String refusal;

    public DesktopToolSource(
            Environment environment,
            @Value("${app.assistant.local-shell.acknowledge-shared-host:false}") boolean acknowledgeSharedHost,
            @Value("${app.assistant.desktop.agent-url:}") String agentUrl,
            @Value("${app.assistant.desktop.agent-secret:}") String agentSecret,
            @Value("${app.assistant.desktop.approval-required:true}") boolean approvalRequired) {
        this.singleUser = acknowledgeSharedHost || environment.acceptsProfiles(Profiles.of("personal"));
        boolean configured = agentUrl != null && !agentUrl.isBlank();
        this.enabled = configured && singleUser;
        this.refusal =
                !configured
                        ? null
                        : singleUser
                                ? null
                                : "this is not a single-user install (personal profile or"
                                        + " app.assistant.local-shell.acknowledge-shared-host=true)";
        this.tools =
                enabled
                        ? shareOneClient(agentUrl.strip(), agentSecret, approvalRequired)
                        : List.of();
    }

    @PostConstruct
    void report() {
        if (refusal != null) {
            log.warn("app.assistant.desktop.agent-url is set but the desktop tools are not offered: {}", refusal);
        } else if (enabled) {
            log.info("Desktop tools on: {} local_desktop_* tools via the desktop-agent sidecar", tools.size());
        }
    }

    private static List<ToolCallback> shareOneClient(
            String agentUrl, String agentSecret, boolean approvalRequired) {
        DesktopAgentClient client = new DesktopAgentClient(agentUrl, agentSecret);
        return java.util.Arrays.stream(DesktopToolCallback.Action.values())
                .map(a -> (ToolCallback) new DesktopToolCallback(client, a, approvalRequired))
                .toList();
    }

    @Override
    public List<ToolCallback> rawTools() {
        return tools;
    }
}
