package com.agenticai.core.agents.tools.localshell;

import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxException;
import com.agenticai.core.guardrails.ToolConfirmationDescriber;
import com.agenticai.core.json.JsonMappers;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Assistant's terminal, as two tools over one sandboxed runner:
 *
 * <ul>
 *   <li>{@value #READ_NAME}: read-only inspection ({@link ShellCommandPolicy#notReadOnly}); runs
 *       without asking, and anything else is refused with a pointer to the other tool.
 *   <li>{@value #RUN_NAME}: any command. It is classified irreversible and listed in {@code
 *       AgentToolGuard.ALWAYS_CONFIRM_TOOLS}, so the tool guard parks every call for the person's
 *       approval (the confirmation card shows the exact command, via {@link
 *       ToolConfirmationDescriber}) and replays it after they approve, even for a fully autonomous
 *       agent - UNLESS the operator sets {@code app.assistant.local-shell.approval-required=false}
 *       (the personal profile's default), which lifts the pin and lets the call run inside the
 *       sandbox without asking.
 * </ul>
 *
 * Both run inside the OS sandbox ({@link SandboxedShellRunner}); the policy here only decides what
 * needs a human, the sandbox is the boundary.
 */
public final class LocalShellToolCallback implements ToolCallback, ToolConfirmationDescriber {

    public static final String READ_NAME = "local_shell_read";
    public static final String RUN_NAME = "local_shell";

    private static final Logger log = LoggerFactory.getLogger(LocalShellToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int SUMMARY_CHARS = 600;

    private final ShellRunner runner;
    private final boolean readOnly;
    private final int defaultTimeoutSeconds;
    private final int maxTimeoutSeconds;
    private final boolean approvalRequired;

    public LocalShellToolCallback(
            ShellRunner runner,
            boolean readOnly,
            int defaultTimeoutSeconds,
            int maxTimeoutSeconds) {
        this(runner, readOnly, defaultTimeoutSeconds, maxTimeoutSeconds, true);
    }

    public LocalShellToolCallback(
            ShellRunner runner,
            boolean readOnly,
            int defaultTimeoutSeconds,
            int maxTimeoutSeconds,
            boolean approvalRequired) {
        this.runner = runner;
        this.readOnly = readOnly;
        this.defaultTimeoutSeconds = Math.max(1, defaultTimeoutSeconds);
        this.maxTimeoutSeconds = Math.max(this.defaultTimeoutSeconds, maxTimeoutSeconds);
        this.approvalRequired = approvalRequired;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String where =
                runner.environmentNote()
                        + " stdout and stderr come back together with the exit code.";
        String description =
                readOnly
                        ? "Runs a read-only inspection command without asking: ls, cat, head, tail, wc, grep, find, "
                                + "sort, cut, diff, jq, git status/log/diff/show, date, uname, df, du, ps, which, and "
                                + "pipes between them. No redirects, no $( ) or backticks, no ; && or &. Anything "
                                + "else is refused: use local_shell for that. "
                                + where
                        : "Runs any shell command (build, script, file processing, anything) and returns its "
                                + "output. "
                                + (approvalRequired
                                        ? "EVERY call is shown to the user for approval before it runs, so say in "
                                                + "plain words what the command does first, and prefer local_shell_read for looking "
                                                + "around. "
                                        : "On this install the calls run without waiting for approval (the owner "
                                                + "trusted the sandboxed terminal), so still say in plain words what "
                                                + "the command does first, and prefer local_shell_read for looking around. ")
                                + where;
        return ToolDefinition.builder()
                .name(readOnly ? READ_NAME : RUN_NAME)
                .description(description)
                .inputSchema(
                        """
                        {
                            "type": "object",
                            "properties": {
                                "command": {"type": "string", "description": "The shell command line (run by /bin/sh -c)"},
                                "folder": {"type": "string", "description": "Optional folder to run in, relative to the workspace (the folder the person attached); the command starts there, so do not cd into it. Default: the workspace itself"},
                                "background": {"type": "boolean", "description": "Set true for a dev server or any long-running process (npm run dev, python -m http.server, ./mvnw spring-boot:run). It keeps running after this call and its address is returned as preview_port; the person sees it in the console's Preview pane. Read the port from the PORT environment variable and listen on 0.0.0.0, for example `PORT=$PORT npm run dev -- --host 0.0.0.0`. Do not combine with & or nohup."},
                                "timeout_seconds": {"type": "integer", "description": "Optional time limit; the command is stopped when it runs out (capped by the owner's setting)"}
                            },
                            "required": ["command"]
                        }
                        """)
                .build();
    }

    @Override
    public String describeForConfirmation(String toolInput) {
        String command = commandOf(toolInput);
        if (command == null) {
            return null;
        }
        String shown =
                command.length() > SUMMARY_CHARS
                        ? command.substring(0, SUMMARY_CHARS) + "..."
                        : command;
        String folder = folderOf(toolInput);
        String where = folder == null ? runner.workspace() : runner.workspace() + "/" + folder;
        return "Run this command (sandboxed; it can only write inside "
                + runner.workspace()
                + ") in "
                + where
                + ":\n"
                + shown;
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args = MAPPER.readTree(toolInput == null ? "{}" : toolInput);
            String command = args.path("command").asString("");
            Optional<String> refusal = ShellCommandPolicy.refusal(command);
            if (refusal.isPresent()) {
                return error(refusal.get());
            }
            if (readOnly) {
                Optional<String> notReadOnly = ShellCommandPolicy.notReadOnly(command);
                if (notReadOnly.isPresent()) {
                    return error(
                            "Not run: "
                                    + notReadOnly.get()
                                    + ". Use local_shell for this"
                                    + (approvalRequired
                                            ? "; the user is asked before it runs."
                                            : "."));
                }
            }
            if (args.path("background").asBoolean(false) && !readOnly) {
                String bgFolder = args.path("folder").asString("");
                ShellRunner.Server server =
                        runner.runBackground(withoutRedundantCd(command, bgFolder), bgFolder);
                ObjectNode bg = MAPPER.createObjectNode();
                bg.put("running", server.running());
                bg.put("output", server.output());
                if (server.running()) {
                    bg.put("preview_port", server.port());
                    bg.put(
                            "note",
                            "The server keeps running. Tell the person it is in the Preview pane (top right of the Assistant).");
                } else {
                    bg.put("exit_code", server.exitCode() == null ? -1 : server.exitCode());
                    bg.put(
                            "note",
                            "The server stopped on its own; read the output, fix it and start it again.");
                }
                return MAPPER.writeValueAsString(bg);
            }
            int timeout = args.path("timeout_seconds").asInt(defaultTimeoutSeconds);
            timeout = Math.min(Math.max(timeout, 1), maxTimeoutSeconds);
            String folder = args.path("folder").asString("");
            command = withoutRedundantCd(command, folder);
            ShellRunner.Result result = runner.run(command, folder, Duration.ofSeconds(timeout));
            ObjectNode out = MAPPER.createObjectNode();
            out.put("exit_code", result.exitCode());
            out.put("output", result.output());
            out.put("truncated", result.truncated());
            out.put("timed_out", result.timedOut());
            out.put("workspace", runner.workspace());
            if (!folder.isBlank()) {
                out.put("folder", folder);
            }
            if (result.timedOut()) {
                out.put(
                        "note",
                        "Stopped after "
                                + timeout
                                + " s. Raise timeout_seconds or run it in smaller steps.");
            }
            return MAPPER.writeValueAsString(out);
        } catch (StdioSandboxException e) {
            return error(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("The command was interrupted.");
        } catch (IOException e) {
            log.warn("{} could not run a command", readOnly ? READ_NAME : RUN_NAME, e);
            return error("The command could not be started on this computer.");
        } catch (RuntimeException e) {
            log.warn("{} failed", readOnly ? READ_NAME : RUN_NAME, e);
            return error("The terminal failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Drops a leading {@code cd <folder> &&} (or {@code ;}) when {@code <folder>} is the folder the
     * command already starts in. Small models add it out of habit; run inside that folder it fails
     * ("can't cd to demo") and costs the person another approval.
     */
    static String withoutRedundantCd(String command, String folder) {
        if (folder == null || folder.isBlank() || command == null) {
            return command;
        }
        String f = java.util.regex.Pattern.quote(folder.strip().replaceAll("/+$", ""));
        return command.replaceFirst(
                "^\\s*cd\\s+(?:\\./)?([\"']?)" + f + "/?\\1\\s*(?:&&|;)\\s*", "");
    }

    private static String folderOf(String toolInput) {
        try {
            JsonNode node = MAPPER.readTree(toolInput == null ? "{}" : toolInput).path("folder");
            return node.isString() && !node.asString().isBlank() ? node.asString() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String commandOf(String toolInput) {
        try {
            JsonNode node = MAPPER.readTree(toolInput == null ? "{}" : toolInput).path("command");
            return node.isString() && !node.asString().isBlank() ? node.asString() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String error(String message) {
        try {
            return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("error", message));
        } catch (IOException e) {
            return "{\"error\":\"error\"}";
        }
    }
}
