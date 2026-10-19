// Run narration: the run told as a short story, deterministic, no LLM.
// Split out of runModel.ts (re-exported from there, so imports are unchanged).
import {
  ERROR_HANDLE,
  formatCostUsd,
  formatDuration,
  handleOf,
  linkVisits,
  type AwaitReason,
  type RunEdge,
  type RunModel,
  type TraceVisit,
} from "./runModel";

// ---------------------------------------------------------------------------
// narrate - the run told as a short story, deterministic, no LLM
// ---------------------------------------------------------------------------

export type NarrationTone = "neutral" | "good" | "warn" | "bad";

export type NarrationLine = {
  text: string;
  tone: NarrationTone;
  /** Node the sentence is about, so the UI can highlight / focus it. */
  nodeId: string | null;
  /** Timeline offset (ms since t0) to seek to when the sentence is chosen. */
  atMs: number | null;
};

export type NarrateOptions = {
  /** Canvas edges; unlock "routed to X" and error-path sentences. */
  edges?: readonly RunEdge[];
  /** Intl locale / IANA zone for clock times; defaults to the runtime's. */
  locale?: string;
  timeZone?: string;
};

const SUMMARY_MAX = 140;

function clip(text: string, max = SUMMARY_MAX): string {
  const t = text.replace(/\s+/g, " ").trim();
  return t.length > max ? `${t.slice(0, max - 1).trimEnd()}…` : t;
}

function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? "";
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

const AWAIT_PHRASE: Record<AwaitReason, string> = {
  approval: "Waiting for approval at",
  input: "Waiting for form input at",
  voice: "Waiting for the caller's keypress at",
  operator: "Paused by an operator before",
  other: "Waiting at",
};

