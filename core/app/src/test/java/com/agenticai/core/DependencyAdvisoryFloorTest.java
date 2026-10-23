package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fails when a runtime dependency of the shipped core jars drops below the release that fixed a
 * published High advisory. The jar scan in {@code .github/workflows/release.yml} only fails on
 * Critical, so without this a later BOM bump or a removed pin in {@code core/pom.xml} would put a
 * known-vulnerable jar back into the images unnoticed. core/app depends on every runtime module,
 * and the versions come from the one parent pom, so its test classpath is the one the jars ship
 * (app-jobs and app-worker resolve the same managed versions).
 *
 * <p>Raise a floor here together with the pin in {@code core/pom.xml} when a newer advisory lands;
 * never lower one.
 */
class DependencyAdvisoryFloorTest {

    /**
     * One artifact: its Maven artifactId, a class the jar owns, the first fixed version and the
     * advisories.
     */
    private record Floor(String artifactId, String probeClass, String fixedIn, String advisories) {}

    private static final List<Floor> FLOORS =
            List.of(
                    new Floor(
                            "httpcore5",
                            "org.apache.hc.core5.http.HttpRequest",
                            "5.4.3",
                            "GHSA-hf6x-8p5f-cgmf"),
                    new Floor(
                            "httpcore5-h2",
                            "org.apache.hc.core5.http2.H2Error",
                            "5.4.3",
                            "GHSA-v3jc-474w-2wm6"),
                    new Floor(
                            "jackson-core",
                            "com.fasterxml.jackson.core.JsonFactory",
                            "2.21.7",
                            "GHSA-p6pp-m3f8-5c89, GHSA-7hhh-6rmp-j9qf"),
                    new Floor(
                            "jackson-databind",
                            "com.fasterxml.jackson.databind.ObjectMapper",
                            "2.21.7",
                            "GHSA-q4xh-88c3-wmh7, GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54"),
                    new Floor(
                            "postgresql",
                            "org.postgresql.Driver",
                            "42.7.12",
                            "GHSA-j92g-9f8w-j867"),
                    new Floor(
                            "lz4-java",
                            "net.jpountz.lz4.LZ4Factory",
                            "1.11.4",
                            "GHSA-mcr4-qmvw-px4g"));

    // mcp-core is deliberately absent: GHSA-8jxr-pr72-r468 is fixed only in 1.0.0, which has no
    // SseMessageEndpointValidator or io.modelcontextprotocol.json.jackson (McpOriginPinning and
    // StaticMcpStdioSidecarRouting in agents-tools stop compiling) and needs spring-ai 2.x. See
    // CHANGELOG.

    @Test
    void noShippedDependencyIsBelowItsAdvisoryFixVersion() throws Exception {
        Map<String, String> below = new LinkedHashMap<>();
        for (Floor floor : FLOORS) {
            String version = resolvedVersion(floor);
            if (compare(version, floor.fixedIn()) < 0) {
                below.put(
                        floor.artifactId() + " " + version,
                        floor.advisories() + " fixed in " + floor.fixedIn());
            }
        }
        assertThat(below)
                .as("dependencies below their advisory fix version: pin them in core/pom.xml")
                .isEmpty();
    }

    @Test
    void theVersionComparisonOrdersNumericSegmentsNotText() {
        assertThat(compare("2.21.10", "2.21.7")).isPositive();
        assertThat(compare("42.7.11", "42.7.12")).isNegative();
        assertThat(compare("5.4.3", "5.4.3")).isZero();
        assertThat(compare("1.0.0.RELEASE", "1.0.0")).isZero();
    }

    /**
     * The version in the file name of the jar that serves the probe class, i.e. the jar that wins
     * on the classpath.
     */
    private static String resolvedVersion(Floor floor) throws Exception {
        Class<?> probe =
                Class.forName(
                        floor.probeClass(),
                        false,
                        DependencyAdvisoryFloorTest.class.getClassLoader());
        URI location = probe.getProtectionDomain().getCodeSource().getLocation().toURI();
        String file = Paths.get(location).getFileName().toString();
        String prefix = floor.artifactId() + "-";
        assertThat(file)
                .as("jar serving %s", floor.probeClass())
                .startsWith(prefix)
                .endsWith(".jar");
        return file.substring(prefix.length(), file.length() - ".jar".length());
    }

    /**
     * Compares dotted numeric versions segment by segment; a non-numeric tail (".RELEASE", "-rc1")
     * counts as 0.
     */
    private static int compare(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? numeric(a[i]) : 0;
            int y = i < b.length ? numeric(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int numeric(String segment) {
        return segment.matches("\\d+") ? Integer.parseInt(segment) : 0;
    }
}
