"use client";

import {
  CalendarClock,
  Check,
  FileSearch,
  FileText,
  Globe,
  HandCoins,
  Mail,
  MessageCircle,
  Moon,
  Newspaper,
  Send,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Sun,
  UserSearch,
  Wrench,
} from "lucide-react";
import { DAY_AGENTS, DAY_CHAPTERS, statsAt, type AgentId, type DayEvent } from "./day";
import { ease, lerp, seg } from "./script";
import { PhoneFit } from "./PhoneFit";
import { TONE } from "./dayTone";
import DayPhone from "./DayPhone";
import Finale from "./DayFinale";
import { useTranslations } from "../../../lib/i18n";

// The stage of "An Impossible Day": an orrery of agents around a sleeping owner,
// a 24-hour ring with a sun/moon on it, and the phone that shows what the outside
// world saw. Pure function of (chapter index, progress p, clock hour) - like the
// trailer, so scrub / pause / speed / jump cost nothing.
//
// Geometry lives in an 800x800 space; the container is square and sized in cqw so
// the whole picture scales with the room it is given.

const AGENT_ICON: Record<AgentId, typeof Globe> = {
  desk: MessageCircle,
  watch: Globe,
  brief: Newspaper,
  chase: HandCoins,
  legal: FileSearch,
  hire: UserSearch,
  ops: CalendarClock,
  guard: ShieldCheck,
};
const PORT_ICON = {
  whatsapp: MessageCircle,
  web: Globe,
  telegram: Send,
  email: Mail,
  docs: FileText,
  calendar: CalendarClock,
  agent: Sparkles,
} as const;

const R_NODE = 250;
const R_PORT = 345;
const R_RING = 388;
const rad = (deg: number) => (deg * Math.PI) / 180;
const pt = (deg: number, r: number) => ({ x: 400 + r * Math.cos(rad(deg)), y: 400 + r * Math.sin(rad(deg)) });
const pct = (v: number) => `${(v / 800) * 100}%`;
const angleOf = (id: AgentId) => DAY_AGENTS.find((a) => a.id === id)!.angle;
const nodePos = (id: AgentId) => pt(angleOf(id), R_NODE);
const portPos = (id: AgentId) => pt(angleOf(id), R_PORT);
const HUB = { x: 400, y: 400 };
const mix = (a: { x: number; y: number }, b: { x: number; y: number }, k: number) => ({
  x: lerp(a.x, b.x, k),
  y: lerp(a.y, b.y, k),
});
const fmtClock = (h: number) => {
  const m = Math.floor((((h % 24) + 24) % 24) * 60);
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
};

type Props = { index: number; p: number; hour: number; onBuild: (job: string) => void; signupHref: string };