export function narrateLines(
  model: RunModel,
  nodeLabel: (nodeId: string) => string | null | undefined,
  opts: NarrateOptions = {},
): NarrationLine[] {
  const labelOf = (v: TraceVisit): string =>
    (nodeLabel(v.nodeId) || "").trim() || v.label.trim() || v.nodeType || v.nodeId;
  const rel = (abs: number | null): number | null => (abs == null ? null : abs - model.t0);
  const anyRealTime = model.run.startMs != null || model.visits.some((v) => v.timing !== "estimated");
  const clock = (abs: number): string =>
    new Intl.DateTimeFormat(opts.locale, {
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      timeZone: opts.timeZone,
    }).format(new Date(abs));

  const lines: NarrationLine[] = [];
  const events: NarrationLine[] = [];
  const { visits, run } = model;

  // 1. Opening.
  const trigger = visits.find((v) => v.nodeType === "trigger") ?? null;
  const at = anyRealTime ? ` at ${clock(model.t0)}` : "";
  if (trigger) {
    const name = labelOf(trigger);
    const by = /^trigger$/i.test(name) ? "Triggered" : `Triggered by ${name}`;
    lines.push({ text: `${by}${at}`, tone: "neutral", nodeId: trigger.nodeId, atMs: 0 });
  } else if (run.requestedBy) {
    lines.push({ text: `Started by ${run.requestedBy}${at}`, tone: "neutral", nodeId: null, atMs: 0 });
  } else if (visits.length || run.startMs != null) {
    lines.push({ text: `Run started${at}`, tone: "neutral", nodeId: null, atMs: 0 });
  }

  // 2. Branches and routes, in time order.
  const links = opts.edges?.length ? linkVisits(model, opts.edges) : [];
  const edgeById: Record<string, RunEdge> = {};
  for (const e of opts.edges ?? []) edgeById[e.id] = e;
  const outDegree: Record<string, number> = {};
  for (const e of opts.edges ?? []) outDegree[e.source] = (outDegree[e.source] ?? 0) + 1;
  const narratedBranch = new Set<string>();
  for (const v of visits) {
    if (v.nodeType === "condition" && v.branch && v.endMs != null) {
      if (narratedBranch.has(v.nodeId)) continue;
      narratedBranch.add(v.nodeId);
      events.push({
        text: `${labelOf(v)} took the ${v.branch.toUpperCase()} path`,
        tone: "neutral", nodeId: v.nodeId, atMs: rel(v.endMs),
      });
    }
  }
  for (const l of links) {
    const handle = (handleOf(edgeById[l.edgeId]) ?? "").toLowerCase();
    if (handle === ERROR_HANDLE) {
      events.push({
        text: `${labelOf(l.from)} failed and recovered through its error path to ${labelOf(l.to)}`,
        tone: "warn", nodeId: l.from.nodeId, atMs: rel(l.from.endMs),
      });
      continue;
    }
    const from = l.from;
    if (from.nodeType === "condition" || narratedBranch.has(from.nodeId)) continue;
    if ((outDegree[from.nodeId] ?? 0) > 1 && handle) {
      narratedBranch.add(from.nodeId);
      events.push({ text: `${labelOf(from)} routed to ${labelOf(l.to)}`, tone: "neutral", nodeId: from.nodeId, atMs: rel(from.endMs) });
    }
  }

  // 3. Loops (first two repeated nodes).
  const looped = Object.values(model.byNode).filter((vs) => vs.length > 1 && vs[0].nodeType !== "engine").slice(0, 2);
  for (const vs of looped) {
    const timed = vs.filter((v) => v.durationMs != null);
    const avg = timed.length ? timed.reduce((s, v) => s + (v.durationMs as number), 0) / timed.length : null;
    events.push({
      text: `${labelOf(vs[0])} ran ${vs.length} times${avg != null ? ` (about ${formatDuration(avg)} each)` : ""}`,
      tone: "neutral", nodeId: vs[0].nodeId, atMs: rel(vs[1].startMs),
    });
  }

  // 4. First parallel burst: distinct nodes whose timed spans overlap.
  const spans = visits.filter((v) => v.timing !== "estimated" && v.endMs != null && v.endMs > v.startMs);
  for (const a of spans) {
    const group = spans.filter(
      (b) => b.nodeId !== a.nodeId && b.startMs < (a.endMs as number) && a.startMs < (b.endMs as number) && b.startMs >= a.startMs,
    );
    if (!group.length) continue;
    const names: string[] = [];
    for (const v of [a, ...group]) {
      const name = labelOf(v);
      if (!names.includes(name)) names.push(name);
    }
    if (names.length < 2) continue;
    const shown = names.length > 4 ? [...names.slice(0, 3), `${names.length - 3} more`] : names;
    events.push({ text: `${joinNames(shown)} ran in parallel`, tone: "neutral", nodeId: a.nodeId, atMs: rel(a.startMs) });
    break;
  }

  // 5. Skips (a degraded feature, not a failure).
  for (const v of visits.filter((x) => x.status === "skipped" && x.nodeType !== "engine").slice(0, 2)) {
    const why = clip(v.summary);
    events.push({ text: `${labelOf(v)} was skipped${why ? `: ${why}` : ""}`, tone: "warn", nodeId: v.nodeId, atMs: rel(v.startMs) });
  }

  // 6. Errors the run survived (a later visit or the run still completed).
  const lastError = [...visits].reverse().find((v) => v.status === "error") ?? null;
  const fatal = run.status === "failed" ? lastError : null;
  const recovered = new Set(links.filter((l) => (handleOf(edgeById[l.edgeId]) ?? "").toLowerCase() === ERROR_HANDLE).map((l) => l.from.seq));
  for (const v of visits) {
    if (v.status !== "error" || v === fatal || recovered.has(v.seq)) continue;
    const why = clip(v.summary);
    events.push({ text: `${labelOf(v)} hit an error but the run carried on${why ? `: ${why}` : ""}`, tone: "warn", nodeId: v.nodeId, atMs: rel(v.startMs) });
  }

  events.sort((a, b) => (a.atMs ?? 0) - (b.atMs ?? 0));
  lines.push(...events);

  // 7. Slowest step, when there is something to compare.
  const timedVisits = visits.filter((v) => v.durationMs != null && v.nodeType !== "trigger");
  if (timedVisits.length >= 2) {
    const slow = timedVisits.reduce((m, v) => ((v.durationMs as number) > (m.durationMs as number) ? v : m));
    if ((slow.durationMs as number) > 0) {
      const share = model.totalMs > 0 ? Math.round(((slow.durationMs as number) / model.totalMs) * 100) : 0;
      const shareText = share >= 20 && share <= 100 ? ` (${share}% of the run)` : "";
      lines.push({
        text: `${labelOf(slow)} took ${formatDuration(slow.durationMs)} - the slowest step${shareText}`,
        tone: "neutral", nodeId: slow.nodeId, atMs: rel(slow.startMs),
      });
    }
  }

  // 8. Model usage, only when core reported it.
  const { tokensIn, tokensOut, costUsd } = model.totals;
  if (tokensIn != null || tokensOut != null) {
    const tokens = (tokensIn ?? 0) + (tokensOut ?? 0);
    const cost = costUsd != null ? `, about ${formatCostUsd(costUsd, opts.locale)}` : "";
    lines.push({
      text: `Used ${new Intl.NumberFormat(opts.locale).format(tokens)} model tokens${cost}`,
      tone: "neutral", nodeId: null, atMs: null,
    });
  }

  // 9. Ending.
  const total = model.totalMs > 0 ? formatDuration(model.totalMs) : null;
  const steps = visits.filter((v) => v.nodeType !== "engine").length;
  const stepsText = `${steps} step${steps === 1 ? "" : "s"}`;
  const pausedVisit = [...visits].reverse().find((v) => v.status === "paused") ?? null;
  const runningVisit = [...visits].reverse().find((v) => v.endMs == null) ?? null;
  switch (run.status) {
    case "failed": {
      if (fatal) {
        const why = clip(fatal.summary || run.errorMessage || "");
        lines.push({ text: `Failed at ${labelOf(fatal)}${why ? `: ${why}` : ""}`, tone: "bad", nodeId: fatal.nodeId, atMs: rel(fatal.startMs) });
      } else {
        const why = clip(run.errorMessage || "");
        lines.push({ text: `Failed${why ? `: ${why}` : ""}`, tone: "bad", nodeId: null, atMs: model.totalMs });
      }
      break;
    }
    case "completed":
      lines.push({
        text: `Finished successfully${total ? ` in ${total}` : ""} across ${stepsText}`,
        tone: "good", nodeId: null, atMs: model.totalMs,
      });
      break;
    case "cancelled": {
      const c = [...visits].reverse().find((v) => v.status === "cancelled") ?? null;
      lines.push({
        text: `Cancelled${c ? ` at ${labelOf(c)}` : ""}${total ? ` after ${total}` : ""}`,
        tone: "warn", nodeId: c?.nodeId ?? null, atMs: c ? rel(c.startMs) : model.totalMs,
      });
      break;
    }
    case "paused":
    case "awaiting": {
      if (pausedVisit) {
        const reason = pausedVisit.awaiting ?? (run.status === "paused" ? "operator" : "other");
        lines.push({ text: `${AWAIT_PHRASE[reason]} ${labelOf(pausedVisit)}`, tone: "warn", nodeId: pausedVisit.nodeId, atMs: rel(pausedVisit.startMs) });
      } else {
        lines.push({ text: `Paused${total ? ` after ${total}` : ""}`, tone: "warn", nodeId: null, atMs: model.totalMs });
      }
      break;
    }
    case "running": {
      if (runningVisit) {
        const so = model.t1 > runningVisit.startMs ? ` (${formatDuration(model.t1 - runningVisit.startMs)} so far)` : "";
        lines.push({ text: `Still running - ${labelOf(runningVisit)} is working${so}`, tone: "neutral", nodeId: runningVisit.nodeId, atMs: rel(runningVisit.startMs) });
      } else {
        lines.push({ text: `Still running after ${stepsText}`, tone: "neutral", nodeId: null, atMs: model.totalMs });
      }
      break;
    }
    default:
      if (steps) lines.push({ text: `Ran ${stepsText}${total ? ` in ${total}` : ""}`, tone: "neutral", nodeId: null, atMs: model.totalMs });
  }

  return lines;
}

/** The run as plain sentences - see narrateLines for tone/seek metadata. */
export function narrate(
  model: RunModel,
  nodeLabel: (nodeId: string) => string | null | undefined,
  opts: NarrateOptions = {},
): string[] {
  return narrateLines(model, nodeLabel, opts).map((l) => l.text);
}
