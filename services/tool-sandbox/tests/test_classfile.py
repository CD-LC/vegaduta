"""Tests for the Java bytecode gate.

These compile real source with a real ``javac`` and check the verifier's
verdict on the actual class files, because the whole point of verifying
bytecode rather than source is that only the compiler knows what the code
really references. A test that hand-rolled class files would be testing the
wrong thing.

Two halves, and the second is the one that catches regressions:

* **rejection** - the obvious escapes must be refused;
* **acceptance** - ordinary modern Java must still compile and pass. Lambdas,
  string concatenation, records, enhanced switch and streams all emit
  ``invokedynamic`` against ``java.lang.invoke``, which the policy denies as a
  class. If the bootstrap carve-out is ever tightened too far, these tests fail
  and the feature becomes unusable rather than merely insecure - which is
  exactly the failure mode that is easy to ship by accident.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import classfile

JAVAC = shutil.which("javac")
pytestmark = pytest.mark.skipif(JAVAC is None, reason="javac is not on PATH")

HARNESS_JAVA = Path(__file__).resolve().parents[1] / "harness" / "java"


@pytest.fixture(scope="module")
def vega_api(tmp_path_factory) -> Path:
    """Compile the trusted vega.Log helper that tools compile against."""
    out = tmp_path_factory.mktemp("vega-api")
    subprocess.run(
        [JAVAC, "-nowarn", "-d", str(out), str(HARNESS_JAVA / "vega" / "Log.java")],
        check=True, capture_output=True,
    )
    return out


def compile_source(source: str, classpath: Path | None = None) -> dict[str, bytes]:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        src = root / "Tool.java"
        src.write_text(source, encoding="utf-8")
        out = root / "classes"
        out.mkdir()
        cmd = [JAVAC, "-nowarn", "-g:lines,source", "-d", str(out)]
        if classpath:
            cmd += ["-cp", str(classpath)]
        cmd.append(str(src))
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            raise AssertionError(f"fixture failed to compile:\n{result.stderr}")
        return {p.name: p.read_bytes() for p in sorted(out.rglob("*.class"))}


# --------------------------------------------------------------------------
# Must be rejected
# --------------------------------------------------------------------------

DENIED_CASES = {
    "process execution": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) throws Exception {
                Runtime.getRuntime().exec("/bin/sh");
                return "no";
            }
        }
    """,
    "environment read": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return System.getenv("APP_ENCRYPTION_KEY");
            }
        }
    """,
    "system property read": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return System.getProperty("user.home");
            }
        }
    """,
    "file read": """
        import java.util.Map;
        import java.nio.file.Files;
        import java.nio.file.Path;
        public class Tool {
            public static Object run(Map<String, Object> input) throws Exception {
                return Files.readString(Path.of("/proc/self/environ"));
            }
        }
    """,
    "legacy file api": """
        import java.util.Map;
        import java.io.File;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return new File("/etc/passwd").exists();
            }
        }
    """,
    "network socket": """
        import java.util.Map;
        import java.net.Socket;
        public class Tool {
            public static Object run(Map<String, Object> input) throws Exception {
                new Socket("10.0.0.1", 5432);
                return "no";
            }
        }
    """,
    "reflection by literal": """
        import java.util.Map;
        import java.lang.reflect.Method;
        public class Tool {
            public static Object run(Map<String, Object> input) throws Exception {
                Method m = String.class.getMethod("length");
                return m.invoke("abc");
            }
        }
    """,
    "reflection by name": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) throws Exception {
                return Class.forName("java.lang.Runtime");
            }
        }
    """,
    "method handles lookup": """
        import java.util.Map;
        import java.lang.invoke.MethodHandles;
        public class Tool {
            public static Object run(Map<String, Object> input) throws Exception {
                return MethodHandles.lookup().toString();
            }
        }
    """,
    "class loader": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return Tool.class.getClassLoader();
            }
        }
    """,
    "thread creation": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) throws Exception {
                Thread t = new Thread(() -> { });
                t.start();
                return "no";
            }
        }
    """,
    "system exit": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                System.exit(1);
                return null;
            }
        }
    """,
    "service loader": """
        import java.util.Map;
        import java.util.ServiceLoader;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return ServiceLoader.load(Runnable.class).toString();
            }
        }
    """,
    "denied type in an unused field": """
        import java.util.Map;
        import java.io.File;
        public class Tool {
            private static File unused;
            public static Object run(Map<String, Object> input) { return "ok"; }
        }
    """,
    "denied type hidden in a nested class": """
        import java.util.Map;
        public class Tool {
            static class Helper {
                static String read() throws Exception {
                    return java.nio.file.Files.readString(java.nio.file.Path.of("/etc/hostname"));
                }
            }
            public static Object run(Map<String, Object> input) throws Exception {
                return Helper.read();
            }
        }
    """,
    # Hardening (AREA 4): system properties read through the wrapper classes, class
    # loading by name through resource bundles, and threads started implicitly
    # by the parallel stream / Arrays helpers.
    "system property via Boolean.getBoolean": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return Boolean.getBoolean("java.vm.name");
            }
        }
    """,
    "system property via Integer.getInteger": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return Integer.getInteger("java.specification.version");
            }
        }
    """,
    "system property via Long.getLong": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return Long.getLong("user.dir");
            }
        }
    """,
    "class loading by name via ResourceBundle": """
        import java.util.Map;
        import java.util.ResourceBundle;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return ResourceBundle.getBundle("com.example.Anything").toString();
            }
        }
    """,
    "class loading by name via a ResourceBundle subclass": """
        import java.util.Map;
        import java.util.PropertyResourceBundle;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return PropertyResourceBundle.getBundle("x").toString();
            }
        }
    """,
    "implicit threads via parallelStream": """
        import java.util.List;
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return List.of(1, 2, 3).parallelStream().count();
            }
        }
    """,
    "implicit threads via BaseStream.parallel": """
        import java.util.Map;
        import java.util.stream.IntStream;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return IntStream.range(0, 100).parallel().sum();
            }
        }
    """,
    "implicit threads via Arrays.parallelSort": """
        import java.util.Arrays;
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                int[] a = {3, 1, 2};
                Arrays.parallelSort(a);
                return a;
            }
        }
    """,
    "nested class of a denied class": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return String.valueOf(Thread.State.NEW);
            }
        }
    """,
}


