package com.agenticai.core.sdlc.harness;

import java.util.regex.Pattern;

/**
 * Git-backed checkpoint/rollback over a workspace reached through a {@link ShellRunner}.
 * Every checkpoint is a real commit, so rollback is exact and the history is
 * inspectable. Inputs interpolated into shell commands are restricted: SHAs must
 * be hex, labels are reduced to a safe character set.
 */
public class GitWorkspace {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern UNSAFE_LABEL = Pattern.compile("[^A-Za-z0-9 _.\\-]");
    private static final String GIT = "git -c user.name=vegaduta-harness -c user.email=harness@invalid ";
    private static final int GIT_TIMEOUT_SECONDS = 60;

    private final ShellRunner shell;

    public GitWorkspace(ShellRunner shell) {
        this.shell = shell;
    }

    /** Ensures the workspace is a git repository. */
    public void ensureRepository() {
        ShellRunner.ShellResult probe = shell.run("git rev-parse --is-inside-work-tree", GIT_TIMEOUT_SECONDS);
        if (!probe.ok()) {
            require(shell.run("git init -q", GIT_TIMEOUT_SECONDS), "git init");
        }
    }

    /** Commits everything (allowing an empty commit) and returns the new HEAD sha. */
    public String checkpoint(String label) {
        String safe = sanitizeLabel(label);
        require(shell.run("git add -A", GIT_TIMEOUT_SECONDS), "git add");
        require(shell.run(GIT + "commit -q --allow-empty --no-verify -m '" + safe + "'", GIT_TIMEOUT_SECONDS),
            "git commit");
        ShellRunner.ShellResult head = shell.run("git rev-parse HEAD", GIT_TIMEOUT_SECONDS);
        require(head, "git rev-parse");
        String sha = head.output().trim();
        if (!SHA.matcher(sha).matches()) {
            throw new IllegalStateException("Unexpected HEAD from git: " + sha);
        }
        return sha;
    }

    /** Restores tracked files and removes untracked ones, returning the tree to {@code sha}. */
    public void rollbackTo(String sha) {
        requireSha(sha);
        require(shell.run("git reset -q --hard " + sha, GIT_TIMEOUT_SECONDS), "git reset");
        require(shell.run("git clean -fdq", GIT_TIMEOUT_SECONDS), "git clean");
    }

    /** Unified diff from {@code baseSha} to the current HEAD. */
    public String diffSince(String baseSha) {
        requireSha(baseSha);
        ShellRunner.ShellResult r = shell.run("git diff " + baseSha + " HEAD", GIT_TIMEOUT_SECONDS);
        require(r, "git diff");
        return r.output();
    }

    static String sanitizeLabel(String label) {
        String s = label == null ? "" : UNSAFE_LABEL.matcher(label).replaceAll("");
        s = s.strip();
        if (s.isEmpty()) {
            s = "checkpoint";
        }
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static void requireSha(String sha) {
        if (sha == null || !SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("Not a commit sha: " + sha);
        }
    }

    private static void require(ShellRunner.ShellResult r, String what) {
        if (!r.ok()) {
            throw new IllegalStateException(what + " failed (exit " + r.exitCode() + "): " + r.output());
        }
    }
}
