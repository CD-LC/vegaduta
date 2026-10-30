"use client";

import {
  Bot,
  Brain,
  Check,
  Code2,
  Globe,
  FileText,
  Plug,
  Search,
  Smartphone,
  Link2,
  Sparkles,
  Database,
  Wrench,
  Zap,
  Layers,
} from "lucide-react";
import { APP, AGENT, REGISTRY_TILES, SAMPLE_ANSWER, SAMPLE_TRACE, ease, seg, typed, type ActId } from "./script";
import type { AppView } from "./AppPhone";
import BrandName from "../../BrandName";
import { usePath, Cursor, Panel, type SceneProps } from "./sceneKit";
import { FlowScene, RunScene, ShipScene } from "./ScenesRun";
import { useTranslations } from "../../../lib/i18n";

// Trailer scenes. Every scene is a pure function of p (0..1 within its act):
// no timers, no state, so scrubbing and pausing are free and exact.

// ---------------------------------------------------------------- phone views
export function phoneViewFor(id: ActId, p: number): AppView {
  const base: AppView = {
    build: 1,
    title: APP.title,
    tagline: APP.tagline,
    crop: APP.crop,
    button: APP.button,
    sample: true,
  };
  switch (id) {
    case "open":
      return { ...base, build: 0, hidden: true };
    case "design":
      return { ...base, build: ease(seg(p, 0.04, 0.92)), hidden: false };
    case "registry":
      return base;
    case "agent":
      return { ...base, bound: p > 0.62 };
    case "flow":
      return { ...base, bound: true };
    case "run": {
      const tapped = seg(p, 0.04, 0.1);
      const stepT = seg(p, 0.14, 0.62);
      const shown = Math.min(SAMPLE_TRACE.length, Math.ceil(stepT * SAMPLE_TRACE.length - 0.001));
      const steps =
        tapped > 0.9
          ? SAMPLE_TRACE.slice(0, Math.max(0, shown)).map((s, i) => ({
              label: s.label,
              detail: s.detail,
              done: stepT * SAMPLE_TRACE.length > i + 0.85,
            }))
          : [];
      const answerT = seg(p, 0.66, 0.82);
      return {
        ...base,
        bound: true,
        pressed: seg(p, 0.04, 0.12) * (1 - seg(p, 0.12, 0.13)),
        busy: p > 0.1 && p < 0.66,
        steps,
        answer: answerT > 0 ? typed(SAMPLE_ANSWER, answerT) : undefined,
        push: p > 0.84 ? (p > 0.95 ? "approved" : "ask") : null,
      };
    }
    default:
      return {
        ...base,
        bound: true,
        steps: SAMPLE_TRACE.map((s) => ({ label: s.label, detail: s.detail, done: true })),
        answer: SAMPLE_ANSWER,
        push: "approved",
      };
  }
}
// ---------------------------------------------------------------------- scenes
function OpenScene({ p }: SceneProps) {
    const tHC = useTranslations("hcComponentsStudioShowtimeScenes");
  const a = ease(seg(p, 0.05, 0.3));
  const b = ease(seg(p, 0.3, 0.55));
  const c = ease(seg(p, 0.6, 0.85));
  return (
    <div className="flex h-full flex-col items-center justify-center px-6 text-center">
      <div className="text-xs uppercase tracking-[0.3em] text-violet-300" style={{ opacity: a }}>
        <BrandName /> presents
      </div>
      <h2
        className="mt-5 max-w-3xl text-5xl font-semibold leading-[1.05] tracking-tight sm:text-7xl"
        style={{ opacity: a, transform: `translateY(${(1 - a) * 24}px)` }}
      >
        {tHC("s14")}
        <br />
        <span
          className="bg-gradient-to-r from-emerald-300 via-teal-300 to-sky-300 bg-clip-text text-transparent"
          style={{ opacity: b }}
        >
          {tHC("s13")}
        </span>
      </h2>
      <p
        className="mt-6 max-w-xl text-lg text-zinc-300"
        style={{ opacity: c, transform: `translateY(${(1 - c) * 12}px)` }}
      >
        {tHC("s12")}
      </p>
    </div>
  );
}

