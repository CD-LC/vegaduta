package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The differences between an expected (recorded) trace and an actual one.
 *
 * <p>Entries are aligned by {@code (toolName, canonical arguments)} with a
 * longest-common-subsequence alignment, so an extra call shows up as one {@link Kind#ADDED} rather
 * than shifting everything after it. Volatile fields (timestamps, run and agent ids, sequence
 * numbers) are ignored; a {@link UnaryOperator redaction} can scrub volatile argument fields
 * (request ids, dates) before comparing.
 *
 * <pre>{@code
 * UnaryOperator<String> redact = args -> args.replaceAll("\"ts\":\\d+", "\"ts\":0");
 * TraceDiff diff = TraceDiff.compare(expected, recorder.trace(), redact);
 * assertThat(diff.isEmpty()).as(diff.toMarkdown()).isTrue();
 * }</pre>
 */
@Experimental
public final class TraceDiff {

  /** What changed. */
  public enum Kind {
    /** The actual trace has a call the expected one does not. */
    ADDED,
    /** The expected trace has a call the actual one does not. */
    REMOVED,
    /** The same call happens in both traces but at a different position. */
    REORDERED,
    /** The same tool is called at the same position with different arguments. */
    CHANGED_ARGS,
    /** The same call ended with a different status. */
    CHANGED_STATUS,
    /** The same call ended with the same status but a different output. */
    CHANGED_OUTPUT
  }

  /**
   * One difference.
   *
   * @param kind what changed
   * @param expected the expected entry, or {@code null} for {@link Kind#ADDED}
   * @param actual the actual entry, or {@code null} for {@link Kind#REMOVED}
   * @param detail a one-line human explanation
   */
  public record Difference(
      Kind kind, @Nullable TraceEntry expected, @Nullable TraceEntry actual, String detail) {}

  private final List<Difference> differences;

  private TraceDiff(List<Difference> differences) {
    this.differences = Collections.unmodifiableList(new ArrayList<>(differences));
  }

  public static TraceDiff compare(Trace expected, Trace actual) {
    return compare(expected, actual, UnaryOperator.identity());
  }

  /** Compares after applying {@code redactArgs} to every entry's arguments on both sides. */
  public static TraceDiff compare(Trace expected, Trace actual, UnaryOperator<String> redactArgs) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(actual, "actual");
    Objects.requireNonNull(redactArgs, "redactArgs");
    List<TraceEntry> e = redact(expected.entries(), redactArgs);
    List<TraceEntry> a = redact(actual.entries(), redactArgs);
    return new TraceDiff(diff(e, a));
  }

  public List<Difference> differences() {
    return differences;
  }

  public boolean isEmpty() {
    return differences.isEmpty();
  }

  public List<Difference> of(Kind kind) {
    return differences.stream().filter(d -> d.kind() == kind).toList();
  }

  /** A Markdown report: a one-line verdict then a table with one row per difference. */
  public String toMarkdown() {
    if (differences.isEmpty()) {
      return "No differences.\n";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(differences.size()).append(differences.size() == 1 ? " difference" : " differences");
    sb.append(":\n\n| # | Kind | Expected | Actual | Detail |\n|---|---|---|---|---|\n");
    int i = 1;
    for (Difference d : differences) {
      sb.append("| ")
          .append(i++)
          .append(" | ")
          .append(d.kind())
          .append(" | ")
          .append(cell(d.expected()))
          .append(" | ")
          .append(cell(d.actual()))
          .append(" | ")
          .append(escapeCell(d.detail()))
          .append(" |\n");
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return toMarkdown();
  }

  // ------------------------------------------------------------------------------ algorithm

  private static List<TraceEntry> redact(List<TraceEntry> entries, UnaryOperator<String> redact) {
    List<TraceEntry> out = new ArrayList<>(entries.size());
    for (TraceEntry e : entries) {
      String redacted = redact.apply(e.argumentsJson());
      out.add(redacted.equals(e.argumentsJson()) ? e : e.withArgumentsJson(redacted));
    }
    return out;
  }

  private static String key(TraceEntry e) {
    return e.toolName() + "\u0000" + e.canonicalArguments();
  }

  private static List<Difference> diff(List<TraceEntry> expected, List<TraceEntry> actual) {
    int n = expected.size();
    int m = actual.size();
    String[] ek = expected.stream().map(TraceDiff::key).toArray(String[]::new);
    String[] ak = actual.stream().map(TraceDiff::key).toArray(String[]::new);
    int[][] lcs = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        lcs[i][j] =
            ek[i].equals(ak[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    // Walk the alignment into a flat op list, then refine removed/added pairs.
    List<Difference> ops = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && ek[i].equals(ak[j])) {
        compareMatched(expected.get(i), actual.get(j), ops);
        i++;
        j++;
      } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
        ops.add(
            new Difference(
                Kind.ADDED, null, actual.get(j), "unexpected call " + describe(actual.get(j))));
        j++;
      } else {
        ops.add(
            new Difference(
                Kind.REMOVED, expected.get(i), null, "missing call " + describe(expected.get(i))));
        i++;
      }
    }
    return refine(ops);
  }

  private static void compareMatched(TraceEntry e, TraceEntry a, List<Difference> out) {
    if (e.status() != a.status()) {
      out.add(
          new Difference(
              Kind.CHANGED_STATUS,
              e,
              a,
              describe(e) + ": status " + e.status() + " -> " + a.status()));
    } else if (e.status() != ToolStatus.ERROR && !e.output().equals(a.output())) {
      // An ERROR output is the pipeline's explanation of the exception; on a replay the recorded
      // explanation is itself the exception message and gets wrapped again, so only the status is
      // comparable.
      out.add(new Difference(Kind.CHANGED_OUTPUT, e, a, describe(e) + ": output differs"));
    }
  }

  /**
   * Refines the raw REMOVED/ADDED ops: a removed call whose exact key was added elsewhere is one
   * REORDERED; after that, a removed and an added call of the same tool are one CHANGED_ARGS. The
   * nearest partner wins, so an adjacent remove/add pair is paired first.
   */
  private static List<Difference> refine(List<Difference> ops) {
    List<Difference> step1 = pair(ops, true);
    return pair(step1, false);
  }

  private static List<Difference> pair(List<Difference> ops, boolean exactKey) {
    List<Difference> result = new ArrayList<>(ops.size());
    boolean[] consumed = new boolean[ops.size()];
    for (int k = 0; k < ops.size(); k++) {
      if (consumed[k]) {
        continue;
      }
      Difference d = ops.get(k);
      if (d.kind() == Kind.REMOVED || d.kind() == Kind.ADDED) {
        int partner = findPartner(ops, consumed, k, d, exactKey);
        if (partner >= 0) {
          consumed[partner] = true;
          Difference p = ops.get(partner);
          TraceEntry e = d.kind() == Kind.REMOVED ? d.expected() : p.expected();
          TraceEntry a = d.kind() == Kind.ADDED ? d.actual() : p.actual();
          if (e == null || a == null) {
            result.add(d);
            continue;
          }
          if (exactKey) {
            result.add(
                new Difference(
                    Kind.REORDERED,
                    e,
                    a,
                    describe(e) + " moved from position " + e.seq() + " to " + a.seq()));
            compareMatched(e, a, result);
          } else {
            result.add(new Difference(Kind.CHANGED_ARGS, e, a, argDetail(e, a)));
          }
          continue;
        }
      }
      result.add(d);
    }
    return result;
  }

  private static int findPartner(
      List<Difference> ops, boolean[] consumed, int from, Difference d, boolean exactKey) {
    Kind wanted = d.kind() == Kind.REMOVED ? Kind.ADDED : Kind.REMOVED;
    TraceEntry self = d.kind() == Kind.REMOVED ? d.expected() : d.actual();
    if (self == null) {
      return -1;
    }
    for (int k = from + 1; k < ops.size(); k++) {
      if (consumed[k] || ops.get(k).kind() != wanted) {
        continue;
      }
      TraceEntry other = wanted == Kind.REMOVED ? ops.get(k).expected() : ops.get(k).actual();
      if (other == null) {
        continue;
      }
      boolean match =
          exactKey ? key(self).equals(key(other)) : self.toolName().equals(other.toolName());
      if (match) {
        return k;
      }
    }
    return -1;
  }

  private static String argDetail(TraceEntry e, TraceEntry a) {
    List<String> lines = ArgumentDiff.lines(e.canonicalArguments(), a.canonicalArguments());
    StringBuilder sb = new StringBuilder(e.toolName()).append(" arguments changed: ");
    sb.append(String.join("; ", lines));
    if (e.status() != a.status()) {
      sb.append(" (status ").append(e.status()).append(" -> ").append(a.status()).append(')');
    }
    return sb.toString();
  }

  private static String describe(TraceEntry e) {
    return e.toolName() + "(" + e.canonicalArguments() + ")";
  }

  private static String cell(@Nullable TraceEntry e) {
    return e == null ? "" : escapeCell(e.describe());
  }

  private static String escapeCell(String s) {
    return s.replace("|", "\\|").replace("\n", " ");
  }
}
