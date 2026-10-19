// DEMO ONLY. An in-memory stand-in for core's /api/briefs so the real Earned Autonomy screens can be shown
// with mock data and no server. Every number here is invented for the demo and the page says so; nothing in the
// product imports this file. Shapes follow types.ts (core's as-built wire shapes).

import sampleBrief from "../fixtures/brief.sample.json";
import type {
  ApplyJob, ApplyStep, Brief, BriefBinding, BriefDetail, BriefLedger, BriefListItem, BriefVersionSummary, JsonPatchOp,
  LedgerRun, PlanRequest, PlanResult, PlanStreamEvent, ReadinessRow, RehearsalSession, RehearsalStarted, Rung,
  TrustChange, TrustEvent, TrustEvidenceLine, TrustProposal, TuneResult,
} from "../types";

const clone = <T,>(x: T): T => JSON.parse(JSON.stringify(x));
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const NOISE = 0.04;
const GATE = 0.7;

const READINESS: ReadinessRow[] = [
  { kind: "SECRET", ref: "s1", label: "TALLY_API_KEY", state: "MISSING", reason: "No value is stored yet. The invoice fetch step cannot run without it.", actionHref: "/secrets?tour=field:secret-add&name=TALLY_API_KEY" },
  { kind: "SECRET", ref: "s2", label: "WHATSAPP_TOKEN", state: "WAITING_ON_ADMIN", reason: "Tenant secrets need an admin with integration permission. Everything else can be applied without it." },
  { kind: "KNOWLEDGE", ref: "k1", label: "Customer ledger notes and payment terms", state: "MISSING", reason: "Nothing uploaded yet. The agents will answer without your payment terms until you add them.", actionHref: "/knowledge?tour=field:knowledge-toggle" },
  { kind: "CONNECTOR", ref: "m1", label: "WhatsApp Business", state: "GATED", reason: "Connecting an MCP server is not part of your current plan.", unlocksAt: "PREMIUM" },
  { kind: "CHANNEL", ref: "c1", label: "WHATSAPP", state: "SIMULATED", reason: "Not connected, so replies are rehearsed and never leave the platform." },
  { kind: "PLAN_LIMIT", ref: "w1", label: "Workflows", state: "READY", reason: "One workflow fits within your plan." },
];

type Env = {
  id: string;
  brief: Brief;
  status: "DRAFT" | "APPLIED" | "FAILED";
  rung: Rung;
  version: number;
  versions: BriefVersionSummary[];
  bindings: BriefBinding[];
  runs: LedgerRun[];
  thumbs: { up: number; down: number };
  trustEvents: TrustEvent[];
  rehearsals: { started: number; session: RehearsalSession; personas: string[] }[];
  jobs: Map<string, { steps: ApplyStep[]; cursor: number; version: number }>;
};

let seq = 100;
const store = new Map<string, Env>();
const iso = (daysAgo: number) => new Date(Date.now() - daysAgo * 86400000).toISOString();

function seed() {
  store.clear();
  const brief = clone(sampleBrief) as unknown as Brief;
  const env: Env = {
    id: "demo-1",
    brief,
    status: "APPLIED",
    rung: "UNDER_WATCH",
    version: 3,
    versions: [
      { version: 1, parentVersion: null, note: "Planned from your brief", createdBy: "you", createdAt: iso(6) },
      { version: 2, parentVersion: 1, note: "Tuned: shorter reminders", createdBy: "you", createdAt: iso(4) },
      { version: 3, parentVersion: 2, note: "Tuned: escalate after 7 days", createdBy: "you", createdAt: iso(2) },
    ],
    bindings: bindingsFor(brief),
    runs: [
      { version: 1, runId: "r1", at: iso(6), score: 0.71, passThreshold: GATE, verdict: "PASSED", noiseFloor: NOISE, deltaVsPrev: null, significant: false, change: "BASELINE" },
      { version: 2, runId: "r2", at: iso(4), score: 0.73, passThreshold: GATE, verdict: "PASSED", noiseFloor: NOISE, deltaVsPrev: 0.02, significant: false, change: "WITHIN_NOISE" },
      { version: 3, runId: "r3", at: iso(2), score: 0.81, passThreshold: GATE, verdict: "PASSED", noiseFloor: NOISE, deltaVsPrev: 0.08, significant: true, change: "SIGNIFICANT_UP" },
    ],
    thumbs: { up: 9, down: 2 },
    trustEvents: [],
    rehearsals: [],
    jobs: new Map(),
  };
  store.set(env.id, env);
}
seed();

