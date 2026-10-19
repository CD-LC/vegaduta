package com.agenticai.core.agents.tools.code.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The allowlist pre-screen for model-written tool source. */
class GeneratedCodeScreenTest {

    private static List<String> java(String source) {
        return GeneratedCodeScreen.screen(CustomCodeToolLanguage.JAVA, source).stream()
                .map(e -> e.message())
                .toList();
    }

    private static List<String> python(String source) {
        return GeneratedCodeScreen.screen(CustomCodeToolLanguage.PYTHON, source).stream()
                .map(e -> e.message())
                .toList();
    }

    private static String tool(String body) {
        return "import java.util.Map;\npublic class Tool {\n  public static Object run(Map<String, Object> input) throws Exception {\n"
                + body
                + "\n  }\n}\n";
    }

    // ---- Java: must be rejected ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Runtime.getRuntime().exec(\"id\"); return 1;",
                "return new ProcessBuilder(\"id\").start();",
                "return System.getenv(\"APP_ENCRYPTION_KEY\");",
                "System.exit(0); return 1;",
                "return Class.forName(\"java.lang.Runtime\");",
                "new Thread(() -> {}).start(); return 1;",
                "return java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"));",
                "return new java.net.URL(\"http://x\").openStream();",
                "return java.util.concurrent.ThreadLocalRandom.current().nextInt();",
                "return java.lang.reflect.Array.newInstance(int.class, 1);",
                "return java.lang.Runtime.version();",
                "return Boolean.getBoolean(\"java.vm.name\");",
                "return Integer.getInteger(\"user.dir\");",
                "return java.util.List.of(1).parallelStream().count();",
                "return java.util.ResourceBundle.getBundle(\"x\");",
                "return ClassLoader.getSystemClassLoader();"
            })
    void deniedJavaBodiesAreRejected(String body) {
        assertThat(java(tool(body))).as(body).isNotEmpty();
    }

    @Test
    void deniedImportsAreRejected() {
        for (String imp :
                List.of(
                        "java.io.File",
                        "java.nio.file.Files",
                        "java.net.Socket",
                        "java.util.concurrent.Executors",
                        "java.lang.reflect.Method",
                        "java.lang.invoke.MethodHandles",
                        "javax.script.ScriptEngine",
                        "org.apache.commons.io.FileUtils",
                        "com.example.Anything",
                        "java.security.MessageDigest",
                        "java.sql.DriverManager",
                        "java.lang.System",
                        "java.lang.Runtime")) {
            String src = "import " + imp + ";\nimport java.util.Map;\npublic class Tool { public static Object run(Map<String,Object> i) { return 1; } }";
            assertThat(java(src)).as(imp).isNotEmpty();
        }
    }

    @Test
    void packageAndNativeAreRejected() {
        assertThat(java("package com.evil;\n" + tool("return 1;"))).anyMatch(m -> m.contains("default package"));
        assertThat(java("public class Tool { native int x(); public static Object run(java.util.Map<String,Object> i){return 1;} }"))
                .anyMatch(m -> m.contains("native"));
    }

    @Test
    void errorsCarryTheLineNumber() {
        var errors =
                GeneratedCodeScreen.screen(
                        CustomCodeToolLanguage.JAVA, tool("int a = 1;\nSystem.exit(1);\nreturn a;"));
        // three header lines, then the body: System.exit is its second line, i.e. line 5
        assertThat(errors).extracting(e -> e.line()).containsExactly(5);
    }

    // ---- Java: must be accepted ---------------------------------------------------------------

    @Test
    void ordinaryJavaPasses() {
        String src =
                """
                import java.math.BigDecimal;
                import java.math.RoundingMode;
                import java.time.LocalDate;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;
                import java.util.stream.Collectors;

                public class Tool {
                    record Money(String currency, long minor) {}
                    public static Object run(Map<String, Object> input) {
                        List<String> words = new ArrayList<>(List.of("b", "a"));
                        BigDecimal tax = new BigDecimal("12.345").setScale(2, RoundingMode.HALF_UP);
                        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                        out.put("words", words.stream().sorted().collect(Collectors.toList()));
                        out.put("tax", tax.toPlainString());
                        out.put("date", LocalDate.of(2026, 1, 2).toString());
                        out.put("n", Integer.parseInt("5") + Long.parseLong("7"));
                        out.put("flag", Boolean.parseBoolean("true"));
                        out.put("money", new Money("INR", 100L).toString());
                        return out;
                    }
                }
                """;
        assertThat(java(src)).isEmpty();
    }

    @Test
    void forbiddenWordsInCommentsAndStringsAreNotCode() {
        String src =
                tool(
                        "// we do not call System.exit or Runtime.getRuntime here\n"
                                + "/* java.io.File and Thread are unavailable */\n"
                                + "String note = \"System.getenv is blocked; import java.net.Socket\";\n"
                                + "String block = \"\"\"\n  Runtime ProcessBuilder\n  \"\"\";\n"
                                + "char c = 'x';\n"
                                + "return note + block + c;");
        assertThat(java(src)).isEmpty();
    }

    @Test
    void aToolsOwnTypeMayShareAJavaLangDeniedName() {
        String src =
                "import java.util.Map;\npublic class Tool {\n"
                        + "  static class Process { int step = 1; }\n"
                        + "  public static Object run(Map<String,Object> input) { return new Process().step; }\n}";
        assertThat(java(src)).isEmpty();
    }

    @Test
    void lowercaseVariablesNamedLikePackagesAreNotMistakenForPackages() {
        String src = tool("java.math.BigDecimal net = new java.math.BigDecimal(\"1\");\nint org = 1; int com = 2;\nreturn net.add(java.math.BigDecimal.ONE).intValue() + org + com;");
        assertThat(java(src)).isEmpty();
    }

    // ---- Python -------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "import os\ndef run(input):\n    return 1",
                "import subprocess\ndef run(input):\n    return 1",
                "import json, socket\ndef run(input):\n    return 1",
                "from os import path\ndef run(input):\n    return 1",
                "from . import x\ndef run(input):\n    return 1",
                "import json._private\ndef run(input):\n    return 1",
                "def run(input):\n    return open('/etc/passwd').read()",
                "def run(input):\n    return eval('1+1')",
                "def run(input):\n    exec('x=1')\n    return 1",
                "def run(input):\n    return getattr(input, 'x')",
                "def run(input):\n    return ().__class__",
                "def run(input):\n    return input._secret",
                "def run(input):\n    return __import__('os')"
            })
    void deniedPythonIsRejected(String source) {
        assertThat(python(source)).as(source).isNotEmpty();
    }

    @Test
    void ordinaryPythonPasses() {
        String src =
                """
                import json
                import math
                from datetime import datetime, timezone
                from collections import OrderedDict

                # opening the file is not available: this comment mentions open() and os
                def run(input):
                    note = "open(os.system) and __import__ are just words in a string"
                    d = OrderedDict(a=1)
                    return {"root": math.sqrt(input.get("n", 16)), "json": json.dumps(d), "note": note,
                            "year": datetime(2026, 1, 1, tzinfo=timezone.utc).year}
                """;
        assertThat(python(src)).isEmpty();
    }

    @Test
    void anAttributeNamedLikeADeniedBuiltinIsNotTheBuiltin() {
        assertThat(python("import re\ndef run(input):\n    return re.compile('a').match('a') is not None and input.get('open')")).isEmpty();
    }

    @Test
    void emptySourceIsRejected() {
        assertThat(java("  ")).isNotEmpty();
        assertThat(python(null)).isNotEmpty();
    }
}
