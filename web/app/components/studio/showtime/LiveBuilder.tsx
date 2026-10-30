"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, Check, Loader2, Sparkles } from "lucide-react";
import {
  studio,
  runWorkflow,
  type StudioAgent,
  type StudioEvent,
  type StudioPalette,
  type StudioWorkflow,
  type WorkflowStep,
} from "../studioClient";
import { toolLabel } from "../studioContent";
import { createShare } from "../../try/demoClient";
import { qrPath } from "../../../lib/qr";
import { getMobileApkDownload } from "../../../lib/mobileApp";
import AppPhone, { type AppStep, type AppView } from "./AppPhone";
import { AGENT, APP } from "./script";
import { PhoneFit } from "./PhoneFit";
import { Field, Head, RegistryStep, ShipStep, type McpSearch } from "./liveBuilderSteps";
import { useTranslations } from "../../../lib/i18n";

// Build it live: the trailer's six moves, for real, on the real demo backend.
// Same stage, same phone - but the app is the visitor's (they can rename it), the
// tools come from the platform's real palette (search, attach), the agent is a
// real agent, the workflow is a real workflow with a real approval pause, and the
// phone's button runs it. Every run counts against the same fair-use gateways as
// the studio; nothing here bypasses them.

type Step = "app" | "registry" | "agent" | "flow" | "ship";
const STEPS: { id: Step; label: string }[] = [
  { id: "app", label: "Design the app" },
  { id: "registry", label: "Find tools" },
  { id: "agent", label: "Attach an agent" },
  { id: "flow", label: "Wire & run" },
  { id: "ship", label: "Put it on a phone" },
];

type Xray = {
  id: number;
  kind: "tool" | "step" | "plan" | "note" | "error";
  text: string;
  done?: boolean;
  tool?: string;
};

type Props = {
  token: string;
  signupHref: string;
  onBudget: (used: number, limit: number) => void;
  onClose: () => void;
};