function bindingsFor(b: Brief): BriefBinding[] {
  const out: BriefBinding[] = [];
  b.agents.forEach((a, i) => out.push({ ref: a.ref, kind: "AGENT", entityId: `00000000-0000-4000-8000-0000000000a${i + 1}` }));
  b.workflows.forEach((w, i) => out.push({ ref: w.ref, kind: "WORKFLOW", entityId: `00000000-0000-4000-8000-0000000000b${i + 1}` }));
  return out;
}

function ledgerOf(e: Env): BriefLedger {
  const rated = e.thumbs.up + e.thumbs.down;
  return {
    briefId: e.id,
    state: "READY",
    reason: null,
    rung: e.rung,
    baseline: { runId: e.runs[0].runId, score: e.runs[0].score, at: e.runs[0].at, version: e.runs[0].version },
    noiseFloor: NOISE,
    runs: e.runs,
    thumbs: { up: e.thumbs.up, down: e.thumbs.down, rated, ratio: rated ? e.thumbs.up / rated : null },
    experiments: [],
    trustEvents: e.trustEvents,
  };
}

function detailOf(e: Env): BriefDetail {
  const last = e.runs[e.runs.length - 1];
  return {
    id: e.id,
    brief: clone({ ...e.brief, trust: { ...e.brief.trust, rung: e.rung } }),
    readiness: clone(READINESS),
    rung: e.rung,
    status: e.status,
    versions: clone(e.versions),
    bindings: clone(e.bindings),
    ledgerSummary: e.status === "APPLIED"
      ? { state: "READY", reason: null, baseline: e.runs[0].score, latest: last.score, lastDelta: last.deltaVsPrev, lastDeltaSignificant: last.significant, runs: e.runs.length, noiseFloor: NOISE }
      : null,
  };
}

function evidenceOf(e: Env): TrustEvidenceLine[] {
  const last = e.runs[e.runs.length - 1];
  const rated = e.thumbs.up + e.thumbs.down;
  const ratio = rated ? e.thumbs.up / rated : 0;
  return [
    { key: "eval", label: "Latest score is at or above the gate for 7 days", met: last.score >= GATE, detail: `Latest score ${last.score.toFixed(2)} against a gate of ${GATE.toFixed(2)}.` },
    { key: "guardrails", label: "No guardrail strikes in that window", met: true },
    { key: "feedback", label: "At least 10 rated replies with 70% or more thumbs up", met: rated >= 10 && ratio >= 0.7, detail: `Rated replies so far: ${rated}.` },
    { key: "plan", label: "Your plan runs evaluations", met: true },
  ];
}

const NEXT: Record<Rung, Rung | null> = { UNDER_WATCH: "TRUSTED", TRUSTED: "AUTONOMOUS", AUTONOMOUS: null };
const EFFECTS: Record<string, string[]> = {
  TRUSTED: ["Collections follow-up moves to the middle autonomy tier.", "The approval before Draft the reminder is removed.", "Sending on WhatsApp still waits for you: it cannot be undone."],
  AUTONOMOUS: ["Both agents move to the highest autonomy tier.", "Every approval is removed except the ones you marked as human-only.", "A quality gate now blocks any change that scores below the last run."],
  UNDER_WATCH: ["Both agents return to the lowest autonomy tier.", "An approval is put back before every node that acts."],
};

function applySteps(b: Brief): ApplyStep[] {
  const s: ApplyStep[] = [];
  let n = 0;
  const push = (kind: string, ref: string, error?: string) => s.push({ seq: ++n, kind, ref, status: "PENDING", entityId: null, error: error ?? null });
  (b.knowledge ?? []).forEach((k) => push("KNOWLEDGE", k.ref));
  (b.procedures ?? []).forEach((p) => push("PROCEDURE", p.ref));
  (b.mcpServers ?? []).forEach((m) => push("MCP", m.ref, "SKIPPED: waiting on an admin with integration permission"));
  b.agents.forEach((a) => push("AGENT", a.ref));
  b.workflows.forEach((w) => push("WORKFLOW", w.ref));
  (b.channels ?? []).forEach((c) => push("CHANNEL", c.ref, "NOTE: not connected yet - replies stay in rehearsal"));
  if (b.evalSet) push("EVAL_DATASET", "eval");
  push("TRUST", "rung", "NOTE: starts under watch - an approval sits before every action");
  return s;
}

