package com.agenticai.core.agents.tools.stdiosandbox;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide handle to the active {@link StdioSandboxService}. {@code McpStdioParamsBuilder} is a
 * plain object built inside {@code DynamicMcpConnectionManager}'s constructor (whose signature the
 * architecture baselines freeze), so it cannot take the bean by injection; the service registers
 * itself here on startup instead. Absent (never installed) means "no sandbox", which is today's
 * behaviour. Tests that need a specific service pass it to the builder explicitly.
 */
public final class StdioSandboxRuntime {

    private static final AtomicReference<StdioSandboxService> CURRENT = new AtomicReference<>();

    private StdioSandboxRuntime() {}

    static void install(StdioSandboxService service) {
        CURRENT.set(service);
    }

    static void uninstall(StdioSandboxService service) {
        CURRENT.compareAndSet(service, null);
    }

    /** The installed service, or null when none (sandboxing then never applies). */
    public static StdioSandboxService current() {
        return CURRENT.get();
    }
}
