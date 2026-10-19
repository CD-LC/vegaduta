package com.agenticai.core.sdlc.harness;

/**
 * Runs one shell command inside the coding workspace (an E2B sandbox in
 * production; a fake in unit tests). The harness never spawns processes on the
 * core host itself.
 */
@FunctionalInterface
public interface ShellRunner {

    ShellResult run(String command, int timeoutSeconds);

    /** Exit code plus combined stdout/stderr. */
    record ShellResult(int exitCode, String output) {
        public ShellResult {
            output = output == null ? "" : output;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }
}
