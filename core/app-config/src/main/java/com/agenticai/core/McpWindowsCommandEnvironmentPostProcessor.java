package com.agenticai.core;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Makes the static, YAML-wired MCP stdio servers (application-mcp.yml's {@code
 * spring.ai.mcp.client.stdio.connections}) actually spawn on Windows, before any bean exists - the
 * static counterpart to {@code DynamicMcpConnectionManager.normalizeCommandForWindows()}
 * (core/agents), which already does the identical {@code npx -> npx.cmd} rewrite but only for the
 * DB-registered dynamic path it constructs itself.
 *
 * <p><b>The bug this closes.</b> Every static connection launches with {@code command:
 * ${MCP_<name>_COMMAND:npx}}. On Windows npm installs the shim as {@code npx.cmd}, and Java's
 * {@code ProcessBuilder} calls Windows' {@code CreateProcess} directly, which - unlike {@code
 * cmd.exe} - never consults {@code PATHEXT} for an extensionless command name, so a literal {@code
 * "npx"} fails to spawn with {@code CreateProcess error=2, The system cannot find the file
 * specified}. That failure never surfaces cleanly: the MCP client's {@code initialize()}/{@code
 * listTools()} just never gets a reply, blocks for application-mcp.yml's full {@code
 * request-timeout: 60s}, then throws a {@code TimeoutException}. {@code
 * AgentToolProvider#resolveStaticMcpTools} catches that and degrades to "no MCP tools this turn"
 * (fail-open, so nothing crashes) - but every cache-cold tool discovery pays a 60-second stall
 * first, and MCP tools never work at all on Windows.
 *
 * <p>The intended override was the {@code MCP_<name>_COMMAND=npx.cmd} env var, but nothing in this
 * repo sets it: {@code .env.example} ships the defaults as plain {@code npx} behind a hand-edit
 * comment, and there is no launch script (the {@code run-core.ps1} referenced in {@code
 * DynamicMcpConnectionManager}'s javadoc does not exist here). So on a plain {@code mvn
 * spring-boot:run} the inline {@code :npx} default is what every connection actually got.
 *
 * <p><b>Why an {@link EnvironmentPostProcessor}.</b> Spring AI's {@code McpClientAutoConfiguration}
 * reads these commands straight from the {@code Environment} and builds the {@code
 * ServerParameters} itself, so there is no bean-level seam where {@code normalizeCommandForWindows}
 * could be applied to the static path - the value has to be correct in the environment before that
 * auto-config runs. This is the same reason and mechanism as {@link
 * TimeZoneEnvironmentPostProcessor} (registered right beside this one in {@code
 * META-INF/spring.factories}): the post-processor runs during {@code prepareEnvironment()}, before
 * any bean, for every entry point ({@code java -jar}, {@code mvn spring-boot:run},
 * {@code @SpringBootTest}) alike.
 *
 * <p><b>Precedence is deliberate.</b> The defaults are contributed via {@code addLast}, i.e. the
 * lowest-priority property source. A real {@code MCP_FILESYSTEM_COMMAND} in the OS environment or a
 * {@code -D} override still wins (those sources rank higher), so an operator who deliberately
 * points a connection at some other runtime or an absolute path is never overridden - exactly the
 * "don't rewrite what someone deliberately set" restraint {@code normalizeCommandForWindows}
 * already applies on the dynamic side. Off Windows this post-processor contributes nothing, so
 * Linux/Mac/Docker keep the plain {@code npx} default untouched.
 */
public class McpWindowsCommandEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "mcpWindowsCommandDefaults";
    private static final String WINDOWS_NPX = "npx.cmd";

    /**
     * The env-var names application-mcp.yml's {@code ${MCP_<name>_COMMAND:npx}} placeholders
     * resolve against - one per static stdio connection. Kept in lockstep with that file: a new
     * static npx-launched connection there needs its command key added here to spawn on Windows.
     */
    private static final String[] MCP_COMMAND_KEYS = {
        "MCP_FILESYSTEM_COMMAND",
        "MCP_MEMORY_COMMAND",
        "MCP_SEQUENTIAL_THINKING_COMMAND",
        "MCP_PUPPETEER_COMMAND",
        "MCP_PLAYWRIGHT_VISION_COMMAND",
    };

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        boolean windows =
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win");
        if (!windows) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (String key : MCP_COMMAND_KEYS) {
            defaults.put(key, WINDOWS_NPX);
        }
        // addLast: lowest precedence, so a real OS env var / -D override still
        // wins and only the inline ":npx" fallback is superseded.
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }
}
