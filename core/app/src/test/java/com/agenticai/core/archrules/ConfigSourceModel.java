package com.agenticai.core.archrules;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

/**
 * What the source tree says about configuration, read from files (no Spring context, no database):
 * every {@code application*.yml} key with its raw value, every property the Java code reads with the
 * default it gives, and the keys the typed {@code @ConfigurationProperties} classes bind. Shared by
 * {@link ConfigurationDriftTest}.
 *
 * <p>A <i>read</i> is one of: an {@code @Value("${key:default}")}; an {@code @ConditionalOnProperty};
 * an {@code environment.getProperty("key"...)}; a field of an {@code @ConfigurationProperties} class
 * (nested helper classes of the same file included, and a {@code record} component); a key of the
 * {@code .properties} resource such a class loads its defaults from. A key that is only composed at run
 * time ({@code prefix + "client-id"}) is invisible here, which is what the allowlist is for.
 */
final class ConfigSourceModel {

    /** A property the code reads. {@code defaultValue} is null when the site gives none (or a non-literal). */
    record Read(String key, String defaultValue, String kind, String source) {}

    /** Roots owned by Spring Boot, the JVM or the container, not by this platform's own configuration. */
    static final Set<String> FRAMEWORK_ROOTS = Set.of(
            "spring", "server", "management", "logging", "user", "java", "os", "springdoc", "file", "line");

