package io.vegaduta.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the packaged CLI the way the documentation tells users to, in a separate JVM with nothing on
 * its class path but the jar and the "application" being scanned (this module's test fixtures).
 * {@link MainTest} calls {@code Main.run} in-process with every dependency already on the class
 * path, so it cannot see a jar that is missing them.
 *
 * <p>Runs after {@code package} (see the {@code cli-jar-it} execution in the module pom) against
 * {@code target/vegaduta-agent-scanner-<version>-cli.jar}; {@code -Dcli.jar=<path>} points it at
 * another jar. The thin library jar fails this test with a {@code NoClassDefFoundError}.
 */
class CliJarIT {

  private static final String FIXTURES = "io.vegaduta.agent.scanner.fixtures";

  private static Path cliJar;
  private static Path fixturesDir;

  @TempDir Path temp;

  @BeforeAll
  static void locateArtifacts() {
    String jar = System.getProperty("cli.jar");
    String fixtures = System.getProperty("fixtures.dir");
    assertThat(jar).as("system property cli.jar (set by the cli-jar-it execution)").isNotBlank();
    assertThat(fixtures).as("system property fixtures.dir").isNotBlank();
    cliJar = Path.of(jar);
    fixturesDir = Path.of(fixtures);
    assertThat(cliJar).as("the packaged CLI jar; run `mvn verify`, not `mvn test`").exists();
  }

  @Test
  void javaDashJarPrintsUsage() throws Exception {
    Result r = run("-jar", cliJar.toString(), "--help");

    assertThat(r.err).doesNotContain("NoClassDefFoundError");
    assertThat(r.exit).isEqualTo(Main.EXIT_OK);
    assertThat(r.out).contains("usage: io.vegaduta.agent.scanner.Main").contains("--fail-under");
  }

  @Test
  void javaDashJarScansAClassDirectoryAndGatesOnTheScore() throws Exception {
    Result json =
        run(
            "-jar",
            cliJar.toString(),
            "--jar",
            fixturesDir.toString(),
            "--package",
            FIXTURES,
            "--format",
            "json");

    assertThat(json.err).doesNotContain("NoClassDefFoundError").doesNotContain("Exception");
    assertThat(json.exit).isEqualTo(Main.EXIT_OK);
    assertThat(json.out).startsWith("{\"score\":").contains("\"unclassified\":[");

    Result gated =
        run(
            "-jar",
            cliJar.toString(),
            "--jar",
            fixturesDir.toString(),
            "--package",
            FIXTURES,
            "--fail-under",
            "95");

    assertThat(gated.exit).isEqualTo(Main.EXIT_FAIL_UNDER);
    assertThat(gated.out).contains("# VegaDuta risk map");
    assertThat(gated.err).contains("below --fail-under 95");
  }

  @Test
  void theDocumentedClassPathFormAlsoWorks() throws Exception {
    String classPath = fixturesDir + File.pathSeparator + cliJar;

    Result r =
        run(
            "-cp",
            classPath,
            "io.vegaduta.agent.scanner.Main",
            "--class",
            FIXTURES + ".BillingTools",
            "--fail-under",
            "90");

    assertThat(r.err).doesNotContain("NoClassDefFoundError");
    assertThat(r.exit).isEqualTo(Main.EXIT_OK);
    assertThat(r.out).contains("Readiness score: 100/100");
  }

  /**
   * Runs {@code java <args>} and returns its exit code and output; never inherits this JVM's class
   * path.
   */
  private Result run(String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(List.of(args));
    Path out = Files.createTempFile(temp, "stdout", ".txt");
    Path err = Files.createTempFile(temp, "stderr", ".txt");
    Process process =
        new ProcessBuilder(command)
            .directory(temp.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("scanner CLI did not finish in 60 s: " + command);
    }
    return new Result(
        process.exitValue(),
        Files.readString(out, StandardCharsets.UTF_8),
        Files.readString(err, StandardCharsets.UTF_8));
  }

  private record Result(int exit, String out, String err) {}
}
