package com.agenticai.core.sdlc.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one place a model- or user-supplied file path is turned into something the harness may write
 * to. A path is accepted only if it is a plain relative POSIX path made of conservative segments:
 * no {@code ..}, no absolute or drive-lettered form, no backslashes or control characters, no
 * {@code .git} segment (the checkpoint history must not be editable), no segment starting with
 * {@code -} (so it can never be read as an option by a command that forgot {@code --}).
 *
 * <p>This is validation of the <em>name</em>. Symlink escapes through a directory that already
 * exists in the workspace are a separate hazard; {@link WorkspaceFiles} adds a resolved-path check
 * inside the sandbox.
 */
public final class WorkspacePaths {

    public static final int MAX_PATH_CHARS = 300;
    public static final int MAX_DEPTH = 24;

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._@+=,~\\-]{1,100}");

    private WorkspacePaths() {
    }

    /**
     * @return the normalized relative path ({@code ./a//b} becomes {@code a/b})
     * @throws IllegalArgumentException when the path could escape or touch protected state
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A file path is required.");
        }
        if (raw.length() > MAX_PATH_CHARS) {
            throw new IllegalArgumentException("The file path is too long.");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                throw new IllegalArgumentException("The file path contains a forbidden character.");
            }
        }
        if (raw.startsWith("/") || raw.startsWith("~") || raw.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Only paths inside the workspace are allowed.");
        }
        List<String> kept = new ArrayList<>();
        for (String segment : raw.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Path traversal is not allowed.");
            }
            if (segment.equalsIgnoreCase(".git") || isGitMetadataName(segment)) {
                throw new IllegalArgumentException("Git metadata cannot be edited.");
            }
            if (segment.startsWith("-")) {
                throw new IllegalArgumentException("A path segment cannot start with '-'.");
            }
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("The file path contains an unsupported character.");
            }
            kept.add(segment);
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("A file path is required.");
        }
        if (kept.size() > MAX_DEPTH) {
            throw new IllegalArgumentException("The file path is nested too deeply.");
        }
        return String.join("/", kept);
    }

    /** {@code .gitmodules}/{@code .gitattributes}/{@code .gitconfig}-style names can change git behaviour. */
    private static boolean isGitMetadataName(String segment) {
        String s = segment.toLowerCase(java.util.Locale.ROOT);
        return s.equals(".gitmodules") || s.equals(".gitattributes") || s.equals(".gitconfig");
    }
}