const BLOCKS = [
  { label: "Header", icon: Layers, at: [0.05, 0.22] },
  { label: "Text field", icon: FileText, at: [0.4, 0.58] },
  { label: "Button", icon: Zap, at: [0.62, 0.8] },
  { label: "Result card", icon: Sparkles, at: [0.82, 0.92] },
];

function DesignScene({ p }: SceneProps) {
    const tHC = useTranslations("hcComponentsStudioShowtimeScenes");
  const cur = usePath(
    [
      { t: 0, x: 60, y: 90 },
      { t: 0.1, x: 22, y: 26, click: true },
      { t: 0.24, x: 86, y: 40 },
      { t: 0.36, x: 22, y: 40 },
      { t: 0.44, x: 22, y: 40, click: true },
      { t: 0.6, x: 86, y: 52 },
      { t: 0.66, x: 22, y: 54 },
      { t: 0.7, x: 22, y: 54, click: true },
      { t: 0.84, x: 86, y: 66 },
      { t: 1, x: 70, y: 86 },
    ],
    p
  );
  return (
    <Panel title={tHC("s11")} icon={Smartphone}>
      <div className="grid h-full grid-cols-[150px_1fr] gap-4">
        <div className="space-y-2">
          <div className="text-[10px] uppercase tracking-wider text-zinc-400">Blocks</div>
          {BLOCKS.map((b) => {
            const used = p > b.at[1];
            const active = p > b.at[0] - 0.08 && p < b.at[1];
            return (
              <div
                key={b.label}
                className={`flex items-center gap-2 rounded-xl border px-3 py-2.5 text-sm transition-colors ${active ? "border-violet-400 bg-violet-500/20" : used ? "border-emerald-500/30 bg-emerald-500/10 text-zinc-400" : "border-white/10 bg-white/5"}`}
              >
                <b.icon className="h-4 w-4 text-violet-300" /> {b.label}
                {used && <Check aria-hidden="true" className="ms-auto h-3.5 w-3.5 text-emerald-300 shrink-0" />}
              </div>
            );
          })}
        </div>
        <div className="relative rounded-2xl border border-dashed border-white/15 bg-black/20">
          <div
            className="absolute inset-0 flex flex-col items-center justify-center gap-2 px-6 text-center text-sm text-zinc-400"
            style={{ opacity: 1 - seg(p, 0.06, 0.2) }}
          >
            {tHC("s10")}
          </div>
          <div
            className="absolute inset-x-6 top-6 space-y-2 text-xs text-zinc-400"
            style={{ opacity: seg(p, 0.2, 0.3) }}
          >
            <div className="text-[10px] uppercase tracking-wider text-zinc-400">Properties</div>
            <div className="rounded-lg border border-white/10 bg-white/5 px-3 py-2">
              Title · <span className="text-zinc-200">{typed(APP.title, seg(p, 0.12, 0.24))}</span>
            </div>
            <div
              className="rounded-lg border border-white/10 bg-white/5 px-3 py-2"
              style={{ opacity: seg(p, 0.42, 0.5) }}
            >
              Field · <span className="text-zinc-200">{tHC("s9")}</span>
            </div>
            <div
              className="rounded-lg border border-white/10 bg-white/5 px-3 py-2"
              style={{ opacity: seg(p, 0.66, 0.74) }}
            >
              {tHC("s8")} <span className="text-zinc-200">{typed(APP.button, seg(p, 0.66, 0.78))}</span>
            </div>
            <div
              className="rounded-lg border border-emerald-500/25 bg-emerald-500/10 px-3 py-2 text-emerald-200"
              style={{ opacity: seg(p, 0.86, 0.95) }}
            >
              {tHC("s7")}
            </div>
          </div>
        </div>
      </div>
      <Cursor {...cur} />
    </Panel>
  );
}

