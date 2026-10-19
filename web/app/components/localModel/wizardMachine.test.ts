import { describe, expect, it } from "vitest";
import { probeFixture, recommendationFixture } from "./fixtures";
import {
  allChoices,
  canStart,
  exceedsRam,
  fitsMachine,
  groupChoices,
  initialWizardState,
  speedHint,
  stepIndex,
  wizardReducer,
  type WizardEvent,
  type WizardState,
} from "./wizardMachine";

const run = (events: WizardEvent[], from: WizardState = initialWizardState) => events.reduce(wizardReducer, from);
const loaded: WizardEvent = { type: "loaded", recommendation: recommendationFixture };

describe("wizardReducer", () => {
  it("loads the recommendation and preselects the recommended model", () => {
    const s = run([loaded]);
    expect(s.phase).toBe("ready");
    expect(s.selectedTag).toBe("qwen3:8b");
    expect(canStart(s)).toBe(true);
  });

  it("lists the recommended model first, then the whole catalog in its order, de-duplicated", () => {
    expect(allChoices(recommendationFixture).map((c) => c.tag)).toEqual([
      "qwen3:8b",
      "qwen3:4b",
      "llama3.2:3b",
      "gpt-oss:20b",
      "llama3.3:70b",
    ]);
  });

  it("falls back to recommended + alternatives for a backend that sends no catalog", () => {
    const rec = { ...recommendationFixture, catalog: [] as typeof recommendationFixture.catalog, alternatives: [recommendationFixture.recommended, ...recommendationFixture.alternatives] };
    expect(allChoices(rec).map((c) => c.tag)).toEqual(["qwen3:8b", "qwen3:4b"]);
  });

  it("groups the catalog into models that fit and models that need more memory, hiding none", () => {
    const { fits, needsMore } = groupChoices(recommendationFixture);
    expect(fits.map((c) => c.tag)).toEqual(["qwen3:8b", "qwen3:4b", "llama3.2:3b"]);
    expect(needsMore.map((c) => c.tag)).toEqual(["gpt-oss:20b", "llama3.3:70b"]);
    expect(fits.length + needsMore.length).toBe(recommendationFixture.catalog.length);
  });

  it("judges fit by the backend flag, then the budget, then raw memory for older backends", () => {
    const big = recommendationFixture.catalog[4];
    expect(fitsMachine(recommendationFixture, big)).toBe(false);
    const noFlag = { ...big, fits: undefined as unknown as boolean, ramGb: 11 };
    expect(fitsMachine(recommendationFixture, noFlag)).toBe(true);
    expect(fitsMachine({ ...recommendationFixture, budgetGb: 0 }, { ...noFlag, ramGb: 15 })).toBe(true);
    expect(fitsMachine({ ...recommendationFixture, budgetGb: 0 }, { ...noFlag, ramGb: 17 })).toBe(false);
  });

  it("rates speed as comfortable, tight or over the machine", () => {
    expect(speedHint(recommendationFixture, recommendationFixture.catalog[0])).toBe("comfortable");
    expect(speedHint(recommendationFixture, { ...recommendationFixture.recommended, ramGb: 11 })).toBe("tight");
    expect(speedHint(recommendationFixture, recommendationFixture.catalog[4])).toBe("over");
  });

  it("will not start a model that does not fit until the risk is acknowledged, and forgets that on re-select", () => {
    const picked = run([loaded, { type: "select", tag: "llama3.3:70b" }]);
    expect(canStart(picked)).toBe(false);
    expect(wizardReducer(picked, { type: "start" }).phase).toBe("ready");
    const ok = wizardReducer(picked, { type: "acknowledge", value: true });
    expect(canStart(ok)).toBe(true);
    expect(wizardReducer(ok, { type: "start" }).phase).toBe("pulling");
    const other = wizardReducer(ok, { type: "select", tag: "gpt-oss:20b" });
    expect(other.acknowledgedRisk).toBe(false);
    expect(wizardReducer(ok, { type: "select", tag: "llama3.3:70b" }).acknowledgedRisk).toBe(true);
  });

  it("selects only known tags, and only while ready", () => {
    const ready = run([loaded]);
    expect(wizardReducer(ready, { type: "select", tag: "qwen3:4b" }).selectedTag).toBe("qwen3:4b");
    expect(wizardReducer(ready, { type: "select", tag: "nope" }).selectedTag).toBe("qwen3:8b");
    const pulling = wizardReducer(ready, { type: "start" });
    expect(wizardReducer(pulling, { type: "select", tag: "qwen3:4b" }).selectedTag).toBe("qwen3:8b");
  });

  it("walks the full path: pull, probe, create, done", () => {
    const s = run([
      loaded,
      { type: "start" },
      { type: "pullProgress", percent: 42.5, message: "layers" },
      { type: "pullDone" },
      { type: "probed", probe: probeFixture },
      { type: "created", agentId: "a1", agentName: "Local" },
    ]);
    expect(s.phase).toBe("done");
    expect(s.probe).toEqual(probeFixture);
    expect(s.created).toEqual({ agentId: "a1", agentName: "Local" });
  });

  it("skips the download when the model is already installed", () => {
    const rec = { ...recommendationFixture, ollama: { ...recommendationFixture.ollama, installedTags: ["qwen3:8b"] } };
    const s = run([{ type: "loaded", recommendation: rec }, { type: "start" }]);
    expect(s.phase).toBe("probing");
  });

  it("blocks start while Ollama is unreachable", () => {
    const rec = { ...recommendationFixture, ollama: { ...recommendationFixture.ollama, reachable: false } };
    const ready = run([{ type: "loaded", recommendation: rec }]);
    expect(canStart(ready)).toBe(false);
    expect(wizardReducer(ready, { type: "start" }).phase).toBe("ready");
  });

  it("continues to create when the probe fails, and remembers it", () => {
    const s = run([loaded, { type: "start" }, { type: "pullDone" }, { type: "probeFailed" }]);
    expect(s.phase).toBe("creating");
    expect(s.probeFailed).toBe(true);
    expect(s.probe).toBeNull();
  });

  it("clamps progress and ignores stale events from other phases", () => {
    const pulling = run([loaded, { type: "start" }]);
    expect(wizardReducer(pulling, { type: "pullProgress", percent: 250, message: "" }).percent).toBe(100);
    expect(wizardReducer(pulling, { type: "pullProgress", percent: Number.NaN, message: "" }).percent).toBe(0);
    const ready = run([loaded]);
    expect(wizardReducer(ready, { type: "pullDone" })).toBe(ready);
    expect(wizardReducer(ready, { type: "created", agentId: "x", agentName: "y" })).toBe(ready);
  });

  it("records a failure on the failing step and recovers through detect, keeping the selection", () => {
    const failed = run([loaded, { type: "select", tag: "qwen3:4b" }, { type: "start" }, { type: "failed", step: "pull", message: "disk full" }]);
    expect(failed.phase).toBe("error");
    expect(failed.error).toEqual({ step: "pull", message: "disk full" });
    expect(stepIndex(failed)).toBe(1);
    const retried = wizardReducer(failed, { type: "detect" });
    expect(retried.phase).toBe("detecting");
    expect(retried.error).toBeNull();
    expect(wizardReducer(retried, loaded).selectedTag).toBe("qwen3:4b");
  });

  it("does not let a late failure overwrite a finished wizard", () => {
    const done = run([loaded, { type: "start" }, { type: "pullDone" }, { type: "probed", probe: probeFixture }, { type: "created", agentId: "a", agentName: "n" }]);
    expect(wizardReducer(done, { type: "failed", step: "create", message: "x" })).toBe(done);
  });

  it("falls back to the recommendation when a re-check no longer offers the previous selection", () => {
    const s = run([loaded, { type: "select", tag: "qwen3:4b" }]);
    const rec = { ...recommendationFixture, alternatives: [] as typeof recommendationFixture.alternatives, catalog: [recommendationFixture.recommended] };
    expect(wizardReducer({ ...s, phase: "detecting" }, { type: "loaded", recommendation: rec }).selectedTag).toBe("qwen3:8b");
  });
});

describe("exceedsRam", () => {
  it("warns only when the model needs more than the machine reports", () => {
    const [rec, big] = [recommendationFixture.recommended, recommendationFixture.catalog[4]];
    expect(exceedsRam(recommendationFixture, rec)).toBe(false);
    expect(exceedsRam(recommendationFixture, big)).toBe(true);
    expect(exceedsRam(null, big)).toBe(false);
  });
});
