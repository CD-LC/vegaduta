package com.agenticai.core.agents.tools.stdiosandbox;

import java.util.List;
import java.util.Map;

/**
 * The process core must start instead of the bare launcher: {@code command}/{@code args} wrap the
 * real one in the sandbox tool, {@code env} is the complete child environment (core's own is
 * cleared by {@code EnvIsolatedStdioClientTransport}).
 *
 * @param mechanism {@code seatbelt}, {@code bubblewrap}, {@code unshare}, {@code docker} or {@code
 *     podman}
 * @param degraded true when the mechanism does not confine the filesystem (Linux {@code unshare})
 */
public record SandboxLaunch(
        String command,
        List<String> args,
        Map<String, String> env,
        String mechanism,
        boolean degraded) {}
