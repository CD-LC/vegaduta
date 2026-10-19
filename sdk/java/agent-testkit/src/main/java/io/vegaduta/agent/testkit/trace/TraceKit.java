package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.testkit.GovernanceTestKit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-import entry point for record/replay regression tests.
 *
 * <p>Record a run through a {@link GovernanceTestKit}:
 *
 * <pre>{@code
 * Trace trace = TraceKit.record(GovernanceTestKit.strict().classification(...),
 *     kit -> agent.run(kit.govern(realTool)));
 * trace.save(golden);
 * }</pre>
 *
 * <p>Replay it in CI, deterministically and offline:
 *
 * <pre>{@code
 * Trace golden = Trace.load(goldenPath);
 * TraceKit.Recording run = TraceKit.recording(GovernanceTestKit.strict().classification(...));
 * agent.run(run.kit().governAll(ReplayToolbox.fromTrace(golden)));
 * assertThat(run.trace()).matches(golden);
 * }</pre>
 *
 * <p>{@link #recordOrReplay(Path, GovernanceTestKit.Builder, List, Consumer)} does both: when the
 * golden file is missing <em>and recording was asked for</em> (the {@value #RECORD_PROPERTY} system
 * property or {@value #RECORD_ENV} environment variable is {@code true}, or {@link
 * #recordOrReplay(Path, GovernanceTestKit.Builder, List, Consumer, RecordMode)} is called with
 * {@link RecordMode#RECORD_IF_MISSING}) it records the real tools and writes the golden; when the
 * golden is missing and recording was not asked for, it fails. A renamed resource directory or a
 * typo in the path therefore fails the test instead of silently turning it into a live run.
 *
 * <p>The recorder is attached through {@link GovernanceTestKit.Builder#customize(Consumer)}. That
 * mutates the builder passed in: a builder reused across recordings accumulates recorders, so
 * create the builder per test (or per {@code recording} call).
 */
@Experimental
public final class TraceKit {

  /** System property that allows {@code recordOrReplay} to record a missing golden. */
  public static final String RECORD_PROPERTY = "vegaduta.trace.record";

  /** Environment variable with the same meaning as {@link #RECORD_PROPERTY}. */
  public static final String RECORD_ENV = "VEGADUTA_TRACE_RECORD";

  private static final Logger log = LoggerFactory.getLogger(TraceKit.class);

  /** Whether {@code recordOrReplay} may record when the golden file is absent. */
  public enum RecordMode {
    /** Record a missing golden against the real tools (and say so loudly). */
    RECORD_IF_MISSING,
    /** Never record: a missing golden is a failure. */
    REPLAY_ONLY,
    /** {@link #RECORD_IF_MISSING} when {@value #RECORD_PROPERTY}/{@value #RECORD_ENV} is true. */
    FROM_ENVIRONMENT
  }

  private TraceKit() {}

  /** A test kit whose governance records into {@link #recorder()}. */
  public record Recording(GovernanceTestKit kit, TraceRecorder recorder) {

    public Governance governance() {
      return kit.governance();
    }

    /** A snapshot of what has been recorded so far. */
    public Trace trace() {
      return recorder.trace();
    }
  }

  /** Builds the kit with a fresh {@link TraceRecorder} attached as an observer. */
  public static Recording recording(GovernanceTestKit.Builder builder) {
    return recording(builder, new TraceRecorder());
  }

  public static Recording recording(GovernanceTestKit.Builder builder, Clock clock) {
    return recording(builder, new TraceRecorder(clock));
  }

  public static Recording recording(GovernanceTestKit.Builder builder, TraceRecorder recorder) {
    Objects.requireNonNull(builder, "builder");
    Objects.requireNonNull(recorder, "recorder");
    GovernanceTestKit kit = builder.customize(b -> b.observer(recorder)).build();
    return new Recording(kit, recorder);
  }

  /** Builds the kit with a recorder, runs {@code scenario} against it and returns the trace. */
  public static Trace record(
      GovernanceTestKit.Builder builder, Consumer<GovernanceTestKit> scenario) {
    Recording recording = recording(builder);
    scenario.accept(recording.kit());
    return recording.trace();
  }

  /**
   * Like {@link #record(GovernanceTestKit.Builder, Consumer)} but hands the scenario the raw {@link
   * Governance}.
   */
  public static Trace recordGovernance(
      GovernanceTestKit.Builder builder, Consumer<Governance> scenario) {
    return record(builder, kit -> scenario.accept(kit.governance()));
  }

  /**
   * Replays {@code golden} strictly and fails if the new trace differs; records it first when it is
   * missing and {@link RecordMode#FROM_ENVIRONMENT} says recording is allowed. {@code scenario}
   * receives the tools to offer the agent: the real ones when recording, replay tools (with the
   * real descriptors) when replaying.
   *
   * @return the trace of this run
   * @throws AssertionError when the golden is missing and recording was not enabled, or the replay
   *     differs (the first replay mismatch, with its diff, is appended to the message)
   */
  public static Trace recordOrReplay(
      Path golden,
      GovernanceTestKit.Builder builder,
      List<? extends Tool> realTools,
      Consumer<Scenario> scenario) {
    return recordOrReplay(golden, builder, realTools, scenario, RecordMode.FROM_ENVIRONMENT);
  }

  /**
   * {@link #recordOrReplay(Path, GovernanceTestKit.Builder, List, Consumer)} with an explicit mode.
   */
  public static Trace recordOrReplay(
      Path golden,
      GovernanceTestKit.Builder builder,
      List<? extends Tool> realTools,
      Consumer<Scenario> scenario,
      RecordMode mode) {
    Objects.requireNonNull(golden, "golden");
    Objects.requireNonNull(realTools, "realTools");
    Objects.requireNonNull(mode, "mode");
    Recording recording = recording(builder);
    if (!Files.exists(golden)) {
      if (!recordingAllowed(mode)) {
        throw new AssertionError(
            "golden trace "
                + golden
                + " does not exist and recording is off; run once with -D"
                + RECORD_PROPERTY
                + "=true (or "
                + RECORD_ENV
                + "=true) against the real tools to create it");
      }
      log.warn(
          "TraceKit: golden {} is missing; RECORDING against the real tools (side effects run)",
          golden);
      scenario.accept(new Scenario(recording.kit(), List.copyOf(realTools), false));
      Trace trace = recording.trace().withMetadata("mode", "recorded");
      trace.save(golden);
      return trace;
    }
    Trace expected = Trace.load(golden);
    List<Tool> replay = ReplayToolbox.fromTrace(expected, ReplayMode.STRICT, realTools);
    scenario.accept(new Scenario(recording.kit(), replay, true));
    Trace actual = recording.trace();
    try {
      TraceAssert.assertThat(actual).matches(expected);
    } catch (AssertionError e) {
      ReplayMismatchException mismatch = firstMismatch(replay);
      if (mismatch == null) {
        throw e;
      }
      AssertionError withDiff =
          new AssertionError(
              e.getMessage() + "\n\nFirst replay mismatch:\n" + mismatch.getMessage());
      withDiff.initCause(mismatch);
      throw withDiff;
    }
    return actual;
  }

  private static boolean recordingAllowed(RecordMode mode) {
    return switch (mode) {
      case RECORD_IF_MISSING -> true;
      case REPLAY_ONLY -> false;
      case FROM_ENVIRONMENT ->
          Boolean.parseBoolean(System.getProperty(RECORD_PROPERTY))
              || Boolean.parseBoolean(System.getenv(RECORD_ENV));
    };
  }

  private static ReplayMismatchException firstMismatch(List<Tool> tools) {
    for (Tool t : tools) {
      if (t instanceof ReplayTool r && r.firstMismatch().isPresent()) {
        return r.firstMismatch().get();
      }
    }
    return null;
  }

  /**
   * What a {@link #recordOrReplay} scenario gets.
   *
   * @param kit the governance under test, with the recorder attached
   * @param tools the tools to offer the agent (real or replay)
   * @param replaying {@code true} on the replay leg
   */
  public record Scenario(GovernanceTestKit kit, List<Tool> tools, boolean replaying) {}
}
