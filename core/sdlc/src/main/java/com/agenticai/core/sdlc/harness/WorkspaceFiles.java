package com.agenticai.core.sdlc.harness;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * File reads and writes inside the workspace, expressed as fixed shell templates over the sandbox's
 * {@link CodingSandbox#internal()} runner. Every path goes through {@link WorkspacePaths#normalize}
 * (no traversal, no {@code .git}) and, inside the sandbox, through a resolved-path check
 * ({@code readlink -m} must still be under the workspace root), so a symlink that a build step left
 * in the tree cannot redirect a write out of it. Contents travel base64-encoded, so no model text is
 * ever parsed as shell.
 */
public final class WorkspaceFiles {

    /** One write must stay below this (the base64 form has to fit one command-line argument). */
    public static final int MAX_FILE_BYTES = 64 * 1024;

    private static final String CONFINE_PREFIX = "ws=$(pwd -P) && t=$(readlink -m -- ";
    private static final String CONFINE_SUFFIX =
        ") && case \"$t\" in \"$ws\"/*) ;; *) echo 'path escapes the workspace' >&2; exit 98;; esac";

    private WorkspaceFiles() {
    }

    private static String confine(String quotedPath) {
        return CONFINE_PREFIX + quotedPath + CONFINE_SUFFIX;
    }

    public static void write(ShellRunner shell, String path, String content) {
        String p = WorkspacePaths.normalize(path);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("A file edit is larger than " + (MAX_FILE_BYTES / 1024) + " KB.");
        }
        String q = ShellQuote.single(p);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        String command = confine(q)
            + " && mkdir -p -- \"$(dirname -- " + q + ")\""
            + " && d=$(readlink -m -- \"$(dirname -- " + q + ")\") && case \"$d\" in \"$ws\"|\"$ws\"/*) ;; *) exit 98;; esac"
            + " && [ ! -L " + q + " ] && printf '%s' '" + b64 + "' | base64 -d > " + q;
        ShellRunner.ShellResult r = shell.run(command, 60);
        if (!r.ok()) {
            throw new IllegalStateException("Could not write " + p + ": " + AbstractCodingSandbox.tail(r.output()));
        }
    }

    public static void delete(ShellRunner shell, String path) {
        String p = WorkspacePaths.normalize(path);
        String q = ShellQuote.single(p);
        ShellRunner.ShellResult r = shell.run(confine(q) + " && rm -f -- " + q, 60);
        if (!r.ok()) {
            throw new IllegalStateException("Could not delete " + p + ": " + AbstractCodingSandbox.tail(r.output()));
        }
    }

    /** First {@code maxBytes} bytes of the file as text; empty string when it does not exist. */
    public static String read(ShellRunner shell, String path, int maxBytes) {
        String p = WorkspacePaths.normalize(path);
        String q = ShellQuote.single(p);
        ShellRunner.ShellResult r = shell.run(
            confine(q) + " && [ -f " + q + " ] && head -c " + Math.max(1, maxBytes) + " -- " + q, 60);
        return r.ok() ? r.output() : "";
    }

    /** Tracked-or-new file paths, at most {@code max}, one per entry. */
    public static List<String> list(ShellRunner shell, int max) {
        ShellRunner.ShellResult r = shell.run(
            "git ls-files --cached --others --exclude-standard | head -n " + Math.max(1, max), 60);
        if (!r.ok()) {
            return List.of();
        }
        return r.output().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
