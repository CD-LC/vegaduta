// State machine for the "Set up a local model" wizard. Pure and framework-free so the whole flow is unit-tested
// without React: detect hardware -> choose -> (pull) -> probe -> point the pool row at the model + first agent -> done.
// The async work (HTTP calls, polling) lives in useLocalModelWizard; this file only decides what each event means.
import type {
  LocalModelChoice,
  LocalModelProbe,
  LocalModelRecommendation,
} from "../../lib/api/localModels";

export type WizardPhase = "detecting" | "ready" | "pulling" | "probing" | "creating" | "done" | "error";

/** The step that failed, so the error panel can say what to retry. */
export type WizardStep = "detect" | "pull" | "probe" | "create";

export interface WizardState {
  phase: WizardPhase;
  recommendation: LocalModelRecommendation | null;
  selectedTag: string | null;
  /** A model that does not fit can still be pulled, but only after the person ticks the warning. Reset on select. */
  acknowledgedRisk: boolean;
  percent: number;
  pullMessage: string;
  probe: LocalModelProbe | null;
  /** Probing is advisory: when it fails the model is still usable, and the person is told the grade is unknown. */
  probeFailed: boolean;
  error: { step: WizardStep; message: string } | null;
  created: { agentId: string; agentName: string } | null;
}

export type WizardEvent =
  | { type: "detect" }
  | { type: "loaded"; recommendation: LocalModelRecommendation }
  | { type: "select"; tag: string }
  | { type: "acknowledge"; value: boolean }
  | { type: "start" }
  | { type: "pullProgress"; percent: number; message: string }
  | { type: "pullDone" }
  | { type: "probed"; probe: LocalModelProbe }
  | { type: "probeFailed" }
  | { type: "created"; agentId: string; agentName: string }
  | { type: "failed"; step: WizardStep; message: string };

export const initialWizardState: WizardState = {
  phase: "detecting",
  recommendation: null,
  selectedTag: null,
  acknowledgedRisk: false,
  percent: 0,
  pullMessage: "",
  probe: null,
  probeFailed: false,
  error: null,
  created: null,
};

/**
 * Every model on offer, recommended first, de-duplicated by tag. Uses the whole catalog when the backend
 * sends one (every row, fitting or not); an older backend without `catalog` still yields recommended + alternatives.
 */
export function allChoices(rec: LocalModelRecommendation | null): LocalModelChoice[] {
  if (!rec) return [];
  const seen = new Set<string>();
  const rows = rec.catalog?.length ? rec.catalog : rec.alternatives;
  return [rec.recommended, ...rows].filter((c) => (seen.has(c.tag) ? false : (seen.add(c.tag), true)));
}

/** True when the model is within the machine's memory budget. Falls back to raw RAM when the backend sent no flag. */
export function fitsMachine(rec: LocalModelRecommendation | null, choice: LocalModelChoice | null): boolean {
  if (!rec || !choice) return false;
  if (typeof choice.fits === "boolean") return choice.fits;
  if (typeof rec.budgetGb === "number" && rec.budgetGb > 0) return choice.ramGb <= rec.budgetGb;
  return !(rec.hardware.ramGb > 0 && choice.ramGb > rec.hardware.ramGb);
}

/** The two groups the picker shows: models that fit this machine, and the rest (never hidden, just labelled). */
export function groupChoices(rec: LocalModelRecommendation | null): { fits: LocalModelChoice[]; needsMore: LocalModelChoice[] } {
  const fits: LocalModelChoice[] = [];
  const needsMore: LocalModelChoice[] = [];
  for (const c of allChoices(rec)) (fitsMachine(rec, c) ? fits : needsMore).push(c);
  return { fits, needsMore };
}

export function selectedChoice(state: WizardState): LocalModelChoice | null {
  return allChoices(state.recommendation).find((c) => c.tag === state.selectedTag) ?? null;
}

export function isInstalled(rec: LocalModelRecommendation | null, tag: string | null): boolean {
  return !!rec && !!tag && rec.ollama.installedTags.includes(tag);
}