@pytest.mark.parametrize("name,source", sorted(DENIED_CASES.items()))
def test_denied_source_is_rejected(name: str, source: str) -> None:
    violations = classfile.verify(compile_source(source))
    assert violations, f"{name!r} was NOT rejected - the sandbox would have run it"


def test_package_declaration_is_rejected() -> None:
    source = """
        package com.evil;
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) { return "ok"; }
        }
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        src = root / "Tool.java"
        src.write_text(source, encoding="utf-8")
        out = root / "classes"
        out.mkdir()
        subprocess.run([JAVAC, "-nowarn", "-d", str(out), str(src)],
                       check=True, capture_output=True)
        classes = {p.name: p.read_bytes() for p in out.rglob("*.class")}
    violations = classfile.verify(classes)
    assert any("default package" in v for v in violations)


# --------------------------------------------------------------------------
# Must be accepted
# --------------------------------------------------------------------------

ALLOWED_CASES = {
    "plain arithmetic and strings": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                String name = String.valueOf(input.getOrDefault("name", "world"));
                int n = Integer.parseInt(String.valueOf(input.getOrDefault("times", "3")));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) { sb.append(name).append(' '); }
                return Map.of("greeting", "Hello, " + name + "!", "repeated", sb.toString().trim());
            }
        }
    """,
    "lambdas and streams": """
        import java.util.List;
        import java.util.Map;
        import java.util.stream.Collectors;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                List<String> words = List.of("delta", "alpha", "charlie", "bravo");
                return words.stream()
                    .filter(w -> w.length() > 4)
                    .map(String::toUpperCase)
                    .sorted()
                    .collect(Collectors.toList());
            }
        }
    """,
    "records and enhanced switch": """
        import java.util.Map;
        public class Tool {
            record Money(String currency, long minor) { }
            public static Object run(Map<String, Object> input) {
                Money m = new Money("INR", 12550L);
                String label = switch (m.currency()) {
                    case "INR" -> "rupees";
                    case "USD" -> "dollars";
                    default -> "unknown";
                };
                return Map.of("label", label, "major", m.minor() / 100.0, "record", m.toString());
            }
        }
    """,
    "regex, time, bigdecimal": """
        import java.math.BigDecimal;
        import java.math.RoundingMode;
        import java.time.LocalDate;
        import java.time.format.DateTimeFormatter;
        import java.util.Map;
        import java.util.regex.Matcher;
        import java.util.regex.Pattern;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                Matcher m = Pattern.compile("([A-Z]{2})-(\\\\d+)")
                    .matcher(String.valueOf(input.getOrDefault("code", "IN-42")));
                BigDecimal tax = new BigDecimal("1234.5678").setScale(2, RoundingMode.HALF_UP);
                return Map.of(
                    "matched", m.matches(),
                    "tax", tax.toPlainString(),
                    "date", LocalDate.of(2026, 8, 5).format(DateTimeFormatter.ISO_DATE));
            }
        }
    """,
    "generics, interfaces and an inner class": """
        import java.util.ArrayList;
        import java.util.Comparator;
        import java.util.List;
        import java.util.Map;
        public class Tool {
            interface Scorer { int score(String value); }
            static class LengthScorer implements Scorer {
                public int score(String value) { return value.length(); }
            }
            public static Object run(Map<String, Object> input) {
                Scorer scorer = new LengthScorer();
                List<String> items = new ArrayList<>(List.of("aa", "b", "cccc"));
                items.sort(Comparator.comparingInt(scorer::score).reversed());
                return items;
            }
        }
    """,
    "parsing and sequential streams (the allowed forms next to the hardened ones)": """
        import java.util.Arrays;
        import java.util.List;
        import java.util.Map;
        import java.util.stream.IntStream;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                int[] a = {3, 1, 2};
                Arrays.sort(a);
                boolean flag = Boolean.parseBoolean("true");
                long total = IntStream.range(0, 10).sum() + Integer.parseInt("5") + Long.parseLong("7");
                return Map.of("sorted", Arrays.toString(a), "n", List.of(1, 2, 3).stream().count(),
                    "flag", flag, "total", total);
            }
        }
    """,
    "a tool's own method named like a hardened one": """
        import java.util.Map;
        public class Tool {
            static int parallel(int x) { return x + 1; }
            public static Object run(Map<String, Object> input) {
                return parallel(1);
            }
        }
    """,
    "exceptions and try/catch": """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                try {
                    return Map.of("value", Integer.parseInt(String.valueOf(input.get("n"))));
                } catch (NumberFormatException e) {
                    return Map.of("error", "not a number: " + e.getMessage());
                }
            }
        }
    """,
}


@pytest.mark.parametrize("name,source", sorted(ALLOWED_CASES.items()))
def test_ordinary_java_is_accepted(name: str, source: str) -> None:
    violations = classfile.verify(compile_source(source))
    assert violations == [], f"{name!r} was wrongly rejected: {violations}"


def test_vega_log_is_available_to_tools(vega_api: Path) -> None:
    source = """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                vega.Log.info("debugging", input);
                return "ok";
            }
        }
    """
    violations = classfile.verify(compile_source(source, classpath=vega_api))
    assert violations == [], violations


def test_malformed_class_file_is_a_violation_not_an_exception() -> None:
    violations = classfile.verify({"Tool.class": b"not a class file at all"})
    assert violations and "unreadable" in violations[0]
