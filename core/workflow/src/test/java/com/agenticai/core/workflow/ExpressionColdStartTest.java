package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The 50 ms default wall budget flaked in a cold JVM because the first expression paid for class loading and
 * static init. The fix is to warm up when the Spring bean is constructed. This runs {@link ExpressionColdStartProbe}
 * in a FRESH JVM (nothing pre-loaded by other tests) and checks, without asserting on wall-clock speed, that
 * the warm-up happened during construction: building the resolver loads the expression classes, and the first
 * real expressions afterwards load (almost) none.
 */
class ExpressionColdStartTest {

    /** Classes the warm-up must have loaded at construction (parser, evaluator, built-ins, Jackson, java.time...). */
    private static final int MIN_LOADED_AT_CONSTRUCTION = 150;
    /**
     * What the first use may still load. Measured: 0 with the warm-up, 114 (and 56 ms, over the 50 ms default
     * budget) with the warm-up removed. The slack allows for JDK differences without letting real init through.
     */
    private static final int MAX_LOADED_BY_FIRST_USE = 15;

    @Test
    void theFirstExpressionAfterConstructionIsNotBilledForClassInit() throws Exception {
        Map<String, String> out = runProbe();

        assertEquals("none", out.get("failure"), "the probe's first expressions failed: " + out);
        long construction = Long.parseLong(out.get("constructionLoaded"));
        long firstUse = Long.parseLong(out.get("firstUseLoaded"));
        assertTrue(construction >= MIN_LOADED_AT_CONSTRUCTION,
            "constructing the resolver should run the warm-up and load the expression classes, but loaded only "
                + construction + " classes: " + out);
        assertTrue(firstUse <= MAX_LOADED_BY_FIRST_USE,
            "the first real expressions still loaded " + firstUse + " classes (limit " + MAX_LOADED_BY_FIRST_USE
                + "), so they would be billed for class init inside the wall budget: " + out);
    }

    private static Map<String, String> runProbe() throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // The test classpath is far beyond the Windows command-line limit, so it goes through an @argfile.
        Path argFile = Files.createTempFile("cold-start-probe", ".args");
        try {
            String classpath = System.getProperty("java.class.path").replace('\\', '/');
            Files.writeString(argFile, "-cp\n\"" + classpath + "\"\n", StandardCharsets.UTF_8);
            return runProbe(java, argFile);
        } finally {
            Files.deleteIfExists(argFile);
        }
    }

    private static Map<String, String> runProbe(String java, Path argFile) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(java, "-Xshare:auto", "@" + argFile, ExpressionColdStartProbe.class.getName()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "probe JVM did not finish: " + text);
        Map<String, String> values = new HashMap<>();
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0 && line.matches("^[a-zA-Z]+=.*")) {
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        assertTrue(values.containsKey("firstUseLoaded"), "probe printed no result (exit " + p.exitValue() + "): " + text);
        System.out.println("COLDSTART " + values);
        return values;
    }
}
