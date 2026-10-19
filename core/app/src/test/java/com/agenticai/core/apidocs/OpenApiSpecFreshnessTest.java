package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Check mode for the committed API contract: {@code docs/api/openapi.json} must be byte-identical
 * to what the generator produces from the controllers on the classpath. The SDKs ({@code node
 * sdk/generate.mjs --check}) and the consumer contracts ({@link ConsumerContractVerificationTest})
 * are checked against that file, so a stale copy would let a controller change slip past both.
 *
 * <p>When this fails, regenerate from {@code core/}: {@code mvn -o -pl app -am test
 * -Dtest=OpenApiSpecGeneratorTest -Dopenapi.write=true -Dsurefire.failIfNoSpecifiedTests=false},
 * then {@code node sdk/generate.mjs}, and commit the diff (see docs/api/README.md).
 */
class OpenApiSpecFreshnessTest {

    @Test
    void committedSpecMatchesTheControllers() throws IOException {
        Path root = ApiContractRepoSupport.findRepoRoot();
        assertThat(root).as("repo root (docs/ and core/pom.xml) above the working dir").isNotNull();
        Path committed = root.resolve("docs").resolve("api").resolve("openapi.json");
        assertThat(committed).as("docs/api/openapi.json").isRegularFile();

        String expected = ApiContractRepoSupport.generatedSpecJson(root);
        String actual = Files.readString(committed, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    "docs/api/openapi.json is out of date with the controllers. "
                            + firstDifference(actual, expected)
                            + "\nRegenerate from core/: mvn -o -pl app -am test"
                            + " -Dtest=OpenApiSpecGeneratorTest -Dopenapi.write=true"
                            + " -Dsurefire.failIfNoSpecifiedTests=false, then node"
                            + " sdk/generate.mjs, and commit the result (docs/api/README.md).");
        }
    }

    /** "first difference at line N: committed `...` vs generated `...`". */
    static String firstDifference(String committed, String generated) {
        String[] a = committed.split("\n", -1);
        String[] b = generated.split("\n", -1);
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (!a[i].equals(b[i])) {
                return "First difference at line "
                        + (i + 1)
                        + ": committed `"
                        + clip(a[i])
                        + "` vs generated `"
                        + clip(b[i])
                        + "`.";
            }
        }
        return "Committed has " + a.length + " lines, generated has " + b.length + ".";
    }

    private static String clip(String s) {
        String t = s.strip();
        return t.length() > 160 ? t.substring(0, 160) + "..." : t;
    }

    @Test
    void firstDifferenceNamesTheLine() {
        assertThat(firstDifference("a\nb\nc", "a\nx\nc")).contains("line 2").contains("`b`");
        assertThat(firstDifference("a", "a\nb")).contains("1 lines").contains("2.");
    }
}