export default function DayStage({ index, p, hour, onBuild, signupHref }: Props) {
  const chapter = DAY_CHAPTERS[index];
  const ev = chapter.event;
  const finale = chapter.id === "finale";
  const stats = statsAt(index, p);
  const litAgents = new Set<AgentId>(DAY_CHAPTERS.slice(0, index).flatMap((c) => (c.event ? [c.event.agent] : [])));
  const night = hour < 6.5 || hour >= 20.5;

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 lg:flex-row lg:justify-center lg:gap-6">
      <div className="flex min-h-0 flex-1 flex-col gap-2 lg:max-w-[calc(100vh-17rem)] lg:flex-none lg:basis-auto">
        <div className="mx-auto min-h-[280px] w-full flex-1">
          <Orrery ev={ev} p={p} hour={hour} night={night} litAgents={litAgents} finale={finale} />
        </div>
        <Counters stats={stats} />
      </div>
      <div className="hidden h-full min-h-0 shrink-0 lg:block lg:w-[380px]">
        {finale ? (
          <Finale stats={stats} onBuild={onBuild} signupHref={signupHref} p={p} />
        ) : ev ? (
          <PhoneFit max={1.15}>
            <DayPhone ev={ev} p={p} stats={stats} />
          </PhoneFit>
        ) : (
          <PhoneFit max={1.15}>
            <DayPhone ev={null} p={p} stats={stats} />
          </PhoneFit>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------- orrery
function Orrery({
  ev,
  p,
  hour,
  night,
  litAgents,
  finale,
}: {
  ev: DayEvent | null;
  p: number;
  hour: number;
  night: boolean;
  litAgents: Set<AgentId>;
  finale: boolean;
}) {
    const tHC = useTranslations("hcComponentsStudioShowtimeDayStage");
  const tone = ev ? TONE[ev.phone.tone] : TONE.violet;

  // Packets
  const packets: { x: number; y: number; c: string }[] = [];
  const lines: { a: { x: number; y: number }; b: { x: number; y: number }; on: boolean }[] = [];
  if (ev) {
    const node = nodePos(ev.agent);
    const port = portPos(ev.agent);
    const src = ev.from ? nodePos(ev.from) : port;
    const inK = seg(p, 0.02, 0.2);
    lines.push({ a: src, b: node, on: p > 0.02 });
    if (inK > 0 && inK < 1) packets.push({ ...mix(src, node, ease(inK)), c: tone.ring });
    if (ev.approval) {
      const toHub = seg(p, 0.6, 0.72);
      const back = seg(p, 0.86, 0.92);
      const out = seg(p, 0.92, 0.99);
      lines.push({ a: node, b: HUB, on: p > 0.6 });
      if (toHub > 0 && toHub < 1) packets.push({ ...mix(node, HUB, ease(toHub)), c: "#fbbf24" });
      if (back > 0 && back < 1) packets.push({ ...mix(HUB, node, ease(back)), c: "#34d399" });
      if (out > 0 && out < 1) packets.push({ ...mix(node, port, ease(out)), c: "#34d399" });
      lines.push({ a: node, b: port, on: p > 0.92 });
    } else {
      const out = seg(p, 0.66, 0.84);
      lines.push({ a: node, b: port, on: p > 0.66 });
      if (out > 0 && out < 1) packets.push({ ...mix(node, port, ease(out)), c: "#34d399" });
    }
  }

  const deciding = !!ev?.approval && p > 0.62 && p < 0.86;
  const sunAngle = (hour / 24) * 360 - 90;
  const sun = pt(sunAngle, R_RING);
  const state = finale
    ? "reading the report"
    : deciding
      ? "deciding"
      : hour < 7
        ? "asleep"
        : hour >= 22
          ? "reading"
          : "at work";

  return (
    <div className="relative mx-auto aspect-square h-full max-w-full [container-type:size]">
      <svg viewBox="0 0 800 800" className="absolute inset-0 h-full w-full" aria-hidden>
        <defs>
          <radialGradient id="hubglow">
            <stop offset="0%" stopColor="rgba(255,255,255,.14)" />
            <stop offset="100%" stopColor="rgba(255,255,255,0)" />
          </radialGradient>
          <filter id="pglow">
            <feGaussianBlur stdDeviation="6" />
          </filter>
        </defs>
        <circle cx="400" cy="400" r="330" fill="url(#hubglow)" />
        {/* 24h ring */}
        <circle cx="400" cy="400" r={R_RING} fill="none" stroke="rgba(255,255,255,.1)" strokeWidth="2" />
        {Array.from({ length: 48 }).map((_, i) => {
          const a = (i / 48) * 360 - 90;
          const big = i % 12 === 0;
          const p1 = pt(a, R_RING - (big ? 14 : 6));
          const p2 = pt(a, R_RING + (big ? 4 : 0));
          const passed = (i / 48) * 24 <= hour;
          return (
            <line
              key={i}
              x1={p1.x}
              y1={p1.y}
              x2={p2.x}
              y2={p2.y}
              stroke={passed ? "rgba(255,255,255,.7)" : "rgba(255,255,255,.18)"}
              strokeWidth={big ? 3 : 1.5}
            />
          );
        })}
        {[0, 6, 12, 18].map((h) => {
          const q = pt((h / 24) * 360 - 90, R_RING - 30);
          return (
            <text
              key={h}
              x={q.x}
              y={q.y}
              fill="rgba(255,255,255,.4)"
              fontSize="18"
              textAnchor="middle"
              dominantBaseline="middle"
            >
              {String(h).padStart(2, "0")}
            </text>
          );
        })}
        {/* spokes */}
        {DAY_AGENTS.map((a) => {
          const n = nodePos(a.id);
          return (
            <line key={a.id} x1={HUB.x} y1={HUB.y} x2={n.x} y2={n.y} stroke="rgba(255,255,255,.07)" strokeWidth="2" />
          );
        })}
        {DAY_AGENTS.map((a) => {
          const n = nodePos(a.id);
          const q = portPos(a.id);
          return (
            <line
              key={a.id}
              x1={n.x}
              y1={n.y}
              x2={q.x}
              y2={q.y}
              stroke="rgba(255,255,255,.07)"
              strokeWidth="2"
              strokeDasharray="4 6"
            />
          );
        })}
        {lines
          .filter((l) => l.on)
          .map((l, i) => (
            <line
              key={i}
              x1={l.a.x}
              y1={l.a.y}
              x2={l.b.x}
              y2={l.b.y}
              stroke={tone.ring}
              strokeOpacity=".55"
              strokeWidth="3"
              strokeLinecap="round"
              strokeDasharray="8 8"
            />
          ))}
        {packets.map((k, i) => (
          <g key={i}>
            <circle cx={k.x} cy={k.y} r="16" fill={k.c} opacity=".55" filter="url(#pglow)" />
            <circle cx={k.x} cy={k.y} r="7" fill="#fff" />
          </g>
        ))}
        {/* sun / moon on the ring */}
        <circle
          cx={sun.x}
          cy={sun.y}
          r="22"
          fill={night ? "rgba(199,210,254,.25)" : "rgba(253,224,71,.35)"}
          filter="url(#pglow)"
        />
        <circle cx={sun.x} cy={sun.y} r="11" fill={night ? "#c7d2fe" : "#fde047"} />
      </svg>

      {/* ports */}
      {DAY_AGENTS.map((a) => {
        const q = portPos(a.id);
        const active = ev?.agent === a.id;
        const Icon = active && ev ? PORT_ICON[ev.port.icon] : PORT_ICON.agent;
        return (
          <div
            key={a.id}
            className="absolute flex -translate-x-1/2 -translate-y-1/2 flex-col items-center"
            style={{ left: pct(q.x), top: pct(q.y) }}
          >
            <span
              className="flex items-center justify-center rounded-full border transition-all duration-300"
              style={{
                width: "5.6cqw",
                height: "5.6cqw",
                borderColor: active ? tone.ring : "rgba(255,255,255,.12)",
                background: active ? tone.glow : "rgba(255,255,255,.04)",
                boxShadow: active ? `0 0 3cqw ${tone.glow}` : undefined,
                opacity: active ? 1 : 0.5,
              }}
            >
              <Icon style={{ width: "2.6cqw", height: "2.6cqw" }} className={active ? "text-white" : "text-zinc-400"} />
            </span>
            {active && ev && (
              <span
                className="absolute top-full mt-[0.4cqw] whitespace-nowrap rounded-full bg-black/60 px-[1cqw] py-[0.2cqw] text-zinc-200"
                style={{ fontSize: "max(9px,1.3cqw)" }}
              >
                {ev.port.label}
              </span>
            )}
          </div>
        );
      })}

      {/* agents */}
      {DAY_AGENTS.map((a) => {
        const n = nodePos(a.id);
        const active = ev?.agent === a.id;
        const lit = active || litAgents.has(a.id) || finale;
        const Icon = AGENT_ICON[a.id];
        const working = active && p > 0.18 && p < 0.64;
        return (
          <div
            key={a.id}
            className="absolute flex -translate-x-1/2 -translate-y-1/2 flex-col items-center"
            style={{ left: pct(n.x), top: pct(n.y), zIndex: active ? 5 : 1 }}
          >
            <span
              className="relative flex items-center justify-center rounded-full border-2 transition-all duration-300"
              style={{
                width: "11cqw",
                height: "11cqw",
                borderColor: active ? tone.ring : lit ? "rgba(255,255,255,.45)" : "rgba(255,255,255,.14)",
                background: active ? tone.glow : lit ? "rgba(255,255,255,.1)" : "rgba(255,255,255,.04)",
                transform: `scale(${active ? 1.16 : 1})`,
                boxShadow: active ? `0 0 5cqw ${tone.glow}` : undefined,
              }}
            >
              {working && (
                <span
                  className="absolute inset-[-0.8cqw] animate-spin rounded-full border-2 border-dashed"
                  style={{ borderColor: tone.ring, animationDuration: "3s" }}
                />
              )}
              <Icon style={{ width: "5cqw", height: "5cqw" }} className={lit ? "text-white" : "text-zinc-400"} />
              {lit && !active && (
                <span
                  className="absolute -end-[0.4cqw] -top-[0.4cqw] flex items-center justify-center rounded-full bg-emerald-500 text-emerald-950"
                  style={{ width: "2.6cqw", height: "2.6cqw" }}
                >
                  <Check aria-hidden="true" style={{ width: "1.6cqw", height: "1.6cqw" }} />
                </span>
              )}
            </span>
            <span
              className={`mt-[0.6cqw] whitespace-nowrap text-center font-medium ${lit ? "text-zinc-100" : "text-zinc-400"}`}
              style={{ fontSize: "max(9px,1.45cqw)" }}
            >
              {a.name}
            </span>
          </div>
        );
      })}

      {/* hub: the owner */}
      <div className="absolute left-1/2 top-1/2 flex -translate-x-1/2 -translate-y-1/2 flex-col items-center">
        <div
          className="relative flex flex-col items-center justify-center rounded-full border-2 text-center backdrop-blur transition-all duration-500"
          style={{
            width: "19cqw",
            height: "19cqw",
            borderColor: deciding ? "#fbbf24" : "rgba(255,255,255,.3)",
            background: deciding ? "rgba(251,191,36,.18)" : "rgba(0,0,0,.45)",
            boxShadow: deciding ? "0 0 6cqw rgba(251,191,36,.5)" : "0 0 4cqw rgba(0,0,0,.5)",
          }}
        >
          {deciding && <span className="absolute inset-[-1cqw] animate-ping rounded-full border border-amber-300/60" />}
          {night ? (
            <Moon aria-hidden="true" style={{ width: "2.4cqw", height: "2.4cqw" }} className="text-indigo-200" />
          ) : (
            <Sun aria-hidden="true" style={{ width: "2.4cqw", height: "2.4cqw" }} className="text-amber-200" />
          )}
          <div className="font-semibold tabular-nums leading-none" style={{ fontSize: "max(16px,4.4cqw)" }}>
            {fmtClock(hour)}
          </div>
          <div className="mt-[0.5cqw] text-zinc-300" style={{ fontSize: "max(9px,1.5cqw)" }}>
            Meera · {state}
          </div>
        </div>
      </div>

      {/* trace card, inside the ring below the hub */}
      {ev && (
        <div
          className="absolute left-1/2 -translate-x-1/2 rounded-2xl border border-white/10 bg-black/55 backdrop-blur"
          style={{
            top: "61%",
            width: "31cqw",
            padding: "1cqw 1.4cqw",
            opacity: seg(p, 0.16, 0.22) * (1 - seg(p, 0.96, 1)),
          }}
        >
          <div className="mb-[0.6cqw] uppercase tracking-wider text-zinc-400" style={{ fontSize: "max(8px,1.1cqw)" }}>
            {DAY_AGENTS.find((a) => a.id === ev.agent)?.name} {tHC("s3")}
          </div>
          <ul className="space-y-[0.5cqw]">
            {ev.tools.map((t, i) => {
              const start = 0.2 + (i * 0.42) / ev.tools.length;
              const end = 0.2 + ((i + 1) * 0.42) / ev.tools.length;
              const shown = p > start;
              const done = p > end - 0.03;
              const blocked = /BLOCKED/.test(t.result);
              return (
                <li
                  key={t.label}
                  className="flex items-start gap-[0.8cqw]"
                  style={{ opacity: shown ? 1 : 0, fontSize: "max(9px,1.4cqw)", transition: "opacity .25s" }}
                >
                  <span
                    className={`mt-[0.15cqw] ${done ? (blocked ? "text-rose-300" : "text-emerald-300") : "text-zinc-400"}`}
                  >
                    {done ? (
                      blocked ? (
                        <ShieldAlert aria-hidden="true" style={{ width: "1.8cqw", height: "1.8cqw" }} />
                      ) : (
                        <Check aria-hidden="true" style={{ width: "1.8cqw", height: "1.8cqw" }} />
                      )
                    ) : (
                      <Wrench
                        aria-hidden="true"
                        style={{ width: "1.8cqw", height: "1.8cqw" }}
                        className="animate-pulse"
                      />
                    )}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="text-zinc-100">{t.label}</span>
                    {done && (
                      <span className={blocked ? "block text-rose-300" : "block text-zinc-400"}>↳ {t.result}</span>
                    )}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}

function Counters({ stats }: { stats: ReturnType<typeof statsAt> }) {
    const tHC = useTranslations("hcComponentsStudioShowtimeDayStage");
  const items = [
    { l: "messages", v: stats.msgs, c: "" },
    { l: "tool calls", v: stats.tools, c: "" },
    { l: "docs & pages read", v: stats.docs, c: "" },
    { l: "needed Meera", v: stats.asks, c: "text-amber-300" },
    { l: "stopped", v: stats.blocked, c: "text-rose-300" },
  ];
  return (
    <div
      className="flex flex-wrap items-center justify-center gap-x-4 gap-y-1 rounded-xl border border-white/10 bg-black/40 px-3 py-1.5 text-xs backdrop-blur"
      aria-label={tHC("s2")}
    >
      <span className="text-[10px] uppercase tracking-wider text-zinc-400">{tHC("s1")}</span>
      {items.map((i) => (
        <span key={i.l} className={`tabular-nums ${i.c || "text-zinc-300"}`}>
          <b className="text-sm">{i.v}</b> <span className="text-zinc-400">{i.l}</span>
        </span>
      ))}
    </div>
  );
}
