package io.vegaduta.agent.springai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fails when a transitive dependency of the adapter drops below the release that fixed a published
 * High advisory, so a later Spring Boot or Spring AI bump cannot silently bring the old Jackson
 * back into the SDK's build, tests and SBOM. Raise a floor together with the pin in {@code
 * sdk/java/pom.xml}; never lower one.
 */
class DependencyAdvisoryFloorTest {

  /**
   * One artifact: its artifactId, a class the jar owns, the first fixed version, the advisories.
   */
  private record Floor(String artifactId, String probeClass, String fixedIn, String advisories) {}

  private static final List<Floor> FLOORS =
      List.of(
          new Floor(
              "jackson-core",
              "tools.jackson.core.TokenStreamFactory",
              "3.1.7",
              "GHSA-p6pp-m3f8-5c89, GHSA-7hhh-6rmp-j9qf"),
          new Floor(
              "jackson-databind",
              "tools.jackson.databind.ObjectMapper",
              "3.1.7",
              "GHSA-q4xh-88c3-wmh7, GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54"));

  @Test
  void noDependencyIsBelowItsAdvisoryFixVersion() throws Exception {
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
        .as("dependencies below their advisory fix version: pin them in sdk/java/pom.xml")
        .isEmpty();
  }

  @Test
  void versionComparisonOrdersNumericSegmentsNotText() {
    assertThat(compare("3.1.10", "3.1.7")).isPositive();
    assertThat(compare("3.1.5", "3.1.7")).isNegative();
    assertThat(compare("3.1.7", "3.1.7")).isZero();
  }

  /** The version in the file name of the jar that serves the probe class. */
  private static String resolvedVersion(Floor floor) throws Exception {
    Class<?> probe =
        Class.forName(
            floor.probeClass(), false, DependencyAdvisoryFloorTest.class.getClassLoader());
    URI location = probe.getProtectionDomain().getCodeSource().getLocation().toURI();
    String file = Paths.get(location).getFileName().toString();
    String prefix = floor.artifactId() + "-";
    assertThat(file).as("jar serving %s", floor.probeClass()).startsWith(prefix).endsWith(".jar");
    return file.substring(prefix.length(), file.length() - ".jar".length());
  }

  private static int compare(String left, String right) {
    String[] a = left.split("[.-]");
    String[] b = right.split("[.-]");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = i < a.length && a[i].matches("\\d+") ? Integer.parseInt(a[i]) : 0;
      int y = i < b.length && b[i].matches("\\d+") ? Integer.parseInt(b[i]) : 0;
      if (x != y) {
        return Integer.compare(x, y);
      }
    }
    return 0;
  }
}