export default function LiveBuilder({ token, signupHref, onBudget, onClose }: Props) {
    const tHC = useTranslations("hcComponentsStudioShowtimeLiveBuilder");
  const apk = getMobileApkDownload();
  const [step, setStep] = useState<Step>("app");
  const [appTitle, setAppTitle] = useState(APP.title);
  const [tagline, setTagline] = useState(APP.tagline);
  const [button, setButton] = useState(APP.button);
  const [crop, setCrop] = useState(APP.crop);

  const [palette, setPalette] = useState<StudioPalette | null>(null);
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<string[]>([]);
  const [mcp, setMcp] = useState<McpSearch>({
    loading: false,
    hits: [],
    unavailable: false,
  });

  const [mission, setMission] = useState(AGENT.mission);
  const [agent, setAgent] = useState<StudioAgent | null>(null);
  const [workflow, setWorkflow] = useState<StudioWorkflow | null>(null);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [running, setRunning] = useState(false);
  const [xray, setXray] = useState<Xray[]>([]);
  const [steps, setSteps] = useState<AppStep[]>([]);
  const [answer, setAnswer] = useState("");
  const [push, setPush] = useState<null | "ask" | "approved">(null);
  const [runId, setRunId] = useState<string | null>(null);
  const [ran, setRan] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const idRef = useRef(0);

  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void studio.palette(token).then((r) => {
      if (!cancelled && r.ok) setPalette(r.value);
    });
    return () => {
      cancelled = true;
      abortRef.current?.abort();
    };
  }, [token]);

  // Live MCP registry search, debounced. Searching is real; connecting is a workspace feature.
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setMcp({ loading: false, hits: [], unavailable: false });
      return undefined;
    }
    setMcp((m) => ({ ...m, loading: true }));
    const id = window.setTimeout(() => {
      void studio.searchMcp(token, q).then((r) => {
        setMcp(
          r.ok
            ? { loading: false, hits: r.value.results.slice(0, 4), unavailable: r.value.unavailable }
            : { loading: false, hits: [], unavailable: true }
        );
      });
    }, 450);
    return () => window.clearTimeout(id);
  }, [query, token]);

  const tools = useMemo(() => {
    const all = palette?.tools ?? [];
    const q = query.trim().toLowerCase();
    return q ? all.filter((t) => `${t.label} ${t.name} ${t.description}`.toLowerCase().includes(q)) : all;
  }, [palette, query]);

  const toggle = (name: string) => setPicked((p) => (p.includes(name) ? p.filter((x) => x !== name) : [...p, name]));

  const createAgent = async () => {
    setBusy(true);
    setError(null);
    const spec = { name: AGENT.name, mission, tools: picked, reasoningDepth: "CAREFUL", guardrails: [] as string[] };
    const res = agent ? await studio.updateAgent(token, agent.id, spec) : await studio.createAgent(token, spec);
    setBusy(false);
    if (!res.ok) {
      setError(res.error.message);
      return;
    }
    setAgent(res.value);
    setStep("flow");
  };

  const ensureWorkflow = async (): Promise<StudioWorkflow | null> => {
    if (!agent) return null;
    const definition = {
      steps: [
        { id: "t", type: "trigger", label: "Button tapped", config: { inputLabel: "Crop and question" } },
        { id: "a", type: "agent", label: AGENT.name, config: { agentId: agent.id, instruction: "{{input}}" } },
        {
          id: "ok",
          type: "approval",
          label: "Ramesh approves",
          config: { message: "Send this advice to Ramesh's phone?" },
        },
        { id: "out", type: "output", label: "Answer on his phone", config: {} },
      ] as WorkflowStep[],
    };
    // Recreated when the agent changed (its id is baked into the step); otherwise reused.
    const stale = workflow && JSON.stringify(workflow.definition).includes(agent.id) === false;
    if (workflow && !stale) return workflow;
    const res = workflow
      ? await studio.updateWorkflow(token, workflow.id, `${appTitle} flow`, definition)
      : await studio.createWorkflow(token, `${appTitle} flow`, definition);
    if (!res.ok) {
      setError(res.error.message);
      return null;
    }
    setWorkflow(res.value);
    return res.value;
  };

  const onEvent = useCallback(
    (e: StudioEvent) => {
      const add = (x: Omit<Xray, "id">) => setXray((l) => [...l, { ...x, id: ++idRef.current }]);
      switch (e.type) {
        case "budget":
          onBudget(e.used, e.limit);
          break;
        case "plan":
          add({ kind: "plan", text: e.text });
          break;
        case "step_started":
          add({ kind: "step", text: `${e.label}` });
          break;
        case "tool_call":
          add({ kind: "tool", text: `${toolLabel(e.tool)} · ${(e.args || "").slice(0, 90)}`, tool: e.tool });
          setSteps((s) => [...s, { label: toolLabel(e.tool), detail: (e.args || "").slice(0, 26), done: false }]);
          break;
        case "tool_result":
          setXray((l) => {
            const i = [...l].reverse().findIndex((x) => x.kind === "tool" && x.tool === e.tool && !x.done);
            if (i < 0) return l;
            const at = l.length - 1 - i;
            return l.map((x, n) =>
              n === at ? { ...x, done: true, text: `${x.text}\n↳ ${e.preview.slice(0, 160)}` } : x
            );
          });
          setSteps((s) => {
            const i = [...s].reverse().findIndex((x) => !x.done);
            if (i < 0) return s;
            const at = s.length - 1 - i;
            return s.map((x, n) => (n === at ? { ...x, done: true } : x));
          });
          break;
        case "token":
          setAnswer((a) => a + e.text);
          break;
        case "step_output":
          if (e.text) setAnswer(e.text);
          break;
        case "approval_required":
          setRunId(e.runId);
          setPush("ask");
          add({ kind: "note", text: "Paused - waiting for you to approve on the phone." });
          break;
        case "approval":
          setPush(e.approved ? "approved" : null);
          add({
            kind: "note",
            text: e.approved
              ? "Approved - continuing."
              : e.timedOut
                ? "No answer in time - stopped."
                : "Declined - stopped.",
          });
          break;
        case "error":
          add({ kind: "error", text: e.message });
          break;
        case "run_finished":
          if (e.output) setAnswer(e.output);
          break;
        default:
          break;
      }
    },
    [onBudget]
  );

  const tap = async () => {
    if (running || !agent) return;
    setError(null);
    const wf = await ensureWorkflow();
    if (!wf) return;
    setRunning(true);
    setRan(false);
    setXray([]);
    setSteps([]);
    setAnswer("");
    setPush(null);
    setRunId(null);
    const controller = new AbortController();
    abortRef.current = controller;
    await runWorkflow(token, wf.id, `Crop: ${crop}. Question: ${button}`, onEvent, controller.signal);
    setRunning(false);
    setRan(true);
  };

  const decide = async (ok: boolean) => {
    if (!runId) return;
    setPush(ok ? "approved" : null);
    await studio.decide(token, runId, ok);
  };

  const makeShare = async () => {
    setBusy(true);
    setError(null);
    if (agent) await studio.makeLive(token, agent.id);
    const r = await createShare(token);
    setBusy(false);
    if (r.ok && r.shareUrl) setShareUrl(r.shareUrl);
    else setError(r.message || "We couldn't make the link just now.");
  };

  const copy = async () => {
    if (!shareUrl) return;
    try {
      await navigator.clipboard.writeText(shareUrl);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      // select by hand
    }
  };

  const qr = useMemo(() => {
    if (!shareUrl) return null;
    try {
      return qrPath(shareUrl, "M");
    } catch {
      return null;
    }
  }, [shareUrl]);

  const view: AppView = {
    build: 1,
    title: appTitle || "My app",
    tagline,
    crop,
    button: button || "Go",
    bound: !!agent,
    busy: running,
    steps,
    answer: answer || undefined,
    push,
  };

  const idx = STEPS.findIndex((s) => s.id === step);
  const canNext = step === "app" ? !!appTitle.trim() && !!button.trim() : step === "registry" ? true : false;

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 lg:flex-row lg:items-stretch lg:justify-center lg:gap-8">
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-3xl border border-white/10 bg-zinc-900/70 shadow-2xl shadow-black/40 backdrop-blur lg:max-w-[720px]">
        <ol className="flex flex-wrap gap-1.5 border-b border-white/10 p-3" aria-label={tHC("s11")}>
          {STEPS.map((s, i) => (
            <li key={s.id}>
              <button
                type="button"
                onClick={() => (i <= idx || (i === 3 && agent) || (i === 4 && agent)) && setStep(s.id)}
                aria-current={s.id === step ? "step" : undefined}
                className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs ${s.id === step ? "border-violet-400 bg-violet-600 text-white" : i < idx ? "border-emerald-400/40 bg-emerald-500/10 text-zinc-200" : "border-white/10 bg-transparent text-zinc-400"}`}
              >
                {i < idx ? (
                  <Check aria-hidden="true" className="h-3 w-3 shrink-0" />
                ) : (
                  <span className="text-[10px]">{i + 1}</span>
                )}{" "}
                {s.label}
              </button>
            </li>
          ))}
        </ol>

        <div className="min-h-0 flex-1 overflow-y-auto p-5">
          {error && (
            <div className="mb-3 rounded-xl border border-rose-400/40 bg-rose-500/10 p-3 text-sm text-rose-100">
              {error}
            </div>
          )}

          {step === "app" && (
            <div className="space-y-4">
              <Head
                title={tHC("s10")}
                body="This is the phone on the right. Rename it, change the button - it updates as you type. (Yours can be any job; the studio behind this is the same.)"
              />
              <Field label={tHC("s9")} value={appTitle} onChange={setAppTitle} max={24} />
              <Field label="Tagline" value={tagline} onChange={setTagline} max={40} />
              <Field label="Button" value={button} onChange={setButton} max={28} />
              <p className="text-xs text-zinc-400">
                {tHC("s8")}
              </p>
            </div>
          )}

          {step === "registry" && (
            <RegistryStep
              query={query}
              onQuery={setQuery}
              mcp={mcp}
              palette={palette}
              tools={tools}
              picked={picked}
              onToggle={toggle}
            />
          )}

          {step === "agent" && (
            <div className="space-y-4">
              <Head
                title={tHC("s7")}
                body="A real agent with a mission and the tools you just picked. Edit the mission if you like - it is the biggest steering wheel."
              />
              <div className="flex flex-wrap gap-1.5">
                {picked.length === 0 && (
                  <span className="text-xs text-zinc-400">
                    {tHC("s6")}
                  </span>
                )}
                {picked.map((t) => (
                  <span
                    key={t}
                    className="rounded-full border border-emerald-400/40 bg-emerald-500/15 px-2.5 py-1 text-xs text-emerald-100"
                  >
                    {toolLabel(t)}
                  </span>
                ))}
              </div>
              <textarea
                value={mission}
                onChange={(e) => setMission(e.target.value.slice(0, 1200))}
                rows={7}
                aria-label={tHC("s5")}
                className="w-full resize-none rounded-xl border border-white/15 bg-black/30 p-3 text-sm leading-relaxed outline-none focus:border-violet-400"
              />
              <button
                type="button"
                onClick={createAgent}
                disabled={busy || !mission.trim()}
                className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-4 py-2.5 text-sm font-medium shadow-lg shadow-violet-500/30 disabled:opacity-50"
              >
                {busy ? (
                  <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin shrink-0" />
                ) : (
                  <Sparkles aria-hidden="true" className="h-4 w-4 shrink-0" />
                )}{" "}
                {agent ? "Update the agent" : "Create the agent and attach it to the button"}
              </button>
            </div>
          )}

          {step === "flow" && (
            <div className="space-y-4">
              <Head
                title={tHC("s4")}
                body="Tap the button on the phone. The workflow is: button → agent → you approve → answer. The approval is real - the run waits for you."
              />
              <div className="grid grid-cols-4 gap-2 text-center text-[11px]">
                {["Button tapped", AGENT.name, "You approve", "Answer"].map((n, i) => (
                  <div key={n} className="rounded-xl border border-violet-400/30 bg-violet-500/10 px-1.5 py-2">
                    <div className="text-zinc-400">{i + 1}</div>
                    {n}
                  </div>
                ))}
              </div>
              {xray.length === 0 && !running && (
                <p className="rounded-xl border border-dashed border-white/15 p-4 text-center text-sm text-zinc-400">
                  Tap “{button}{tHC("s3")}
                </p>
              )}
              <ol className="space-y-2">
                {xray.map((x) => (
                  <li
                    key={x.id}
                    className={`whitespace-pre-wrap rounded-xl border px-3 py-2 text-[12px] leading-snug ${x.kind === "tool" ? "border-emerald-400/25 bg-emerald-500/10" : x.kind === "error" ? "border-rose-400/40 bg-rose-500/10 text-rose-100" : x.kind === "note" ? "border-amber-400/30 bg-amber-500/10 text-amber-100" : "border-white/10 bg-white/5 text-zinc-300"}`}
                  >
                    {x.kind === "tool" &&
                      (x.done ? (
                        <Check aria-hidden="true" className="me-1 inline h-3.5 w-3.5 text-emerald-300 shrink-0" />
                      ) : (
                        <Loader2
                          aria-hidden="true"
                          className="me-1 inline h-3.5 w-3.5 animate-spin text-emerald-300 shrink-0"
                        />
                      ))}
                    {x.text}
                  </li>
                ))}
              </ol>
              {ran && (
                <p className="text-xs text-zinc-400">
                  {tHC("s2")}
                </p>
              )}
            </div>
          )}

          {step === "ship" && (
            <ShipStep
              shareUrl={shareUrl}
              busy={busy}
              hasAgent={!!agent}
              qr={qr}
              copied={copied}
              signupHref={signupHref}
              apk={apk}
              onMakeShare={makeShare}
              onCopy={copy}
            />
          )}
        </div>

        <div className="flex items-center justify-between gap-2 border-t border-white/10 p-3">
          <button
            type="button"
            onClick={() => (idx === 0 ? onClose() : setStep(STEPS[idx - 1].id))}
            className="inline-flex items-center gap-1.5 rounded-xl border border-white/10 bg-transparent px-3 py-2 text-sm text-zinc-300 hover:bg-white/5"
          >
            <ArrowLeft aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> {idx === 0 ? "Close" : "Back"}
          </button>
          {step === "flow" && (
            <button
              type="button"
              onClick={() => setStep("ship")}
              disabled={!ran && !running}
              className="inline-flex items-center gap-1.5 rounded-xl bg-violet-600 px-4 py-2 text-sm font-medium disabled:opacity-40"
            >
              {tHC("s1")} <ArrowRight aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
            </button>
          )}
          {(step === "app" || step === "registry") && (
            <button
              type="button"
              onClick={() => setStep(STEPS[idx + 1].id)}
              disabled={!canNext}
              className="inline-flex items-center gap-1.5 rounded-xl bg-violet-600 px-4 py-2 text-sm font-medium disabled:opacity-40"
            >
              Next <ArrowRight aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
            </button>
          )}
        </div>
      </div>

      <div className="h-[520px] shrink-0 lg:h-auto lg:w-[360px]">
        <PhoneFit>
          <AppPhone
            view={view}
            onCrop={setCrop}
            onTap={step === "flow" && agent ? tap : undefined}
            onApprove={decide}
          />
        </PhoneFit>
      </div>
    </div>
  );
}
