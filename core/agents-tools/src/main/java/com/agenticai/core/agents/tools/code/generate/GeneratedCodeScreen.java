package com.agenticai.core.agents.tools.code.generate;

import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import com.agenticai.core.agents.tools.code.dto.SandboxValidateResponse.SandboxValidationError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allowlist pre-screen for model-written custom tool source, run BEFORE the tool-sandbox
 * microservice sees it.
 *
 * <p>The authoritative gate is the sandbox: a bytecode constant-pool verifier for Java and an AST
 * check for Python ({@code services/tool-sandbox/app/policy.py}), backed by OS containment. This
 * screen is a second, independent, deliberately stricter-at-the-edges layer inside core, for two
 * reasons: the draft comes from an LLM whose prompt includes tenant free text (so it is
 * attacker-influenced input, not a trusted author), and a screen that fails closed means a
 * misconfigured or older sandbox service is never the only thing between that text and "valid".
 * It can only ever turn a draft INVALID - it never marks anything valid, and a pass still goes to
 * the sandbox for the real verdict.
 *
 * <p>Allowlist first: every import and every fully-qualified {@code java.*} reference must sit in
 * the permitted packages (Java) / permitted modules (Python); the few simple names that need no
 * import ({@code java.lang}) are then checked by name. The package and module lists mirror the
 * sandbox's and the prompt's contract; if they drift the sandbox still decides - the cost of drift
 * is a false rejection here, never a false acceptance.
 */
final class GeneratedCodeScreen {

    // ---- Java -------------------------------------------------------------------------------

    private static final List<String> JAVA_ALLOWED_PREFIXES =
            List.of("java.lang.", "java.util.", "java.math.", "java.time.", "java.text.", "vega.");

    private static final List<String> JAVA_DENIED_PREFIXES =
            List.of(
                    "java.lang.reflect.",
                    "java.lang.invoke.",
                    "java.lang.ref.",
                    "java.lang.module.",
                    "java.lang.instrument.",
                    "java.lang.management.",
                    "java.lang.foreign.",
                    "java.lang.constant.",
                    "java.lang.classfile.",
                    "java.lang.runtime.",
                    "java.util.concurrent.",
                    "java.util.logging.",
                    "java.util.zip.",
                    "java.util.jar.",
                    "java.util.prefs.",
                    "java.util.spi.");

    /** Top-level classes denied even inside the allowed packages. */
    private static final Set<String> JAVA_DENIED_CLASSES =
            Set.of(
                    "System",
                    "Runtime",
                    "Process",
                    "ProcessBuilder",
                    "ProcessHandle",
                    "Thread",
                    "ThreadGroup",
                    "ThreadLocal",
                    "InheritableThreadLocal",
                    "Class",
                    "ClassLoader",
                    "ClassValue",
                    "Compiler",
                    "SecurityManager",
                    "Module",
                    "ModuleLayer",
                    "Package",
                    "StackWalker",
                    "ServiceLoader",
                    "Timer",
                    "TimerTask",
                    "Scanner",
                    "Formatter",
                    "ResourceBundle",
                    "ListResourceBundle",
                    "PropertyResourceBundle");

