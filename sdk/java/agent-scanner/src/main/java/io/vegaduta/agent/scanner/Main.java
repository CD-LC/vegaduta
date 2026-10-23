package io.vegaduta.agent.scanner;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar vegaduta-agent-scanner-&lt;version&gt;-cli.jar --jar app.jar \
 *     --package com.acme.tools --format md --fail-under 70
 * </pre>
 *
 * <p>Run the {@code -cli} jar (it bundles the SDK, SLF4J's API and RE2/J); the plain jar is the
 * library and needs them on the class path. With {@code -cp} instead of {@code -jar}, put the
 * {@code -cli} jar and the application's classes on it: {@code java -cp app.jar:...-cli.jar
 * io.vegaduta.agent.scanner.Main --package ...}.
 *
 * <p>Sources (at least one): {@code --package <prefix>} (repeatable), {@code --jar <path>} (jar or
 * class directory, repeatable), {@code --class <fqcn>} (repeatable). Options: {@code --format
 * md|json} (default md), {@code --fail-under <0-100>} (exit 2 when the score is lower), {@code
 * --approval-from LOW|MEDIUM|HIGH|CRITICAL} and {@code --mode STRICT|WARN|OFF} to score against the
 * policy the application runs with, {@code --help}.
 *
 * <p>Exit codes: 0 success, 1 usage or I/O error, 2 score below {@code --fail-under}.
 */
@Experimental
public final class Main {

  public static final int EXIT_OK = 0;
  public static final int EXIT_USAGE = 1;
  public static final int EXIT_FAIL_UNDER = 2;

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Runs the CLI against the given streams and returns the exit code without exiting. */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    Options options;
    try {
      options = Options.parse(args);
    } catch (IllegalArgumentException e) {
      err.println("error: " + e.getMessage());
      err.println();
      err.print(usage());
      return EXIT_USAGE;
    }
    if (options.help) {
      out.print(usage());
      return EXIT_OK;
    }

    ClassFinder finder = new ClassFinder();
    Set<Class<?>> classes = new LinkedHashSet<>();
    try {
      for (String jar : options.jars) {
        Path path = Path.of(jar);
        ClassFinder scoped = finder.withPath(path);
        classes.addAll(scoped.inPath(path));
        finder = scoped;
      }
      for (String pkg : options.packages) {
        classes.addAll(finder.inPackage(pkg));
      }
      if (!options.classNames.isEmpty()) {
        List<Class<?>> named = finder.byName(options.classNames);
        if (named.size() < options.classNames.size()) {
          err.println(
              "warning: "
                  + (options.classNames.size() - named.size())
                  + " class name(s) could not be loaded from the current classpath");
        }
        classes.addAll(named);
      }
    } catch (IOException e) {
      err.println("error: " + e.getMessage());
      return EXIT_USAGE;
    }

    GovernancePolicy policy =
        GovernancePolicy.builder()
            .approvalRequiredFrom(options.approvalFrom)
            .mode(options.mode)
            .build();
    RiskMap map;
    try {
      map = new RiskMapScanner(policy).scan(new ArrayList<>(classes));
    } catch (RuntimeException | LinkageError e) {
      err.println("error: scan failed: " + e);
      return EXIT_USAGE;
    }
    if (map.tools().isEmpty()) {
      // A gate that scores an application whose tools were never located is no gate at all.
      err.println(
          "error: no tool methods found in "
              + classes.size()
              + " class(es); check the package, jar or class names (a Spring Boot repackaged jar"
              + " keeps classes under BOOT-INF/classes/, which is scanned, but its dependencies"
              + " are not on the scanner's classpath)");
      return EXIT_USAGE;
    }
    out.print("json".equals(options.format) ? map.toJson() + "\n" : map.toMarkdown());
    out.flush();

    if (options.failUnder >= 0 && map.score() < options.failUnder) {
      err.println("readiness score " + map.score() + " is below --fail-under " + options.failUnder);
      return EXIT_FAIL_UNDER;
    }
    return EXIT_OK;
  }

  static String usage() {
    return String.join(
        "\n",
        "usage: io.vegaduta.agent.scanner.Main [sources] [options]",
        "",
        "sources (at least one):",
        "  --package <prefix>     scan classes under a package on the classpath (repeatable)",
        "  --jar <path>           scan a jar or a directory of .class files (repeatable)",
        "  --class <fqcn>         scan one class (repeatable)",
        "",
        "options:",
        "  --format md|json       report format (default md)",
        "  --fail-under <score>   exit 2 when the readiness score is below <score>",
        "  --approval-from <risk> policy threshold LOW|MEDIUM|HIGH|CRITICAL (default HIGH)",
        "  --mode STRICT|WARN|OFF policy mode to score against (default WARN)",
        "  --help                 print this message",
        "",
        "exit codes: 0 ok, 1 usage or I/O error (including a scan that finds no tools),",
        "            2 score below --fail-under",
        "");
  }

  static final class Options {
    final List<String> packages = new ArrayList<>();
    final List<String> jars = new ArrayList<>();
    final List<String> classNames = new ArrayList<>();
    String format = "md";
    int failUnder = -1;
    RiskLabel approvalFrom = RiskLabel.HIGH;
    GovernanceMode mode = GovernanceMode.WARN;
    boolean help;

    static Options parse(String[] args) {
      Options o = new Options();
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        switch (arg) {
          case "--help", "-h" -> o.help = true;
          case "--package" -> o.packages.add(value(args, ++i, arg));
          case "--jar" -> o.jars.add(value(args, ++i, arg));
          case "--class" -> o.classNames.add(value(args, ++i, arg));
          case "--format" -> {
            String v = value(args, ++i, arg).toLowerCase(Locale.ROOT);
            if (!v.equals("md") && !v.equals("json")) {
              throw new IllegalArgumentException("--format must be md or json, got " + v);
            }
            o.format = v;
          }
          case "--fail-under" -> {
            String v = value(args, ++i, arg);
            try {
              o.failUnder = Integer.parseInt(v);
            } catch (NumberFormatException e) {
              throw new IllegalArgumentException("--fail-under must be a number, got " + v);
            }
            if (o.failUnder < 0 || o.failUnder > 100) {
              throw new IllegalArgumentException("--fail-under must be within 0..100");
            }
          }
          case "--approval-from" -> o.approvalFrom = enumValue(RiskLabel.class, args, ++i, arg);
          case "--mode" -> o.mode = enumValue(GovernanceMode.class, args, ++i, arg);
          default -> throw new IllegalArgumentException("unknown argument " + arg);
        }
      }
      if (!o.help && o.packages.isEmpty() && o.jars.isEmpty() && o.classNames.isEmpty()) {
        throw new IllegalArgumentException("at least one of --package, --jar or --class is needed");
      }
      return o;
    }

    private static String value(String[] args, int index, String option) {
      if (index >= args.length) {
        throw new IllegalArgumentException(option + " needs a value");
      }
      return args[index];
    }

    private static <E extends Enum<E>> E enumValue(
        Class<E> type, String[] args, int index, String option) {
      String v = value(args, index, option).toUpperCase(Locale.ROOT);
      try {
        return Enum.valueOf(type, v);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(option + " does not accept " + v);
      }
    }
  }
}
