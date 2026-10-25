package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.agents.tools.DesktopToolNames;
import com.agenticai.core.agentspi.WorkspaceToolSource;
import com.agenticai.core.audit.AuditLogService;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * <p>{@code run_desktop_recipe} is appended only when a {@link DesktopRecipeService} bean exists
 * AND is enabled ({@code app.assistant.desktop.recipes.enabled}, default off), so the desktop
 * surface stays exactly what it was unless both the sidecar opt-in and the recipe flag are set. It
 * is an ACTUATING tool - the whole replay can click/type/submit - so it joins {@link #GATED_NAMES}
 * and is pinned for approval by the same {@code app.assistant.desktop.approval-required} dial.
 */
@Component
public class DesktopToolSource implements WorkspaceToolSource {

    private static final Logger log = LoggerFactory.getLogger(DesktopToolSource.class);

    /**
     * The actuating tools - read-only ones (screen, elements, notify, clipboard) are never pinned.
     * Lives in {@link DesktopToolNames} (kernel) so governance can read it without a tools ->
     * tools.desktop package edge.
     */
    public static final Set<String> GATED_NAMES = DesktopToolNames.GATED;

    private final boolean enabled;
    private final boolean singleUser;
    private final List<ToolCallback> tools;
    private final String refusal;

    /** The one sidecar client every tool callback (and the monitor endpoint) shares. */
    private final DesktopAgentClient client;

    /**
     * @param recipes recipe replay is optional: the service bean may not exist at all (an older
     *     wiring or a test context), and when it does it may still be flag-off - {@link
     *     ObjectProvider} keeps both cases a "no extra tool" instead of a startup failure.
     */
    public DesktopToolSource(
            Environment environment,
            @Value("${app.assistant.local-shell.acknowledge-shared-host:false}")
                    boolean acknowledgeSharedHost,
            @Value("${app.assistant.desktop.agent-url:}") String agentUrl,
            @Value("${app.assistant.desktop.agent-secret:}") String agentSecret,
            @Value("${app.assistant.desktop.approval-required:true}") boolean approvalRequired,
            ObjectProvider<DesktopRecipeService> recipes,
            ObjectProvider<AuditLogService> audit) {
        this.singleUser =
                acknowledgeSharedHost || environment.acceptsProfiles(Profiles.of("personal"));
        boolean configured = agentUrl != null && !agentUrl.isBlank();
        this.enabled = configured && singleUser;
        this.refusal =
                !configured
                        ? null
                        : singleUser
                                ? null
                                : "this is not a single-user install (personal profile or"
                                        + " app.assistant.local-shell.acknowledge-shared-host=true)";
        this.client = enabled ? new DesktopAgentClient(agentUrl.strip(), agentSecret) : null;
        this.tools = enabled ? callbacksOn(client, approvalRequired, recipes, audit) : List.of();
    }

    @PostConstruct
    void report() {
        if (refusal != null) {
            log.warn(
                    "app.assistant.desktop.agent-url is set but the desktop tools are not offered: {}",
                    refusal);
        } else if (enabled) {
            log.info(
                    "Desktop tools on: {} local_desktop_* tools via the desktop-agent sidecar",
                    tools.size());
        }
    }

    private static List<ToolCallback> callbacksOn(
            DesktopAgentClient client,
            boolean approvalRequired,
            ObjectProvider<DesktopRecipeService> recipes,
            ObjectProvider<AuditLogService> audit) {
        List<ToolCallback> callbacks =
                new ArrayList<>(
                        java.util.Arrays.stream(DesktopToolCallback.Action.values())
                                .map(
                                        a ->
                                                (ToolCallback)
                                                        new DesktopToolCallback(
                                                                client, a, approvalRequired, audit))
                                .toList());
        DesktopRecipeService recipeService = recipes == null ? null : recipes.getIfAvailable();
        if (recipeService != null && recipeService.isEnabled()) {
            callbacks.add(new RunDesktopRecipeToolCallback(recipeService, approvalRequired));
        }
        return List.copyOf(callbacks);
    }

    /**
     * Whether the {@code local_desktop_*} tools are on - single-user install AND the sidecar
     * configured. Other desktop surfaces (the monitor controller) key their own gate off this so
     * "desktop enabled" has exactly one source of truth.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * The shared sidecar client, or null when {@link #isEnabled()} is false. Package-private:
     * {@link DesktopMonitorController} streams frames through the same client rather than opening a
     * second SSRF-pinned connection pool to the laptop agent.
     */
    DesktopAgentClient client() {
        return client;
    }

    @Override
    public List<ToolCallback> rawTools() {
        return tools;
    }
}
