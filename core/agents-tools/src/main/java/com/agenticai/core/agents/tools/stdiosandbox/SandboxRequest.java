package com.agenticai.core.agents.tools.stdiosandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What to launch: the REAL command/args/env that {@code McpStdioCommandGuard} already approved.
 *
 * @param serverName registration name - used for the network allowlist and log lines only; it is
 *     never placed in a profile, argument or path
 * @param dataDir the one directory the child may write to (and, in container mode, the only
 *     mount); must exist
 * @param networkAccess whether the child may open network connections
 */
public record SandboxRequest(
        String serverName,
        String command,
        List<String> args,
        Map<String, String> env,
        Path dataDir,
        boolean networkAccess) {

    public SandboxRequest {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