export const mockBriefApi = {
  async planBrief(req: PlanRequest): Promise<PlanResult> {
    await sleep(600);
    return planFor(req);
  },

  async planBriefStream(req: PlanRequest, onEvent: (e: PlanStreamEvent) => void, signal?: AbortSignal): Promise<PlanResult> {
    const lines = [
      "Reading your brief...",
      "Two jobs here: chasing money, and answering order questions. That is two agents, not one.",
      "Checking the registry for a WhatsApp connector... found one, it needs an API key.",
      "Drafting the morning workflow: fetch overdue invoices, draft, then send.",
      "Marking what cannot be undone: sending a WhatsApp message.",
      "Writing 8 test cases so we can measure it from day one.",
    ];
    for (const text of lines) {
      if (signal?.aborted) throw new DOMException("aborted", "AbortError");
      onEvent({ type: "thinking", text });
      await sleep(650);
    }
    for (const name of ["agents", "workflows", "secrets", "evalSet"]) {
      onEvent({ type: "section", name });
      await sleep(180);
    }
    const result = planFor(req);
    onEvent({ type: "done", result });
    return result;
  },

  async createBrief(brief: Brief): Promise<{ id: string; version: number; status: string }> {
    await sleep(250);
    const id = `demo-${++seq}`;
    store.set(id, {
      id, brief: clone(brief), status: "DRAFT", rung: "UNDER_WATCH", version: 1,
      versions: [{ version: 1, parentVersion: null, note: "Planned from your brief", createdBy: "you", createdAt: new Date().toISOString() }],
      bindings: [], runs: [], thumbs: { up: 0, down: 0 }, trustEvents: [], rehearsals: [], jobs: new Map(),
    });
    return { id, version: 1, status: "DRAFT" };
  },

  async updateBrief(id: string, brief: Brief) {
    const e = must(id);
    e.brief = clone(brief);
    return { id, version: e.version, status: e.status };
  },

  async listBriefs(entityId?: string): Promise<BriefListItem[]> {
    await sleep(200);
    const entries = entityId ? [...store.values()].filter((e) => e.bindings.some((b) => b.entityId === entityId)) : [...store.values()];
    return entries.map((e) => {
      const last = e.runs[e.runs.length - 1];
      return { id: e.id, title: e.brief.title, rung: e.rung, status: e.status, currentVersion: e.version, baseline: e.runs[0]?.score ?? null, lastDelta: last?.deltaVsPrev ?? null, readinessSummary: null as BriefListItem["readinessSummary"], updatedAt: new Date().toISOString() };
    });
  },

  async getBrief(id: string): Promise<BriefDetail> {
    await sleep(250);
    return detailOf(must(id));
  },

  async briefReadiness(id: string) {
    must(id);
    const r = clone(READINESS);
    const count = (s: string) => r.filter((x) => x.state === s).length;
    return { readiness: r, summary: { ready: count("READY"), missing: count("MISSING"), waitingOnAdmin: count("WAITING_ON_ADMIN"), gated: count("GATED"), simulated: count("SIMULATED") } };
  },

  async applyBrief(id: string, opts: { background?: boolean; brief?: Brief; baseVersion?: number; note?: string } = {}) {
    const e = must(id);
    if (opts.brief) {
      if (opts.baseVersion != null && opts.baseVersion !== e.version) throw Object.assign(new Error("This envoy changed since the proposal was made. Ask again."), { status: 409 });
      e.brief = clone(opts.brief);
      e.version += 1;
      e.versions.push({ version: e.version, parentVersion: e.version - 1, note: opts.note ?? "Tuned with a sentence", createdBy: "you", createdAt: new Date().toISOString() });
    }
    const jobId = `job-${++seq}`;
    e.jobs.set(jobId, { steps: applySteps(e.brief), cursor: 0, version: e.version });
    return { jobId };
  },

  async getApplyJob(id: string, jobId: string): Promise<ApplyJob> {
    const e = must(id);
    const j = e.jobs.get(jobId);
    if (!j) throw new Error("No such apply job.");
    await sleep(350);
    if (j.cursor < j.steps.length) {
      const step = j.steps[j.cursor++];
      step.status = "DONE";
      if (!step.error?.startsWith("SKIPPED")) step.entityId = `00000000-0000-4000-8000-${String(1000 + step.seq).padStart(12, "0")}`;
    }
    const done = j.cursor >= j.steps.length;
    if (done && e.status !== "APPLIED") {
      e.status = "APPLIED";
      e.bindings = bindingsFor(e.brief);
      if (e.runs.length === 0) e.runs.push({ version: e.version, runId: "r1", at: new Date().toISOString(), score: 0.68, passThreshold: GATE, verdict: "FAILED", noiseFloor: NOISE, deltaVsPrev: null, significant: false, change: "BASELINE" });
    } else if (done && !e.runs.some((r) => r.version === j.version)) {
      const prev = e.runs[e.runs.length - 1];
      const score = Math.min(0.95, +(prev.score + 0.07).toFixed(2));
      e.runs.push({ version: j.version, runId: `r${e.runs.length + 1}`, at: new Date().toISOString(), score, passThreshold: GATE, verdict: "PASSED", noiseFloor: NOISE, deltaVsPrev: +(score - prev.score).toFixed(2), significant: score - prev.score > NOISE, change: score - prev.score > NOISE ? "SIGNIFICANT_UP" : "WITHIN_NOISE" });
    }
    return { jobId, status: done ? "SUCCEEDED" : "RUNNING", version: j.version, error: null, failedStep: null, steps: clone(j.steps), orphans: [] };
  },

  async tuneBrief(id: string, instruction: string): Promise<TuneResult> {
    const e = must(id);
    await sleep(900);
    const proposed = clone(e.brief);
    const a1 = proposed.agents[0];
    const before = a1.reasoningDepth;
    a1.reasoningDepth = "RIGOROUS";
    a1.systemPrompt += " End every reminder with how the customer can pay, and never repeat a reminder sent in the last 24 hours.";
    const diff: JsonPatchOp[] = [
      { op: "replace", path: "/agents/0/reasoningDepth", value: "RIGOROUS" },
      { op: "replace", path: "/agents/0/systemPrompt", value: a1.systemPrompt },
    ];
    return {
      proposedBrief: proposed,
      diff,
      narrative: `You said: "${instruction}". I made the collections agent plan, work and review (${before} to RIGOROUS) and taught it to end each reminder with how to pay. Nothing changes until you apply it, and it is measured against the last run.`,
      baseVersion: e.version,
    };
  },

  async rehearseBrief(id: string): Promise<RehearsalStarted> {
    const e = must(id);
    await sleep(400);
    const personas = (e.brief.rehearsal?.personas ?? []).map((p) => p.name);
    const sessionId = `sim-${++seq}`;
    const startedAt = Date.now();
    e.rehearsals.push({
      started: startedAt, personas,
      session: { label: "rehearsal", sessionId, status: "RUNNING", createdAt: new Date(startedAt).toISOString(), runs: [] },
    });
    return { label: "rehearsal", agentRef: e.brief.agents[0].ref, agentId: e.bindings.find((b) => b.ref === e.brief.agents[0].ref)?.entityId ?? "a1", sessionIds: [sessionId], personas, note: "Tools were not executed: nothing left the platform." };
  },

  async listRehearsals(id: string): Promise<RehearsalSession[]> {
    const e = must(id);
    await sleep(200);
    const verdicts: [RehearsalSession["runs"][number]["verdict"], string][] = [
      ["PASSED", "Stayed polite, quoted the right invoice and amount, offered a payment link."],
      ["PASSED", "Refused to threaten the customer and handed the case to a person."],
      ["INCONCLUSIVE", "Replied in English to a Hindi message; a human should check the wording."],
    ];
    return e.rehearsals.map((r) => {
      const elapsed = Date.now() - r.started;
      const runs = r.personas.map((persona, i) => {
        const finished = elapsed > 1500 + i * 900;
        const [verdict, reason] = verdicts[i % verdicts.length];
        return { runId: `${r.session.sessionId}-${i}`, persona, status: finished ? "COMPLETED" : "RUNNING", verdict: finished ? verdict : null, verdictReason: finished ? reason : null, turns: finished ? 4 + i : Math.min(3, Math.floor(elapsed / 500)) };
      });
      const all = runs.every((x) => x.status === "COMPLETED");
      if (all && !("counted" in r)) {
        (r as unknown as { counted: boolean }).counted = true;
        e.thumbs.up += 4;
      }
      return { ...r.session, status: all ? "COMPLETED" : "RUNNING", runs };
    });
  },

  async trustProposal(id: string): Promise<TrustProposal> {
    const e = must(id);
    await sleep(300);
    const target = NEXT[e.rung];
    if (!target) return { eligible: false, currentRung: e.rung, targetRung: null, evidence: [], diff: [], effects: [] };
    const evidence = evidenceOf(e);
    return { eligible: evidence.every((l) => l.met), currentRung: e.rung, targetRung: target, evidence, diff: [{ op: "replace", path: "/trust/rung", value: target }], effects: EFFECTS[target] };
  },

  async setTrustRung(id: string, rung: Rung): Promise<TrustChange> {
    const e = must(id);
    await sleep(500);
    const from = e.rung;
    const promote = ["UNDER_WATCH", "TRUSTED", "AUTONOMOUS"].indexOf(rung) > ["UNDER_WATCH", "TRUSTED", "AUTONOMOUS"].indexOf(from);
    e.rung = rung;
    e.version += 1;
    e.versions.push({ version: e.version, parentVersion: e.version - 1, note: promote ? `Promoted to ${rung}` : `Demoted to ${rung}`, createdBy: "you", createdAt: new Date().toISOString() });
    const evidence = evidenceOf(e);
    e.trustEvents.push({ kind: promote ? "PROMOTED" : "DEMOTED", fromRung: from, toRung: rung, version: e.version, actor: "you", at: new Date().toISOString(), evidence: { lines: evidence, note: promote ? "Promoted on the evidence above." : "Demoted by hand." } });
    return { kind: promote ? "PROMOTED" : "DEMOTED", fromRung: from, toRung: rung, version: e.version, effects: EFFECTS[rung], evidence };
  },

  async rollbackBrief(id: string, version: number) {
    const e = must(id);
    e.version += 1;
    e.versions.push({ version: e.version, parentVersion: version, note: `Rolled back to version ${version}`, createdBy: "you", createdAt: new Date().toISOString() });
    return { jobId: `job-${++seq}`, status: "SUCCEEDED" };
  },

  async getBriefLedger(id: string): Promise<BriefLedger> {
    await sleep(250);
    return ledgerOf(must(id));
  },
};