const TILE_ICON: Record<string, typeof Globe> = {
  fetch_web_page: FileText,
  web_search: Globe,
  run_code: Code2,
  http_request: Wrench,
  search_knowledge: Database,
  remember_fact: Brain,
  mcp_calendar: Plug,
  mcp_maps: Plug,
  mcp_code: Plug,
};

function RegistryScene({ p }: SceneProps) {
    const tHC = useTranslations("hcComponentsStudioShowtimeScenes");
  const query = typed("web", seg(p, 0.12, 0.3));
  const filtering = seg(p, 0.3, 0.42);
  const attached = seg(p, 0.66, 0.78);
  // Grid is 3x3 of REGISTRY_TILES; web_search sits at index 1 (row 0, col 1).
  const cur = usePath(
    [
      { t: 0, x: 80, y: 90 },
      { t: 0.08, x: 30, y: 15 },
      { t: 0.12, x: 30, y: 15, click: true },
      { t: 0.5, x: 30, y: 15 },
      { t: 0.6, x: 50, y: 33 },
      { t: 0.66, x: 50, y: 33, click: true },
      { t: 0.74, x: 50, y: 33 },
      { t: 0.86, x: 82, y: 33 },
      { t: 1, x: 82, y: 60 },
    ],
    p
  );
  return (
    <Panel title={tHC("s6")} icon={Search}>
      <div className="flex h-full flex-col gap-3">
        <div className="flex items-center gap-2 rounded-xl border border-white/15 bg-black/30 px-3 py-2.5 text-sm">
          <Search aria-hidden="true" className="h-4 w-4 text-zinc-400 shrink-0" />
          <span className={query ? "text-zinc-100" : "text-zinc-400"}>{query || "Search tools…"}</span>
          {query && p < 0.6 && <span className="h-4 w-[2px] animate-pulse bg-violet-300" />}
          <span className="ms-auto text-[11px] text-zinc-400">
            {filtering > 0.5 ? "1 match · 40+ built-in, plus the MCP registry" : "40+ built-in, plus the MCP registry"}
          </span>
        </div>
        <div className="grid min-h-0 flex-1 grid-cols-3 content-start gap-3">
          {REGISTRY_TILES.map((t) => {
            const Icon = TILE_ICON[t.id] ?? Wrench;
            const isTarget = t.id === "web_search";
            const dim = filtering * (isTarget ? 0 : 0.78);
            const hover = isTarget && p > 0.5 && p < 0.66;
            return (
              <div
                key={t.id}
                className={`rounded-2xl border p-3.5 transition-colors ${isTarget && attached > 0 ? "border-emerald-400 bg-emerald-500/15 shadow-lg shadow-emerald-500/20" : hover ? "border-violet-400 bg-violet-500/15" : "border-white/10 bg-white/5"}`}
                style={{
                  opacity: 1 - dim,
                  transform: isTarget ? `scale(${1 + 0.06 * filtering * (1 - attached)})` : undefined,
                }}
              >
                <div className="flex items-center gap-2 text-sm font-medium">
                  <Icon className={`h-4 w-4 ${t.kind === "mcp" ? "text-sky-300" : "text-violet-300"}`} /> {t.label}
                </div>
                <div className="mt-1 text-[11px] text-zinc-400">{t.blurb}</div>
                {isTarget && (
                  <div
                    className="mt-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium"
                    style={{ background: attached > 0.3 ? "rgba(16,185,129,.25)" : "rgba(139,92,246,.25)" }}
                  >
                    {attached > 0.3 ? (
                      <>
                        <Check aria-hidden="true" className="h-3 w-3 shrink-0" /> {tHC("s5")}
                      </>
                    ) : (
                      "Attach to agent"
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
        <div className="flex items-center gap-2 text-[11px] text-zinc-400" style={{ opacity: seg(p, 0.8, 0.92) }}>
          <Check aria-hidden="true" className="h-3.5 w-3.5 text-emerald-300 shrink-0" /> {tHC("s4")}
        </div>
      </div>
      <Cursor {...cur} />
    </Panel>
  );
}

function AgentScene({ p }: SceneProps) {
    const tHC = useTranslations("hcComponentsStudioShowtimeScenes");
  const mission = typed(AGENT.mission, seg(p, 0.08, 0.55));
  const tools = [
    { id: "web_search", label: "Search the web", icon: Globe },
    { id: "fetch_web_page", label: "Read a web page", icon: FileText },
    { id: "run_code", label: "Run code", icon: Code2 },
  ];
  return (
    <Panel title={tHC("s3")} icon={Bot}>
      <div className="flex h-full flex-col gap-4">
        <div className="flex items-center gap-3">
          <span
            className="inline-flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-violet-500 to-indigo-600 text-xl font-semibold shadow-lg shadow-violet-500/40"
            style={{ transform: `scale(${0.6 + 0.4 * ease(seg(p, 0, 0.12))})` }}
          >
            M
          </span>
          <div>
            <div className="text-lg font-semibold">{AGENT.name}</div>
            <div className="text-xs text-emerald-300">{tHC("s2")}</div>
          </div>
        </div>
        <div>
          <div className="mb-1 text-[10px] uppercase tracking-wider text-zinc-400">Mission</div>
          <p className="min-h-[110px] rounded-xl border border-white/10 bg-black/25 p-3 text-[13px] leading-relaxed text-zinc-200">
            {mission}
          </p>
        </div>
        <div>
          <div className="mb-1.5 text-[10px] uppercase tracking-wider text-zinc-400">Hands</div>
          <div className="flex flex-wrap gap-2">
            {tools.map((t, i) => {
              const k = ease(seg(p, 0.5 + i * 0.07, 0.6 + i * 0.07));
              return (
                <span
                  key={t.id}
                  className="inline-flex items-center gap-1.5 rounded-full border border-emerald-400/40 bg-emerald-500/15 px-3 py-1 text-xs text-emerald-100"
                  style={{ opacity: k, transform: `translateY(${(1 - k) * 8}px)` }}
                >
                  <t.icon className="h-3.5 w-3.5" /> {t.label}
                </span>
              );
            })}
          </div>
        </div>
        <div className="grid grid-cols-3 gap-2 text-center text-[11px]" style={{ opacity: seg(p, 0.66, 0.78) }}>
          {[
            { k: "Thinking", v: "Rigorous" },
            { k: "Autonomy", v: "Asks first" },
            { k: "Guardrails", v: "On, every turn" },
          ].map((d) => (
            <div key={d.k} className="rounded-xl border border-amber-400/25 bg-amber-500/10 px-2 py-2">
              <div className="text-zinc-400">{d.k}</div>
              <div className="font-medium text-amber-100">{d.v}</div>
            </div>
          ))}
        </div>
        <div
          className="mt-auto flex items-center gap-2 rounded-xl border border-violet-400/40 bg-violet-500/15 px-3.5 py-2.5 text-sm"
          style={{ opacity: seg(p, 0.72, 0.85) }}
        >
          <Link2 className="h-4 w-4 shrink-0 text-violet-200" aria-hidden="true" /> {tHC("s1")}{" "}
          <b>“{APP.button}”</b> →
          <span className="ms-auto inline-flex items-center gap-1 text-emerald-300">
            <Check className="h-4 w-4 shrink-0" aria-hidden="true" /> wired
          </span>
        </div>
      </div>
    </Panel>
  );
}
export function TrailerScene({ id, p }: { id: ActId; p: number }) {
  switch (id) {
    case "design":
      return <DesignScene p={p} />;
    case "registry":
      return <RegistryScene p={p} />;
    case "agent":
      return <AgentScene p={p} />;
    case "flow":
      return <FlowScene p={p} />;
    case "run":
      return <RunScene p={p} />;
    case "ship":
      return <ShipScene p={p} />;
    default:
      return null;
  }
}

export { OpenScene };
