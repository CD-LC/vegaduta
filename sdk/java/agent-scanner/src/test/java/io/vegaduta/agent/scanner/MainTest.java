package io.vegaduta.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.scanner.fixtures.BillingTools;
import io.vegaduta.agent.scanner.fixtures.LegacyTools;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  @Test
  void cleanClassPassesFailUnderAndPrintsMarkdown() {
    int code =
        Main.run(
            new String[] {"--class", BillingTools.class.getName(), "--fail-under", "90"}, out, err);

    assertThat(code).isEqualTo(Main.EXIT_OK);
    assertThat(out()).contains("# VegaDuta risk map").contains("Readiness score: 100/100");
  }

  @Test
  void unclassifiedPackageFailsUnderThresholdWithExitTwo() {
    int code =
        Main.run(
            new String[] {
              "--package",
              "io.vegaduta.agent.scanner.fixtures",
              "--format",
              "json",
              "--fail-under",
              "95"
            },
            out,
            err);

    assertThat(code).isEqualTo(Main.EXIT_FAIL_UNDER);
    assertThat(out()).startsWith("{\"score\":").contains("\"unclassified\":[");
    assertThat(err()).contains("below --fail-under 95");
  }

  @Test
  void jsonFormatWithoutThresholdExitsZero() {
    String[] args = {"--class", LegacyTools.class.getName(), "--format", "json"};

    int code = Main.run(args, out, err);

    assertThat(code).isEqualTo(Main.EXIT_OK);
    assertThat(out()).contains("\"unclassified\":[\"deleteCustomer\",\"legacy_export\"]");
  }

  @Test
  void missingSourceIsAUsageError() {
    int code = Main.run(new String[] {"--format", "md"}, out, err);

    assertThat(code).isEqualTo(Main.EXIT_USAGE);
    assertThat(err()).contains("at least one of --package, --jar or --class").contains("usage:");
  }

  @Test
  void unknownArgumentAndBadValuesAreUsageErrors() {
    assertThat(Main.run(new String[] {"--bogus"}, out, err)).isEqualTo(Main.EXIT_USAGE);
    assertThat(Main.run(new String[] {"--class", "x", "--format", "xml"}, out, err))
        .isEqualTo(Main.EXIT_USAGE);
    assertThat(Main.run(new String[] {"--class", "x", "--fail-under", "abc"}, out, err))
        .isEqualTo(Main.EXIT_USAGE);
    assertThat(Main.run(new String[] {"--class", "x", "--mode", "LOUD"}, out, err))
        .isEqualTo(Main.EXIT_USAGE);
  }

  @Test
  void helpPrintsUsageAndExitsZero() {
    assertThat(Main.run(new String[] {"--help"}, out, err)).isEqualTo(Main.EXIT_OK);
    assertThat(out()).contains("--fail-under");
  }

  @Test
  void missingJarIsAnIoUsageError() {
    int code = Main.run(new String[] {"--jar", "/definitely/not/here.jar"}, out, err);

    assertThat(code).isEqualTo(Main.EXIT_USAGE);
    assertThat(err()).contains("Not found");
  }

  private String out() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void aScanThatFindsNoToolsIsAnErrorNotAPerfectScore() {
    int code =
        Main.run(new String[] {"--class", "java.lang.String", "--fail-under", "80"}, out, err);

    assertThat(code).isEqualTo(Main.EXIT_USAGE);
    assertThat(err()).contains("no tool methods found");
  }

  @Test
  void modeOffIsScoredAsUngovernedAndStrictMarksUnclassifiedToolsDenied() {
    int off =
        Main.run(
            new String[] {
              "--class", BillingTools.class.getName(), "--mode", "OFF", "--format", "json"
            },
            out,
            err);
    assertThat(off).isEqualTo(Main.EXIT_OK);
    assertThat(out()).contains("GOVERNANCE_OFF").contains("\"approvalGatedCount\":0");

    ByteArrayOutputStream out2 = new ByteArrayOutputStream();
    Main.run(
        new String[] {"--class", LegacyTools.class.getName(), "--mode", "STRICT"},
        new PrintStream(out2),
        err);
    assertThat(out2.toString()).contains("DENIED by the STRICT policy gate");
  }
}