function must(id: string): Env {
  const e = store.get(id);
  if (!e) throw Object.assign(new Error("That envoy was not found."), { status: 404 });
  return e;
}

function planFor(req: PlanRequest): PlanResult {
  const brief = clone(sampleBrief) as unknown as Brief;
  const goal = (req.prompt || "").trim();
  if (goal.length >= 10) {
    brief.goal = goal;
    brief.title = goal.length > 56 ? `${goal.slice(0, 53).trim()}...` : goal;
  }
  return { brief, readiness: clone(READINESS), warnings: ["Demo planner: this brief is a fixed example, not generated from your words."] };
}

/** Reset to the seeded state (the demo page's "Start over"). */
export function resetMockBriefApi() {
  seq = 100;
  seed();
}

/**
 * Replace the client's briefs API with the in-memory one. The console imports `api` and calls it at use time, so patching the
 * object is enough. Returns a function that puts the real methods back: the demo pages call it when they unmount, so a visitor who
 * opens the demo and then navigates on to the real console is never left talking to the fake.
 */
export function installMockBriefApi(api: Record<string, unknown>): () => void {
  const originals: Record<string, unknown> = {};
  for (const key of Object.keys(mockBriefApi)) originals[key] = api[key];
  Object.assign(api, mockBriefApi);
  return () => {
    for (const key of Object.keys(originals)) {
      if (originals[key] === undefined) delete api[key];
      else api[key] = originals[key];
    }
  };
}
