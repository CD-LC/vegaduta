package com.agenticai.core.agents.tools.workspacefiles;

import com.agenticai.core.agentspi.WorkspaceToolSource;
import java.nio.file.Path;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Offers the workspace file tools ({@link WorkspaceFileToolCallback}) next to the Assistant's
 * terminal, under the same conditions: a one-person install with {@code
 * app.assistant.local-shell.enabled=true}, and the projects folder ({@code
 * app.assistant.local-shell.workspace}) actually present in this process. In the container stack it
 * is the folder compose mounts into both core and the workspace-runner.
 */
@Component
public class WorkspaceFileToolSource implements WorkspaceToolSource {

    private final boolean offered;
    private final WorkspaceFiles files;
    private final List<ToolCallback> tools;

    public WorkspaceFileToolSource(
            Environment environment,
            @Value("${app.assistant.local-shell.enabled:false}") boolean enabled,
            @Value("${app.assistant.local-shell.acknowledge-shared-host:false}") boolean acknowledgeSharedHost,
            @Value("${app.assistant.local-shell.workspace:${user.home}/vegaduta-workspace}") String workspace) {
        boolean singleUser = acknowledgeSharedHost || environment.acceptsProfiles(Profiles.of("personal"));
        this.offered = enabled && singleUser;
        this.files = new WorkspaceFiles(Path.of(workspace));
        this.tools =
                List.of(
                        new WorkspaceFileToolCallback(files, WorkspaceFileToolCallback.Kind.LIST, workspace),
                        new WorkspaceFileToolCallback(files, WorkspaceFileToolCallback.Kind.READ, workspace),
                        new WorkspaceFileToolCallback(files, WorkspaceFileToolCallback.Kind.SEARCH, workspace),
                        new WorkspaceFileToolCallback(files, WorkspaceFileToolCallback.Kind.WRITE, workspace),
                        new WorkspaceFileToolCallback(files, WorkspaceFileToolCallback.Kind.EDIT, workspace));
    }

    /** The file tools are offered: switched on, one-person install, projects folder present. */
    boolean offered() {
        return offered && files.available();
    }

    WorkspaceFiles files() {
        return files;
    }

    @Override
    public List<ToolCallback> rawTools() {
        return offered && files.available() ? tools : List.of();
    }
}
