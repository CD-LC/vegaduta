package com.agenticai.core.agents.tools.stdiosandbox;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Validation and escaping for the few strings that end up inside a sandbox profile or a container
 * flag. Every one of them is refused rather than quoted when it contains something a downstream
 * parser could interpret: nothing here "escapes its way out" of an injection, it rejects.
 */
final class SandboxText {

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SandboxText() {}

    /** A POSIX env var name; anything else (spaces, '=', ',') cannot be forwarded safely. */
    static boolean validEnvName(String name) {
        return name != null && ENV_NAME.matcher(name).matches();
    }

    /**
     * An absolute, normalized path with no control characters. Used before a path goes into a
     * profile or a bind-mount flag.
     */
    static String requirePath(Path path, String what) {
        if (path == null || !path.isAbsolute()) {
            throw new StdioSandboxException("The sandbox " + what + " must be an absolute path.");
        }
        String s = path.normalize().toString();
        requirePlain(s, what);
        return s;
    }

    /** No control characters (NUL, newline, tab, DEL) anywhere. */
    static void requirePlain(String value, String what) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new StdioSandboxException(
                        "The sandbox " + what + " contains a control character.");
            }
        }
    }

    /**
     * A path destined for {@code --mount type=bind,src=...}/{@code -v}: additionally free of the
     * characters those parsers split on.
     */
    static String requireMountPath(Path path, String what) {
        String s = requirePath(path, what);
        if (s.indexOf(',') >= 0 || s.indexOf(':') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\\') >= 0) {
            throw new StdioSandboxException(
                    "The sandbox " + what + " contains a character a container mount cannot take.");
        }
        return s;
    }

    /** SBPL string literal: validated, then {@code \} and {@code "} backslash-escaped. */
    static String sbplString(String value) {
        requirePlain(value, "profile path");
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }
}
