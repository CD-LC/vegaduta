package com.agenticai.core.sdlc.harness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Allowlist for the one command the harness runs repeatedly: the test command. A command is a
 * single runner invocation - {@code mvn}, {@code gradle}, {@code npm}, {@code pytest} or {@code go}
 * (the set is configurable, never widened beyond those five names) - made only of space-separated
 * tokens from a conservative character set. There is no shell syntax in that set: no quote, no
 * {@code ; | & $ ` < > ( ) { }}, no newline, no glob, no redirection, so the validated string cannot
 * chain a second command even if it is interpolated into {@code bash -c}.
 *
 * <p>Sub-commands are pinned per runner (build/test goals only; no {@code exec}, {@code npx},
 * {@code python -c}). Absolute paths and {@code ..} segments are refused. The sandbox, not this
 * class, is the isolation boundary; this class keeps the model/user from asking for anything other
 * than "run the project's tests".
 */
public final class CodingCommandPolicy {

    /** The runners that may ever be allowed; configuration can only narrow this set. */
    public static final List<String> SUPPORTED_RUNNERS = List.of("mvn", "gradle", "npm", "pytest", "go");

    public static final int MAX_COMMAND_CHARS = 300;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_./:=,@+\\-]{1,120}");
    private static final Set<String> MVN_GOALS = Set.of("clean", "compile", "test-compile", "test", "verify", "package");
    private static final Set<String> GRADLE_TASKS = Set.of("clean", "classes", "assemble", "test", "check", "build");
    private static final Set<String> GO_SUBCOMMANDS = Set.of("test", "vet", "build");
    private static final Set<String> NPM_FLAGS = Set.of("-s", "--silent", "--if-present");

    private final Set<String> allowed;

    public CodingCommandPolicy(Collection<String> allowedRunners) {
        Set<String> set = new LinkedHashSet<>();
        if (allowedRunners != null) {
            for (String runner : allowedRunners) {
                if (runner != null && SUPPORTED_RUNNERS.contains(runner.strip().toLowerCase(Locale.ROOT))) {
                    set.add(runner.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.allowed = Set.copyOf(set);
    }

    public static CodingCommandPolicy defaults() {
        return new CodingCommandPolicy(SUPPORTED_RUNNERS);
    }

    public Set<String> allowedRunners() {
        return allowed;
    }

    /**
     * @return the command with tokens re-joined by single spaces
     * @throws IllegalArgumentException when the command is not an allowed runner invocation
     */
    public String validate(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("A test command is required.");
        }
        String trimmed = command.strip();
        if (trimmed.length() > MAX_COMMAND_CHARS) {
            throw new IllegalArgumentException("The test command is too long.");
        }
        List<String> tokens = new ArrayList<>();
        for (String token : trimmed.split(" +", -1)) {
            if (!TOKEN.matcher(token).matches()) {
                throw new IllegalArgumentException(
                    "The test command may only contain plain words, flags and relative paths.");
            }
            tokens.add(token);
        }
        for (String token : tokens) {
            if (token.startsWith("/") || token.startsWith("~")) {
                throw new IllegalArgumentException("Absolute paths are not allowed in the test command.");
            }
            for (String segment : token.split("/", -1)) {
                if (segment.equals("..")) {
                    throw new IllegalArgumentException("Path traversal is not allowed in the test command.");
                }
            }
        }
        String runner = canonicalRunner(tokens.get(0));
        if (runner == null || !allowed.contains(runner)) {
            throw new IllegalArgumentException("Only these test runners are allowed: "
                + String.join(", ", allowed.stream().sorted().toList()) + ".");
        }
        List<String> args = tokens.subList(1, tokens.size());
        switch (runner) {
            case "mvn" -> requireGoals(args, MVN_GOALS, "mvn");
            case "gradle" -> requireGoals(args, GRADLE_TASKS, "gradle");
            case "go" -> requireGo(args);
            case "npm" -> requireNpm(args);
            case "pytest" -> {
                // Anything pytest accepts that is a flag, a value or a relative path (tokens already vetted).
            }
            default -> throw new IllegalArgumentException("Unsupported runner.");
        }
        return String.join(" ", tokens);
    }

    private static String canonicalRunner(String first) {
        return switch (first) {
            case "mvn", "./mvnw" -> "mvn";
            case "gradle", "./gradlew" -> "gradle";
            case "npm" -> "npm";
            case "pytest" -> "pytest";
            case "go" -> "go";
            default -> null;
        };
    }

    private static void requireGoals(List<String> args, Set<String> goals, String runner) {
        boolean sawGoal = false;
        for (String arg : args) {
            if (arg.startsWith("-")) {
                // -f/--file, -s/--settings and gradle's -p/--project-dir point the build elsewhere.
                String flag = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
                if (Set.of("-f", "--file", "-s", "--settings", "-gs", "--global-settings", "-p", "--project-dir",
                    "-b", "--build-file", "-c", "--settings-file", "--init-script", "-I").contains(flag)) {
                    throw new IllegalArgumentException("That " + runner + " option is not allowed.");
                }
                continue;
            }
            if (!goals.contains(arg)) {
                throw new IllegalArgumentException("Only these " + runner + " goals are allowed: "
                    + String.join(", ", goals.stream().sorted().toList()) + ".");
            }
            sawGoal = true;
        }
        if (!sawGoal) {
            throw new IllegalArgumentException("Name a " + runner + " goal such as 'test'.");
        }
    }

    private static void requireGo(List<String> args) {
        if (args.isEmpty() || !GO_SUBCOMMANDS.contains(args.get(0))) {
            throw new IllegalArgumentException("Only 'go test', 'go vet' and 'go build' are allowed.");
        }
    }

    private static void requireNpm(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("Use 'npm test' or 'npm run <script>'.");
        }
        int i;
        if (args.get(0).equals("test") || args.get(0).equals("t")) {
            i = 1;
        } else if (args.get(0).equals("run") && args.size() >= 2 && args.get(1).matches("[A-Za-z0-9:_\\-]{1,60}")) {
            i = 2;
        } else {
            throw new IllegalArgumentException("Use 'npm test' or 'npm run <script>'.");
        }
        for (; i < args.size(); i++) {
            if (!NPM_FLAGS.contains(args.get(i))) {
                throw new IllegalArgumentException("That npm option is not allowed.");
            }
        }
    }
}
