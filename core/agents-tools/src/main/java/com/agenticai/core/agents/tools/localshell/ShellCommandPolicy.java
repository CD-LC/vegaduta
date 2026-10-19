package com.agenticai.core.agents.tools.localshell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the Assistant's two shell tools accept. Two small, pure checks:
 *
 * <ul>
 *   <li>{@link #refusal}: commands that are refused outright, by either tool, even when a person
 *       would approve them (privilege escalation, power control, a fork bomb, wiping a root). The
 *       OS sandbox already makes most of these harmless; this keeps the approval card from ever
 *       offering them.
 *   <li>{@link #notReadOnly}: whether a command may run WITHOUT asking ({@code local_shell_read}).
 *       Deliberately a small allow-list over a tiny shell grammar - a single pipeline of known
 *       inspection commands with no redirects, substitution, chaining or background jobs - and
 *       "unsure" always means "not read-only", which sends the model to the tool that asks.
 * </ul>
 *
 * Neither check is the security boundary: the sandbox is (read-only filesystem, credential folders
 * hidden, no network). This only decides which calls need a human.
 */
public final class ShellCommandPolicy {

    private ShellCommandPolicy() {}

    private static final List<Pattern> REFUSED =
            List.of(
                    Pattern.compile("(^|[\\s;&|(])(sudo|doas|su|pkexec)(\\s|$)"),
                    Pattern.compile("(^|[\\s;&|(])(shutdown|reboot|halt|poweroff|launchctl|systemctl)(\\s|$)"),
                    Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{"),
                    Pattern.compile("(^|[\\s;&|(])mkfs(\\.|\\s|$)"),
                    Pattern.compile("\\bdd\\b[^;&|]*\\bof=/dev/"),
                    Pattern.compile("(^|[\\s;&|(])rm\\s+(-[a-zA-Z-]+\\s+)*(/|~|\\$home|\\$\\{home}|/\\*|~/\\*)\\s*($|[;&|])"));

    /** A user-safe reason the command is refused for every caller, or empty when it is acceptable. */
    public static Optional<String> refusal(String command) {
        if (command == null || command.isBlank()) {
            return Optional.of("command is required");
        }
        String c = command.toLowerCase(Locale.ROOT);
        for (Pattern p : REFUSED) {
            if (p.matcher(c).find()) {
                return Optional.of(
                        "This command is not allowed (privilege escalation, power control or a destructive wipe).");
            }
        }
        return Optional.empty();
    }

    /** Commands a read-only call may start a pipeline segment with. */
    static final Set<String> READ_ONLY_COMMANDS =
            Set.of(
                    "ls", "cat", "head", "tail", "wc", "pwd", "echo", "date", "whoami", "uname", "df", "du", "ps",
                    "which", "file", "stat", "grep", "egrep", "fgrep", "sort", "cut", "tr", "basename", "dirname",
                    "realpath", "tree", "find", "git", "id", "hostname", "uptime", "diff", "jq", "shasum",
                    "sha256sum", "md5", "md5sum");

    private static final Set<String> GIT_READ_ONLY =
            Set.of("status", "log", "diff", "show", "rev-parse", "ls-files", "blame", "describe", "branch", "remote");
    private static final Set<String> FIND_WRITING =
            Set.of("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls");

    /**
     * Empty when {@code command} is a single pipeline of allow-listed inspection commands; otherwise a
     * short reason the model can act on ("use local_shell and it will be shown to the user").
     */
    public static Optional<String> notReadOnly(String command) {
        if (command == null || command.isBlank()) {
            return Optional.of("command is required");
        }
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean hasToken = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else if (quote == '"' && (ch == '$' || ch == '`' || ch == '\\')) {
                    return Optional.of("substitution and escapes are not allowed in a read-only command");
                } else {
                    token.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '\'', '"' -> {
                    quote = ch;
                    hasToken = true;
                }
                case ' ', '\t' -> {
                    if (hasToken) {
                        current.add(token.toString());
                        token.setLength(0);
                        hasToken = false;
                    }
                }
                case '|' -> {
                    if (hasToken) {
                        current.add(token.toString());
                        token.setLength(0);
                        hasToken = false;
                    }
                    if (current.isEmpty()) {
                        return Optional.of("empty pipeline segment");
                    }
                    segments.add(current);
                    current = new ArrayList<>();
                }
                case '>', '<', '`', '$', ';', '&', '\n', '\r', '\\', '(', ')', '{', '}' -> {
                    return Optional.of("redirects, substitution, chaining and background jobs are not read-only");
                }
                default -> {
                    token.append(ch);
                    hasToken = true;
                }
            }
        }
        if (quote != 0) {
            return Optional.of("unbalanced quote");
        }
        if (hasToken) {
            current.add(token.toString());
        }
        if (current.isEmpty()) {
            return Optional.of("empty pipeline segment");
        }
        segments.add(current);
        for (List<String> segment : segments) {
            Optional<String> reason = segmentProblem(segment);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> segmentProblem(List<String> tokens) {
        String name = tokens.get(0);
        if (!READ_ONLY_COMMANDS.contains(name)) {
            return Optional.of("'" + name + "' is not a read-only inspection command");
        }
        List<String> args = tokens.subList(1, tokens.size());
        switch (name) {
            case "find" -> {
                for (String a : args) {
                    if (FIND_WRITING.contains(a)) {
                        return Optional.of("find " + a + " can change files");
                    }
                }
            }
            case "sort" -> {
                for (String a : args) {
                    if (a.equals("-o") || a.startsWith("--output") || a.startsWith("-o")) {
                        return Optional.of("sort -o writes a file");
                    }
                }
            }
            case "tree" -> {
                for (String a : args) {
                    if (a.equals("-o") || a.startsWith("--output")) {
                        return Optional.of("tree -o writes a file");
                    }
                }
            }
            case "git" -> {
                if (args.isEmpty() || !GIT_READ_ONLY.contains(args.get(0))) {
                    return Optional.of("only git status, log, diff, show and similar reads are allowed");
                }
                for (String a : args) {
                    if (a.equals("-c") || a.startsWith("--output") || a.equals("--ext-diff") || a.equals("--exec")) {
                        return Optional.of("git " + a + " is not read-only");
                    }
                }
                if (args.get(0).equals("branch") || args.get(0).equals("remote")) {
                    for (String a : args.subList(1, args.size())) {
                        boolean flag = a.startsWith("-");
                        if (!flag || a.equals("-d") || a.equals("-D") || a.equals("-m") || a.equals("-M") || a.equals("--delete")) {
                            return Optional.of("git " + args.get(0) + " with arguments can change the repository");
                        }
                    }
                }
            }
            default -> {
                // plain inspection commands: nothing extra to check
            }
        }
        return Optional.empty();
    }
}
