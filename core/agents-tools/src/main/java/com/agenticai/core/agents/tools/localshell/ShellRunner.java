package com.agenticai.core.agents.tools.localshell;

import java.io.IOException;
import java.time.Duration;

/** Runs one shell command and returns what it printed. The production implementation is sandboxed. */
public interface ShellRunner {

    /**
     * @param output stdout and stderr interleaved, cut at the configured size
     * @param truncated the output was longer than the cap
     * @param timedOut the command was stopped for running past the timeout
     */
    record Result(int exitCode, String output, boolean truncated, boolean timedOut) {}

    /** Where the command starts, and the only folder it can write: shown to the model and the user. */
    String workspace();

    /** One sentence for the model on where commands run and what they can reach. */
    default String environmentNote() {
        return "It runs on the computer that hosts this platform, inside an operating-system sandbox: the working "
                + "folder (and ~) is the workspace "
                + workspace()
                + ", the only place it can write; the rest of the disk is read-only by absolute path, "
                + "credential folders (~/.ssh, keychains, browser profiles) are hidden, and there is no "
                + "network unless the owner enabled it."
                + hostOsHint();
    }

    /**
     * macOS ships BSD tools: a small model that writes GNU flags ({@code du --max-depth}, {@code ps --sort}) gets a
     * usage error and retries blind. Name the OS and give the BSD spellings of the commands people ask for.
     */
    static String hostOsHint() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).contains("mac")
                ? " The computer is a Mac: its tools are BSD, so do not use GNU flags (use `du -d 1`, `ps aux`, "
                        + "`ls -lS`, `sed -i ''`, `stat -f`)."
                : "";
    }

    /** A dev server started in the background: {@code port} is where the person's browser can reach it. */
    record Server(int port, boolean running, String output, Integer exitCode) {}

    /** A server the runner keeps alive, as listed in the console. */
    record Preview(int port, String folder, String command, boolean running) {}

    Result run(String command, Duration timeout) throws IOException, InterruptedException;

    /**
     * Runs in {@code folder}, a path relative to {@link #workspace()} (the folder the person attached
     * in the Assistant); blank means the workspace itself. A runner that cannot change folder runs
     * only at the top.
     */
    default Result run(String command, String folder, Duration timeout) throws IOException, InterruptedException {
        if (folder != null && !folder.isBlank()) {
            throw new IOException("This terminal only runs at the top of the workspace.");
        }
        return run(command, timeout);
    }

    /**
     * Starts {@code command} as a long-running dev server in {@code folder} and returns once it has
     * had a moment to print its first lines. Only the workspace-runner can keep a process alive past the
     * call; the OS sandbox cannot.
     */
    default Server runBackground(String command, String folder) throws IOException {
        throw new IOException("This terminal cannot keep a server running in the background.");
    }

    /** The servers kept alive by {@link #runBackground}; empty where there are none. */
    default java.util.List<Preview> previews() throws IOException {
        return java.util.List.of();
    }

    /** Stops the server on {@code port}; false when there was none. */
    default boolean stopPreview(int port) throws IOException {
        return false;
    }
}