    private static final Pattern JAVA_IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.$]+)(\\.\\*)?\\s*;");
    private static final Pattern JAVA_QUALIFIED =
            Pattern.compile(
                    "(?<![\\w.$])((?:java|javax|jakarta|jdk|sun)\\.[\\w.$]+)");
    private static final Pattern JAVA_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");
    private static final Pattern JAVA_NATIVE = Pattern.compile("\\bnative\\b");
    private static final Pattern JAVA_DECLARED_TYPE =
            Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern JAVA_MEMBER_DENIED =
            Pattern.compile(
                    "\\b(?:Boolean\\s*\\.\\s*getBoolean|Integer\\s*\\.\\s*getInteger|Long\\s*\\.\\s*getLong)\\b"
                            + "|\\.\\s*(?:parallel|parallelStream|parallelSort|parallelPrefix|parallelSetAll)\\s*\\(");

    // ---- Python -----------------------------------------------------------------------------

    private static final Set<String> PYTHON_ALLOWED_MODULES =
            Set.of(
                    "base64", "binascii", "bisect", "calendar", "cmath", "collections", "copy",
                    "dataclasses", "datetime", "decimal", "difflib", "enum", "fractions",
                    "functools", "hashlib", "heapq", "hmac", "html", "itertools", "json", "math",
                    "numbers", "operator", "random", "re", "statistics", "string", "textwrap",
                    "time", "typing", "unicodedata", "uuid", "zoneinfo");

    private static final Pattern PYTHON_IMPORT =
            Pattern.compile("(?m)^[ \\t]*import[ \\t]+([^\\n#;]+)");
    private static final Pattern PYTHON_FROM =
            Pattern.compile("(?m)^[ \\t]*from[ \\t]+([.\\w]+)[ \\t]+import\\b");
    private static final Pattern PYTHON_DENIED_NAME =
            Pattern.compile(
                    "(?<![.\\w])(?:__import__|eval|exec|compile|open|getattr|setattr|delattr|globals"
                            + "|locals|vars|dir|breakpoint|exit|quit|memoryview|super|object)\\b");
    private static final Pattern PYTHON_PRIVATE_ATTR = Pattern.compile("\\.\\s*_");

    private GeneratedCodeScreen() {}

    /** Errors found in {@code source}, empty when it passes the screen. Never throws. */
    static List<SandboxValidationError> screen(CustomCodeToolLanguage language, String source) {
        if (source == null || source.isBlank()) {
            return List.of(new SandboxValidationError(null, "The generated program is empty."));
        }
        List<SandboxValidationError> out = new ArrayList<>();
        if (language == CustomCodeToolLanguage.JAVA) {
            screenJava(source, out);
        } else {
            screenPython(source, out);
        }
        return out;
    }

    // ---- Java -------------------------------------------------------------------------------

    private static void screenJava(String source, List<SandboxValidationError> out) {
        String code = stripJava(source);
        Set<String> declared = new HashSet<>();
        Matcher d = JAVA_DECLARED_TYPE.matcher(code);
        while (d.find()) {
            declared.add(d.group(1));
        }

        Matcher pkg = JAVA_PACKAGE.matcher(code);
        if (pkg.find()) {
            out.add(error(code, pkg.start(), "A custom tool must be in the default package - remove the `package` statement."));
        }
        Matcher nat = JAVA_NATIVE.matcher(code);
        if (nat.find()) {
            out.add(error(code, nat.start(), "`native` methods are not permitted in a custom tool."));
        }

        Matcher imp = JAVA_IMPORT.matcher(code);
        while (imp.find()) {
            String target = imp.group(2);
            if (!javaNameAllowed(target)) {
                out.add(error(code, imp.start(), "Import of `" + target + "` is not permitted in a custom tool."));
            }
        }
        Matcher q = JAVA_QUALIFIED.matcher(code);
        while (q.find()) {
            String name = q.group(1);
            if (!javaNameAllowed(name)) {
                out.add(error(code, q.start(1), "`" + name + "` is not permitted in a custom tool."));
            }
        }
        // Names that need no import (java.lang) - checked as whole identifiers unless the
        // program declares its own type of that name.
        for (String denied : JAVA_DENIED_CLASSES) {
            if (declared.contains(denied)) {
                continue;
            }
            Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(denied) + "(?![\\w$])").matcher(code);
            if (m.find()) {
                out.add(error(code, m.start(), "`" + denied + "` is not available in a custom tool."));
            }
        }
        Matcher mem = JAVA_MEMBER_DENIED.matcher(code);
        if (mem.find()) {
            out.add(error(code, mem.start(), "`" + mem.group().replaceAll("\\s+", "") + "` is not permitted in a custom tool (system properties / implicit threads)."));
        }
    }

    /** True when a dotted name sits in the allowlist and not on a denied package/class. */
    private static boolean javaNameAllowed(String dotted) {
        if (JAVA_ALLOWED_PREFIXES.stream().noneMatch(dotted::startsWith)) {
            return false;
        }
        for (String p : JAVA_DENIED_PREFIXES) {
            if (dotted.startsWith(p)) {
                return false;
            }
        }
        for (String part : dotted.split("[.$]")) {
            if (JAVA_DENIED_CLASSES.contains(part)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Blanks comments, string/char literals and text blocks (newlines kept, so offsets still map to
     * lines) so that words inside them are never mistaken for code.
     */
    static String stripJava(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            char next = i + 1 < n ? s.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    b.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                b.append("  ");
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    b.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    b.append("  ");
                    i += 2;
                }
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                b.append("   ");
                i += 3;
                while (i < n && !s.startsWith("\"\"\"", i)) {
                    if (s.charAt(i) == '\\' && i + 1 < n) {
                        b.append("  ");
                        i += 2;
                        continue;
                    }
                    b.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    b.append("   ");
                    i += 3;
                }
            } else if (c == '"' || c == '\'') {
                b.append(c);
                i++;
                while (i < n && s.charAt(i) != c && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\' && i + 1 < n) {
                        b.append("  ");
                        i += 2;
                        continue;
                    }
                    b.append(' ');
                    i++;
                }
                if (i < n && s.charAt(i) == c) {
                    b.append(c);
                    i++;
                }
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    // ---- Python -----------------------------------------------------------------------------

    private static void screenPython(String source, List<SandboxValidationError> out) {
        String code = stripPython(source);

        Matcher imp = PYTHON_IMPORT.matcher(code);
        while (imp.find()) {
            for (String item : imp.group(1).split(",")) {
                String module = item.strip().split("\\s+as\\s+")[0].strip();
                checkPythonModule(code, imp.start(), module, out);
            }
        }
        Matcher from = PYTHON_FROM.matcher(code);
        while (from.find()) {
            checkPythonModule(code, from.start(), from.group(1), out);
        }
        Matcher name = PYTHON_DENIED_NAME.matcher(code);
        while (name.find()) {
            out.add(error(code, name.start(), "`" + name.group() + "` is not available in a custom tool."));
        }
        Matcher priv = PYTHON_PRIVATE_ATTR.matcher(code);
        if (priv.find()) {
            out.add(error(code, priv.start(), "Attributes starting with `_` (including dunder attributes) are not permitted in a custom tool."));
        }
    }

    private static void checkPythonModule(
            String code, int at, String module, List<SandboxValidationError> out) {
        if (module.isEmpty() || module.startsWith(".")) {
            out.add(error(code, at, "Relative imports are not permitted in a custom tool."));
            return;
        }
        String top = module.split("\\.")[0];
        boolean privatePart = false;
        for (String part : module.split("\\.")) {
            privatePart |= part.startsWith("_");
        }
        if (module.startsWith(".") || privatePart || !PYTHON_ALLOWED_MODULES.contains(top)) {
            out.add(error(code, at, "Import of `" + module + "` is not permitted in a custom tool."));
        }
    }

    /** Blanks comments and string literals (single, triple-quoted); keeps newlines. */
    static String stripPython(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '#') {
                while (i < n && s.charAt(i) != '\n') {
                    b.append(' ');
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                String triple = "" + c + c + c;
                boolean isTriple = s.startsWith(triple, i);
                int len = isTriple ? 3 : 1;
                b.append(" ".repeat(len));
                i += len;
                while (i < n) {
                    if (s.charAt(i) == '\\' && i + 1 < n) {
                        b.append(s.charAt(i + 1) == '\n' ? " \n" : "  ");
                        i += 2;
                        continue;
                    }
                    if (isTriple ? s.startsWith(triple, i) : s.charAt(i) == c || s.charAt(i) == '\n') {
                        break;
                    }
                    b.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n && (isTriple ? s.startsWith(triple, i) : s.charAt(i) == c)) {
                    b.append(" ".repeat(len));
                    i += len;
                }
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    private static SandboxValidationError error(String code, int offset, String message) {
        int line = 1;
        for (int i = 0; i < offset && i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return new SandboxValidationError(line, message);
    }
}
