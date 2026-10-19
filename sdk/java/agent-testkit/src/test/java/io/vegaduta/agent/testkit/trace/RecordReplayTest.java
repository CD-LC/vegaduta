package io.vegaduta.agent.testkit.trace;

import static io.vegaduta.agent.testkit.trace.TraceAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import io.vegaduta.agent.testkit.FakeTool;
import io.vegaduta.agent.testkit.GovernanceTestKit;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordReplayTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);

  private static GovernanceTestKit.Builder strictKit() {
    return GovernanceTestKit.strict()
        .classification(ToolClassification.builder("weather").build())
        .classification(ToolClassification.builder("quote").build());
  }

  /** A nondeterministic tool: every call answers differently, like a live API. */
  private static FakeTool liveQuote() {
    AtomicInteger counter = new AtomicInteger();
    return FakeTool.of("quote", args -> "price=" + (100 + counter.incrementAndGet()));
  }

  @Test
  void aRunRecordedThroughGovernanceReplaysDeterministically(@TempDir Path dir) {
    RunContext ctx = RunContext.builder().principal("alice").runId("r1").agentId("sales").build();
    TraceKit.Recording rec = TraceKit.recording(strictKit(), FIXED);
    FakeTool weather = FakeTool.of("weather", args -> "sunny in " + args);
    FakeTool quote = liveQuote();
    rec.kit().invoke(weather, "{\"city\":\"Oslo\"}", ctx);
    rec.kit().invoke(quote, "{\"sku\":\"A\"}", ctx);
    rec.kit().invoke(quote, "{\"sku\":\"A\"}", ctx);
    rec.kit().invoke(FakeTool.echo("unknown"), "{}", ctx); // denied in STRICT: unclassified

    Trace recorded = rec.trace();
    assertThat(recorded)
        .hasSize(4)
        .hasCallsInOrder("weather", "quote", "quote", "unknown")
        .hasCallCount("quote", 2)
        .callArgsContain("weather", "\"city\":\"Oslo\"")
        .hasStatus("unknown", ToolStatus.DENIED)
        .noCallAfterStatus(ToolStatus.DENIED);
    Assertions.assertThat(recorded.entries().get(0).at()).isEqualTo(FIXED.instant());
    Assertions.assertThat(recorded.entries().get(0).agentId()).isEqualTo("sales");

    Path golden = dir.resolve("golden.jsonl");
    recorded.save(golden);

    // Replay: the live tool is gone, the replay tools answer exactly as recorded, twice over.
    Trace loaded = Trace.load(golden);
    for (int round = 0; round < 2; round++) {
      List<Tool> tools = ReplayToolbox.fromTrace(loaded);
      Assertions.assertThat(tools)
          .extracting(Tool::name)
          .containsExactly("weather", "quote", "unknown");
      TraceKit.Recording replay = TraceKit.recording(strictKit(), FIXED);
      Map<String, Tool> byName = new java.util.HashMap<>();
      tools.forEach(t -> byName.put(t.name(), t));
      ToolResult w = replay.kit().invoke(byName.get("weather"), "{ \"city\" : \"Oslo\" }", ctx);
      ToolResult q1 = replay.kit().invoke(byName.get("quote"), "{\"sku\":\"A\"}", ctx);
      ToolResult q2 = replay.kit().invoke(byName.get("quote"), "{\"sku\":\"A\"}", ctx);
      ToolResult q3 = replay.kit().invoke(byName.get("quote"), "{\"sku\":\"A\"}", ctx);
      replay.kit().invoke(byName.get("unknown"), "{}", ctx);

      Assertions.assertThat(w.output()).isEqualTo("sunny in {\"city\":\"Oslo\"}");
      Assertions.assertThat(q1.output()).isEqualTo("price=101");
      Assertions.assertThat(q2.output()).isEqualTo("price=102");
      Assertions.assertThat(q3.output())
          .as("exhausted: last entry served again")
          .isEqualTo("price=102");
      // The replayed run matches the golden trace except for the whitespace-only argument change
      // (canonicalised away) and the deliberate extra quote call.
      TraceDiff diff = TraceDiff.compare(loaded, replay.trace());
      Assertions.assertThat(diff.differences()).hasSize(1);
      Assertions.assertThat(diff.differences().get(0).kind()).isEqualTo(TraceDiff.Kind.ADDED);
    }
  }

  @Test
  void strictReplayThrowsWithADiffForAnUnrecordedCall() {
    Trace trace =
        Trace.of(
            List.of(
                TraceRoundTripTest.entry(
                    1, "weather", "{\"city\":\"Oslo\",\"unit\":\"C\"}", ToolStatus.OK, "sunny"),
                TraceRoundTripTest.entry(
                    2, "weather", "{\"city\":\"Rome\"}", ToolStatus.OK, "hot")));
    ReplayTool tool = ReplayTool.of(trace, "weather");
    RunContext ctx = RunContext.anonymous();

    assertThatThrownBy(() -> tool.call("{\"unit\":\"F\",\"city\":\"Oslo\"}", ctx))
        .isInstanceOf(ReplayMismatchException.class)
        .hasMessageContaining(
            "No recorded call matches weather({\"city\":\"Oslo\",\"unit\":\"F\"})")
        .hasMessageContaining("#1 weather({\"city\":\"Oslo\",\"unit\":\"C\"}) -> OK   <- closest")
        .hasMessageContaining("#2 weather({\"city\":\"Rome\"}) -> OK")
        .hasMessageContaining("~ unit: \"C\" -> \"F\"")
        .satisfies(
            e -> {
              ReplayMismatchException m = (ReplayMismatchException) e;
              Assertions.assertThat(m.toolName()).isEqualTo("weather");
              Assertions.assertThat(m.recordedArguments()).hasSize(2);
            });

    assertThatThrownBy(() -> ReplayTool.of(trace, "nope").call("{}", RunContext.anonymous()))
        .isInstanceOf(ReplayMismatchException.class)
        .hasMessageContaining("no calls to 'nope' at all");
  }

  @Test
  void lenientReplayFallsBackToTheNextRecordedEntryForTheTool() {
    Trace trace =
        Trace.of(
            List.of(
                TraceRoundTripTest.entry(1, "q", "{\"a\":1}", ToolStatus.OK, "one"),
                TraceRoundTripTest.entry(2, "q", "{\"a\":2}", ToolStatus.OK, "two"),
                TraceRoundTripTest.entry(3, "q", "{\"a\":3}", ToolStatus.ERROR, "boom")));
    ReplayTool tool = ReplayTool.of(trace, "q", ReplayMode.LENIENT);
    RunContext ctx = RunContext.anonymous();

    Assertions.assertThat(tool.call("{\"a\":2}", ctx)).as("exact match wins").isEqualTo("two");
    Assertions.assertThat(tool.call("{\"a\":99}", ctx)).as("next unconsumed").isEqualTo("one");
    assertThatThrownBy(() -> tool.call("{\"a\":98}", ctx))
        .as("recorded ERROR entries rethrow")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("boom");
    assertThatThrownBy(() -> tool.call("{\"a\":97}", ctx))
        .isInstanceOf(ReplayMismatchException.class)
        .hasMessageContaining("no recorded entries are left");
    Assertions.assertThat(tool.servedArguments())
        .as("the unmatched call is not counted as served")
        .hasSize(3);
  }

  @Test
  void recordingToolCapturesRawOutputBelowGovernance() {
    TraceRecorder recorder = new TraceRecorder(FIXED);
    Tool wrapped = RecordingTool.wrap(FakeTool.returning("weather", "raw"), recorder);
    Tool failing =
        RecordingTool.wrap(FakeTool.failing("quote", new IllegalStateException("down")), recorder);
    GovernanceTestKit kit = strictKit().build();

    kit.invoke(wrapped, "{\"city\":\"Oslo\"}");
    ToolResult r = kit.invoke(failing, "{}");

    Assertions.assertThat(r.status()).isEqualTo(ToolStatus.ERROR);
    assertThat(recorder)
        .hasSize(2)
        .hasStatus("weather", ToolStatus.OK)
        .hasStatus("quote", ToolStatus.ERROR);
    Assertions.assertThat(recorder.trace().entries().get(0).output()).isEqualTo("raw");
    Assertions.assertThat(recorder.trace().entries().get(1).output()).contains("down");
    Assertions.assertThat(RecordingTool.wrap(wrapped, recorder)).isSameAs(wrapped);
  }

  @Test
  void recorderObserverCapturesErrorsThrownOutOfThePipeline() {
    TraceRecorder recorder = new TraceRecorder(FIXED);
    ToolCall call = ToolCall.of("x", "{}");
    var scope = recorder.onStart(call, RunContext.ofRun("r"));
    scope.onError(new IllegalArgumentException("bad"));
    scope.onResult(ToolResult.ok("ignored: already done"));
    scope.close();
    Trace t = recorder.metadata("model", "m").trace();
    Assertions.assertThat(t.entries()).hasSize(1);
    Assertions.assertThat(t.entries().get(0).status()).isEqualTo(ToolStatus.ERROR);
    Assertions.assertThat(t.entries().get(0).reason()).isEqualTo("exception");
    Assertions.assertThat(t.metadata()).containsEntry("model", "m");
    recorder.reset();
    Assertions.assertThat(recorder.size()).isZero();
  }

  @Test
  void recordOrReplayWritesTheGoldenFileThenReplaysAgainstIt(@TempDir Path dir) {
    Path golden = dir.resolve("quote.jsonl");
    RunContext ctx = RunContext.ofRun("r");
    FakeTool live = liveQuote();

    // A missing golden is a failure unless recording was asked for explicitly.
    assertThatThrownBy(
            () ->
                TraceKit.recordOrReplay(
                    golden,
                    strictKit(),
                    List.of(live),
                    s -> s.kit().invoke(s.tools().get(0), "{\"sku\":\"A\"}", ctx),
                    TraceKit.RecordMode.REPLAY_ONLY))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("recording is off");
    Assertions.assertThat(live.callCount()).isZero();

    Trace first =
        TraceKit.recordOrReplay(
            golden,
            strictKit(),
            List.of(live),
            s -> {
              Assertions.assertThat(s.replaying()).isFalse();
              s.kit().invoke(s.tools().get(0), "{\"sku\":\"A\"}", ctx);
            },
            TraceKit.RecordMode.RECORD_IF_MISSING);
    Assertions.assertThat(golden).exists();
    Assertions.assertThat(first.metadata()).containsEntry("mode", "recorded");

    Trace second =
        TraceKit.recordOrReplay(
            golden,
            strictKit(),
            List.of(live),
            s -> {
              Assertions.assertThat(s.replaying()).isTrue();
              Assertions.assertThat(s.tools().get(0)).isInstanceOf(ReplayTool.class);
              Assertions.assertThat(s.tools().get(0).descriptor()).isEqualTo(live.descriptor());
              ToolResult r = s.kit().invoke(s.tools().get(0), "{\"sku\":\"A\"}", ctx);
              Assertions.assertThat(r.output()).isEqualTo("price=101");
            });
    Assertions.assertThat(live.callCount())
        .as("the live tool is not called on replay")
        .isEqualTo(1);
    Assertions.assertThat(second.canonicalForm()).isEqualTo(first.canonicalForm());

    assertThatThrownBy(
            () ->
                TraceKit.recordOrReplay(
                    golden,
                    strictKit(),
                    List.of(live),
                    s -> s.kit().invoke(s.tools().get(0), "{\"sku\":\"B\"}", ctx)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("CHANGED_ARGS")
        .hasMessageContaining("First replay mismatch");
  }

  @Test
  void aFailingToolRecordedThroughTheKitReplaysToTheSameOutput(@TempDir Path dir) {
    Path golden = dir.resolve("failing.jsonl");
    RunContext ctx = RunContext.ofRun("r");
    Tool broken =
        new Tool() {
          @Override
          public ToolDescriptor descriptor() {
            return ToolDescriptor.of("q", "always fails");
          }

          @Override
          public String call(String argumentsJson, RunContext context) {
            throw new IllegalStateException("down");
          }
        };
    GovernanceTestKit.Builder kit =
        GovernanceTestKit.strict().classification(ToolClassification.readOnly("q"));
    Trace first =
        TraceKit.recordOrReplay(
            golden,
            kit,
            List.of(broken),
            s -> s.kit().invoke(s.tools().get(0), "{}", ctx),
            TraceKit.RecordMode.RECORD_IF_MISSING);
    Assertions.assertThat(first.entries().get(0).status()).isEqualTo(ToolStatus.ERROR);

    Trace second =
        TraceKit.recordOrReplay(
            golden,
            GovernanceTestKit.strict().classification(ToolClassification.readOnly("q")),
            List.of(broken),
            s -> {
              ToolResult r = s.kit().invoke(s.tools().get(0), "{}", ctx);
              Assertions.assertThat(r.status()).isEqualTo(ToolStatus.ERROR);
            });
    Assertions.assertThat(second.entries().get(0).status()).isEqualTo(ToolStatus.ERROR);
    assertThat(second).matches(first);
  }

  @Test
  void realToolsNeverCalledStillGetReplayToolsInRealOrder() {
    TraceRecorder recorder = new TraceRecorder();
    FakeTool live = liveQuote();
    recorder.record(
        ToolCall.of("quote", "{}"),
        RunContext.ofRun("r"),
        ToolStatus.OK,
        "x",
        null,
        java.time.Instant.EPOCH);
    Tool never =
        new Tool() {
          @Override
          public ToolDescriptor descriptor() {
            return ToolDescriptor.of("refund", "never recorded");
          }

          @Override
          public String call(String argumentsJson, RunContext context) {
            return "";
          }
        };
    List<Tool> replay =
        ReplayToolbox.fromTrace(recorder.trace(), ReplayMode.STRICT, List.of(never, live));
    Assertions.assertThat(replay).hasSize(2);
    Assertions.assertThat(replay.get(0).name()).isEqualTo("refund");
    Assertions.assertThat(replay.get(1).name()).isEqualTo("quote");
    assertThatThrownBy(() -> replay.get(0).call("{}", RunContext.ofRun("r")))
        .isInstanceOf(ReplayMismatchException.class)
        .hasMessageContaining("no calls to 'refund' at all");
  }
}