    private static final Pattern COMMENTS_AND_STRINGS = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])+'|//[^\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"");
    private static final Pattern DOTTED_TOKEN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]*(?:\\.[A-Za-z0-9_\\-]+)+");
    private static final Pattern CONSTANT = Pattern.compile(
            "(?:static\\s+final|final\\s+static)\\s+String\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern VALUE_ANN = Pattern.compile(
            "@(?:org\\.springframework\\.beans\\.factory\\.annotation\\.)?Value\\s*\\(\\s*(?:value\\s*=\\s*)?");
    private static final Pattern CONDITIONAL = Pattern.compile("@ConditionalOnProperty\\s*\\(([^)]*)\\)");
    private static final Pattern GET_PROPERTY = Pattern.compile(
            "\\b\\w*[eE]nv\\w*(?:\\(\\))?\\s*\\.\\s*getProperty\\(\\s*\"([A-Za-z][A-Za-z0-9_.\\-]*)\"(?:\\s*,\\s*(\"(?:\\\\.|[^\"\\\\])*\"|true|false|-?\\d[\\d_.]*[LlDdFf]?))?\\s*\\)");
    private static final Pattern CP_ANN = Pattern.compile(
            "@ConfigurationProperties\\(\\s*(?:(?:prefix|value)\\s*=\\s*)?\"([A-Za-z0-9_.\\-]+)\"");
    private static final Pattern RESOURCE_LITERAL = Pattern.compile("\"(/[\\w\\-/]+\\.properties)\"");
    private static final Pattern FIELD = Pattern.compile(
            "\\bprivate\\s+(?!static\\b)(final\\s+)?([\\w<>\\[\\],.?\\s]+?)\\s+(\\w+)\\s*(?:=\\s*([^;]+))?;");
    private static final Pattern NESTED_TYPE = Pattern.compile(
            "(?:(?:public|private|protected)\\s+)?(?:static\\s+)?(?:final\\s+)?(?:class|record)\\s+(\\w+)");

    final Path root;
    final Path core;
    final Map<String, String> constants = new HashMap<>();
    final List<Read> reads = new ArrayList<>();
    /** Dotted tokens found inside string literals of main code (comments removed). */
    final Set<String> literalTokens = new HashSet<>();
    /** Every key a typed {@code @ConfigurationProperties} class binds (and its resource-file keys). */
    final Map<String, String> cpKeys = new TreeMap<>();
    /** Keys that came from a defaults resource file, mapped to their value. */
    final Map<String, String> resourceDefaults = new TreeMap<>();
    /** key -> raw value, from application.yml only. */
    final Map<String, String> baseYml = new TreeMap<>();
    /** key -> "file: raw value" for every application*.yml (profile files included). */
    final Map<String, List<String>> allYml = new TreeMap<>();
    final String allYmlText;
    /** Simple name -> body of every top-level class of main code that is declared exactly once. */
    private final Map<String, String> topLevelBodies = new HashMap<>();

    ConfigSourceModel(Path root) {
        this.root = root;
        this.core = root.resolve("core");
        List<Path> javaFiles = listFiles(core, ".java", "/src/main/java/");
        Map<Path, String> stripped = new LinkedHashMap<>();
        for (Path p : javaFiles) {
            stripped.put(p, strip(read(p)));
        }
        Map<String, Set<String>> rawConstants = new HashMap<>();
        for (String src : stripped.values()) {
            Matcher m = CONSTANT.matcher(src);
            while (m.find()) {
                rawConstants.computeIfAbsent(m.group(1), k -> new HashSet<>()).add(m.group(2).trim());
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            for (Map.Entry<String, Set<String>> e : rawConstants.entrySet()) {
                Set<String> values = new HashSet<>();
                boolean ok = true;
                for (String expr : e.getValue()) {
                    String v = concat(expr);
                    if (v == null) {
                        ok = false;
                    } else {
                        values.add(v);
                    }
                }
                if (ok && values.size() == 1) {
                    constants.put(e.getKey(), values.iterator().next());
                }
            }
        }
        Set<String> duplicated = new HashSet<>();
        Pattern topLevel = Pattern.compile("(?m)^(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?class\\s+(\\w+)[^{;]*\\{");
        for (String src : stripped.values()) {
            Matcher tl = topLevel.matcher(src);
            while (tl.find()) {
                int open = tl.end() - 1;
                int close = matching(src, open, '{', '}');
                if (close > open && topLevelBodies.put(tl.group(1), src.substring(open + 1, close)) != null) {
                    duplicated.add(tl.group(1));
                }
            }
        }
        duplicated.forEach(topLevelBodies::remove);
        for (Map.Entry<Path, String> e : stripped.entrySet()) {
            scanJava(e.getKey(), e.getValue());
        }
        StringBuilder ymlText = new StringBuilder();
        for (Path p : listFiles(core, ".yml", "/src/main/resources/")) {
            String name = p.getFileName().toString();
            if (!name.startsWith("application")) {
                continue;
            }
            String text = read(p);
            ymlText.append(text).append('\n');
            Map<String, String> flat = new LinkedHashMap<>();
            try (InputStream in = Files.newInputStream(p)) {
                for (Object doc : new Yaml().loadAll(in)) {
                    if (doc instanceof Map<?, ?> map && !isProfileDocument(map)) {
                        flatten("", map, flat);
                    }
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            flat.forEach((k, v) -> {
                allYml.computeIfAbsent(k, x -> new ArrayList<>()).add(name + ": " + v);
                if (name.equals("application.yml")) {
                    baseYml.put(k, v);
                }
            });
        }
        allYmlText = ymlText.toString();
    }

    // ------------------------------------------------------------------------------------------ java scanning
    private void scanJava(Path file, String src) {
        String rel = root.relativize(file).toString();
        Matcher lit = STRING_LITERAL.matcher(src);
        while (lit.find()) {
            Matcher tok = DOTTED_TOKEN.matcher(lit.group());
            while (tok.find()) {
                literalTokens.add(tok.group());
            }
        }
        Matcher composed = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\s*\\+\\s*\"([A-Za-z0-9_.\\-]+)\"").matcher(src);
        while (composed.find()) {
            Matcher local = Pattern.compile("\\b" + composed.group(1) + "\\s*=\\s*\"([^\"]*)\"").matcher(src);
            String prefixConstant = local.find() ? local.group(1) : constants.get(composed.group(1));
            if (prefixConstant != null && prefixConstant.endsWith(".")) {
                literalTokens.add(prefixConstant + composed.group(2)); // KEY_PREFIX + "enabled"
            }
        }
        Matcher v = VALUE_ANN.matcher(src);
        while (v.find()) {
            String expr = valueExpression(src, v.end());
            if (expr == null || !expr.startsWith("${") || !expr.endsWith("}")) {
                continue;
            }
            String inner = expr.substring(2, expr.length() - 1);
            int colon = topLevelColon(inner);
            String key = (colon < 0 ? inner : inner.substring(0, colon)).trim();
            if (!key.matches("[A-Za-z][A-Za-z0-9_.\\-]*")) {
                continue;
            }
            String def = colon < 0 ? null : inner.substring(colon + 1);
            if (def != null && def.contains("${")) {
                def = null;
            }
            reads.add(new Read(key, def, "@Value", rel));
            literalTokens.add(key);
        }
        Matcher c = CONDITIONAL.matcher(src);
        while (c.find()) {
            String body = c.group(1);
            Matcher pre = Pattern.compile("prefix\\s*=\\s*\"([^\"]+)\"").matcher(body);
            String prefix = pre.find() ? pre.group(1) : "";
            Matcher names = Pattern.compile("(?:name|value)\\s*=\\s*(\\{[^}]*\\}|\"[^\"]*\")").matcher(body);
            while (names.find()) {
                Matcher one = Pattern.compile("\"([^\"]+)\"").matcher(names.group(1));
                while (one.find()) {
                    String key = prefix.isEmpty() ? one.group(1) : prefix + "." + one.group(1);
                    reads.add(new Read(key, null, "@ConditionalOnProperty", rel));
                    literalTokens.add(key);
                }
            }
        }
        Matcher gp = GET_PROPERTY.matcher(src);
        while (gp.find()) {
            String def = gp.group(2) == null ? null : unquote(gp.group(2));
            reads.add(new Read(gp.group(1), def, "getProperty", rel));
            literalTokens.add(gp.group(1));
        }
        Matcher cp = CP_ANN.matcher(src);
        while (cp.find()) {
            scanProperties(file, rel, src, cp.group(1), cp.end());
        }
    }

    private void scanProperties(Path file, String rel, String src, String prefix, int from) {
        Matcher decl = Pattern.compile("\\b(class|record)\\s+(\\w+)").matcher(src);
        if (!decl.find(from)) {
            return;
        }
        boolean isRecord = decl.group(1).equals("record");
        int open = src.indexOf('{', decl.end());
        if (open < 0) {
            return;
        }
        if (isRecord) {
            int paren = src.indexOf('(', decl.end());
            int close = matching(src, paren, '(', ')');
            if (paren >= 0 && close > paren) {
                for (String comp : splitTopLevel(src.substring(paren + 1, close))) {
                    Matcher name = Pattern.compile("(\\w+)\\s*$").matcher(comp.trim());
                    if (name.find()) {
                        String def = null;
                        Matcher dv = Pattern.compile("@DefaultValue\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)").matcher(comp);
                        if (dv.find()) {
                            def = unescape(dv.group(1));
                        }
                        record(prefix + "." + kebab(name.group(1)), def, rel);
                    }
                }
            }
        }
        int end = matching(src, open, '{', '}');
        if (end < 0) {
            return;
        }
        String body = src.substring(open + 1, end);
        bindBody(prefix, body, rel, 0, Map.of());
        Matcher res = RESOURCE_LITERAL.matcher(src);
        while (res.find()) {
            Path resource = moduleResources(file).resolve(res.group(1).substring(1));
            if (!Files.isRegularFile(resource)) {
                continue;
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(resource);
                    InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            for (String name : new TreeSet<>(props.stringPropertyNames())) {
                String key = prefix + "." + name;
                String value = props.getProperty(name);
                resourceDefaults.put(key, value);
                cpKeys.putIfAbsent(key, rel);
                reads.add(new Read(key, value, "defaults resource", root.relativize(resource).toString()));
            }
        }
    }

    private void bindBody(String prefix, String body, String rel, int depth, Map<String, String> inherited) {
        Map<String, String> nested = new LinkedHashMap<>(inherited);
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '"') {
                Matcher str = STRING_LITERAL.matcher(body);
                if (str.find(i) && str.start() == i) {
                    top.append(str.group());
                    i = str.end() - 1;
                    continue;
                }
            }
            if (ch == '{') {
                int end = matching(body, i, '{', '}');
                if (end < 0) {
                    break;
                }
                int cut = Math.max(top.lastIndexOf(";"), top.lastIndexOf("}"));
                Matcher t = NESTED_TYPE.matcher(top.substring(cut + 1));
                if (t.find()) {
                    nested.put(t.group(1), body.substring(i + 1, end));
                }
                top.append("{}");
                i = end;
                continue;
            }
            top.append(ch);
        }
        Matcher f = FIELD.matcher(top);
        while (f.find()) {
            boolean isFinal = f.group(1) != null;
            String type = f.group(2).trim();
            String name = f.group(3);
            String init = f.group(4) == null ? null : f.group(4).trim();
            if (isFinal && init == null) {
                continue; // set in the constructor: not a bindable property
            }
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            String simple = type.replaceAll("<.*", "").trim();
            String nestedBody = nested.containsKey(simple) ? nested.get(simple)
                    : depth < 4 && !isJdkType(simple) ? topLevelBodies.get(simple) : null;
            boolean scalar = nestedBody == null && !type.matches("(Map|List|Set|Collection|EnumMap|Optional)\\b.*");
            boolean bound = body.contains("set" + cap + "(") || (!scalar && body.contains("get" + cap + "("));
            if (!bound) {
                continue;
            }
            String key = prefix + "." + kebab(name);
            if (nestedBody != null) {
                bindBody(key, nestedBody, rel, depth + 1, nested);
            } else {
                record(key, init == null ? null : literal(init), rel);
            }
        }
    }

    private static boolean isJdkType(String simple) {
        return Set.of("String", "Integer", "Long", "Boolean", "Double", "Duration", "Object", "Map", "List", "Set",
                "Pattern", "Path", "Properties").contains(simple);
    }

    private void record(String key, String def, String rel) {
        cpKeys.putIfAbsent(key, rel);
        literalTokens.add(key);
        if (def != null) {
            reads.add(new Read(key, def, "@ConfigurationProperties", rel));
        }
    }

    private static Path moduleResources(Path javaFile) {
        String s = javaFile.toString();
        int i = s.indexOf("/src/main/java/");
        return Path.of(s.substring(0, i) + "/src/main/resources");
    }

    // ------------------------------------------------------------------------------------------- expressions
    /** The string value of an {@code @Value} argument made of literals and constants joined by '+'. */
    private String valueExpression(String src, int from) {
        int i = from;
        StringBuilder out = new StringBuilder();
        while (i < src.length()) {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
                i++;
            }
            if (i >= src.length()) {
                return null;
            }
            char ch = src.charAt(i);
            if (ch == '"') {
                Matcher m = STRING_LITERAL.matcher(src);
                if (!m.find(i) || m.start() != i) {
                    return null;
                }
                out.append(unescape(m.group().substring(1, m.group().length() - 1)));
                i = m.end();
            } else if (Character.isJavaIdentifierStart(ch)) {
                int j = i;
                while (j < src.length() && (Character.isJavaIdentifierPart(src.charAt(j)) || src.charAt(j) == '.')) {
                    j++;
                }
                String name = src.substring(i, j);
                String value = constants.get(name.substring(name.lastIndexOf('.') + 1));
                if (value == null) {
                    return null;
                }
                out.append(value);
                i = j;
            } else {
                return null;
            }
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
                i++;
            }
            if (i < src.length() && src.charAt(i) == '+') {
                i++;
                continue;
            }
            return i < src.length() && (src.charAt(i) == ')' || src.charAt(i) == ',') ? out.toString() : null;
        }
        return null;
    }

    /** A constant's initialiser: string literals and other constants joined by '+'. */
    private String concat(String expr) {
        StringBuilder out = new StringBuilder();
        for (String part : expr.split("\\+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String p = part.trim();
            if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
                out.append(unescape(p.substring(1, p.length() - 1)));
            } else if (p.matches("[A-Za-z_][\\w.]*")) {
                String v = constants.get(p.substring(p.lastIndexOf('.') + 1));
                if (v == null) {
                    return null;
                }
                out.append(v);
            } else {
                return null;
            }
        }
        return out.toString();
    }

    /** A Java literal initialiser as a property value, or null when it is not a plain literal. */
    static String literal(String expr) {
        String e = expr.trim();
        if (e.matches("\"(?:[^\"\\\\]|\\\\.)*\"(?:\\s*\\+\\s*\"(?:[^\"\\\\]|\\\\.)*\")*")) {
            StringBuilder sb = new StringBuilder();
            Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(e);
            while (m.find()) {
                sb.append(unescape(m.group(1)));
            }
            return sb.toString();
        }
        if (e.matches("-?[\\d_]+(\\.\\d+)?[LlDdFf]?")) {
            return e.replace("_", "").replaceAll("[LlDdFf]$", "");
        }
        return e.equals("true") || e.equals("false") ? e : null;
    }

    // ----------------------------------------------------------------------------------------------- helpers
    static String strip(String src) {
        Matcher m = COMMENTS_AND_STRINGS.matcher(src);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(g.startsWith("//") || g.startsWith("/*") ? " " : g));
        }
        m.appendTail(out);
        return out.toString();
    }

    static int topLevelColon(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            } else if (ch == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    static int matching(String s, int open, char o, char c) {
        if (open < 0 || open >= s.length()) {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == o) {
                depth++;
            } else if (ch == c && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inString) {
                cur.append(ch);
                if (ch == '\\' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '(' || ch == '<' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == '>' || ch == '}') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        if (!cur.toString().isBlank()) {
            parts.add(cur.toString());
        }
        return parts;
    }

    static String kebab(String camel) {
        return camel.replaceAll("(?<!^)(?=[A-Z])", "-").toLowerCase();
    }

    static String unquote(String s) {
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? unescape(t.substring(1, t.length() - 1)) : t;
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) {
                out.append(ch);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }

    private static boolean isProfileDocument(Map<?, ?> doc) {
        Object spring = doc.get("spring");
        if (spring instanceof Map<?, ?> s && s.get("config") instanceof Map<?, ?> c) {
            return c.get("activate") != null;
        }
        return false;
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, String> out) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            flattenValue(key, e.getValue(), out);
        }
    }

    private static void flattenValue(String key, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> m) {
            flatten(key, m, out);
        } else if (value instanceof List<?> list) {
            boolean scalar = list.stream().noneMatch(x -> x instanceof Map || x instanceof List);
            if (scalar) {
                List<String> parts = new ArrayList<>();
                list.forEach(x -> parts.add(String.valueOf(x)));
                out.put(key, String.join(",", parts));
            } else {
                for (Object item : list) {
                    flattenValue(key, item, out);
                }
            }
        } else {
            out.put(key, value == null ? "" : String.valueOf(value));
        }
    }

    /** {@code ${ENV:default}} (nested allowed) to the default; null when the value is not a static default. */
    static String effectiveDefault(String raw) {
        String v = raw.trim();
        while (v.startsWith("${") && v.endsWith("}") && matching(v, 0, '{', '}') == v.length() - 1) {
            String inner = v.substring(2, v.length() - 1);
            int colon = topLevelColon(inner);
            if (colon < 0) {
                return null;
            }
            v = inner.substring(colon + 1).trim();
        }
        return v.contains("${") ? null : v;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> listFiles(Path dir, String suffix, String mustContain) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix))
                    .filter(p -> p.toString().contains(mustContain))
                    .filter(p -> !p.toString().contains("/target/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