/** True when the chosen model needs more memory than this machine can give it. Advisory until "start". */
export function exceedsRam(rec: LocalModelRecommendation | null, choice: LocalModelChoice | null): boolean {
  return !!rec && !!choice && !fitsMachine(rec, choice);
}

/** A rough speed expectation for the detail card: comfortable, tight, or more than the machine has. */
export type SpeedHint = "comfortable" | "tight" | "over";
export function speedHint(rec: LocalModelRecommendation | null, choice: LocalModelChoice | null): SpeedHint {
  if (!rec || !choice) return "over";
  if (!fitsMachine(rec, choice)) return "over";
  const budget = rec.budgetGb > 0 ? rec.budgetGb : rec.hardware.ramGb;
  return budget > 0 && choice.ramGb > budget * 0.75 ? "tight" : "comfortable";
}

/** Whether the "start" action is allowed right now (Ollama reachable, a model chosen, a too-big model acknowledged, wizard idle). */
export function canStart(state: WizardState): boolean {
  const choice = selectedChoice(state);
  return (
    state.phase === "ready" &&
    !!state.recommendation?.ollama.reachable &&
    !!choice &&
    (fitsMachine(state.recommendation, choice) || state.acknowledgedRisk)
  );
}

const clampPercent = (n: number) => (Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : 0);

export function wizardReducer(state: WizardState, event: WizardEvent): WizardState {
  switch (event.type) {
    case "detect":
      // Keep a previous selection across a re-check ("I just installed Ollama").
      return { ...initialWizardState, selectedTag: state.selectedTag };
    case "loaded": {
      const keep = allChoices(event.recommendation).some((c) => c.tag === state.selectedTag);
      return {
        ...state,
        phase: "ready",
        recommendation: event.recommendation,
        selectedTag: keep ? state.selectedTag : event.recommendation.recommended.tag,
        acknowledgedRisk: false,
        error: null,
      };
    }
    case "select":
      if (state.phase !== "ready") return state;
      return allChoices(state.recommendation).some((c) => c.tag === event.tag)
        ? { ...state, selectedTag: event.tag, acknowledgedRisk: event.tag === state.selectedTag ? state.acknowledgedRisk : false }
        : state;
    case "acknowledge":
      if (state.phase !== "ready") return state;
      return { ...state, acknowledgedRisk: event.value };
    case "start":
      if (!canStart(state)) return state;
      return isInstalled(state.recommendation, state.selectedTag)
        ? { ...state, phase: "probing", percent: 100, error: null, probe: null, probeFailed: false }
        : { ...state, phase: "pulling", percent: 0, pullMessage: "", error: null, probe: null, probeFailed: false };
    case "pullProgress":
      if (state.phase !== "pulling") return state;
      return { ...state, percent: clampPercent(event.percent), pullMessage: event.message };
    case "pullDone":
      if (state.phase !== "pulling") return state;
      return { ...state, phase: "probing", percent: 100 };
    case "probed":
      if (state.phase !== "probing") return state;
      return { ...state, phase: "creating", probe: event.probe, probeFailed: false };
    case "probeFailed":
      if (state.phase !== "probing") return state;
      return { ...state, phase: "creating", probe: null, probeFailed: true };
    case "created":
      if (state.phase !== "creating") return state;
      return { ...state, phase: "done", created: { agentId: event.agentId, agentName: event.agentName } };
    case "failed":
      if (state.phase === "done") return state;
      // A failed detect leaves nothing to show; every other failure keeps the recommendation so "try again" is cheap.
      return { ...state, phase: "error", error: { step: event.step, message: event.message } };
    default:
      return state;
  }
}

/** Linear step index (0-3) for the progress rail; a failed wizard sits on the step that failed. */
export function stepIndex(state: Pick<WizardState, "phase" | "error">): number {
  switch (state.phase) {
    case "detecting":
    case "ready":
      return 0;
    case "pulling":
      return 1;
    case "probing":
      return 2;
    case "creating":
    case "done":
      return 3;
    case "error":
      return { detect: 0, pull: 1, probe: 2, create: 3 }[state.error?.step ?? "detect"];
  }
}
