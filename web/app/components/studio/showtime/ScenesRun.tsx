"use client";

import { useMemo } from "react";
import {
  Bot,
  Code2,
  Globe,
  FileText,
  ShieldCheck,
  Send,
  MessageCircle,
  Smartphone,
  Link2,
  Zap,
  Layers,
} from "lucide-react";
import { qrPath, QR_QUIET } from "../../../lib/qr";
import { SITE_URL } from "../../../lib/site-url";
import { APP, FLOW_NODES, SAMPLE_TRACE, ease, lerp, seg } from "./script";
import { Panel, type SceneProps } from "./sceneKit";
import { useTranslations } from "../../../lib/i18n";

// The trailer's last three scenes (flow, run, ship), split out of Scenes.tsx.
// Same rule as every scene: a pure function of p (0..1 within its act).

const NODE_TONE: Record<string, string> = {
  sky: "border-sky-400/50 bg-sky-500/15 text-sky-100",
  violet: "border-violet-400/50 bg-violet-500/15 text-violet-100",
  amber: "border-amber-400/50 bg-amber-500/15 text-amber-100",
  emerald: "border-emerald-400/50 bg-emerald-500/15 text-emerald-100",
};
const NODE_ICON: Record<string, typeof Bot> = { tap: Zap, agent: Bot, ok: ShieldCheck, out: Smartphone };

export function FlowScene({ p }: SceneProps) {
    const tHC = useTranslations("hcComponentsStudioShowtimeScenesRun");
  const n = FLOW_NODES.length;
  const draw = seg(p, 0.05, 0.6);
  const packet = seg(p, 0.6, 0.98);
  return (
    <Panel title={tHC("s13")} icon={Layers}>
      <div className="relative flex h-full flex-col justify-center gap-8">
        <div className="relative grid grid-cols-4 gap-3">
          {/* edges */}
          <svg className="pointer-events-none absolute inset-0 h-full w-full" preserveAspectRatio="none" aria-hidden>
            {FLOW_NODES.slice(0, -1).map((_, i) => {
              const x1 = ((i + 0.5) / n) * 100 + 9;
              const x2 = ((i + 1.5) / n) * 100 - 9;
              const k = seg(draw, i / (n - 1), (i + 0.9) / (n - 1));
              return (
                <line
                  key={i}
                  x1={`${x1}%`}
                  y1="50%"
                  x2={`${lerp(x1, x2, k)}%`}
                  y2="50%"
                  stroke="rgba(167,139,250,.9)"
                  strokeWidth="2.5"
                  strokeDasharray="6 5"
                  strokeLinecap="round"
                />
              );
            })}
          </svg>
          {FLOW_NODES.map((nd, i) => {
            const k = ease(seg(draw, (i - 0.2) / n, (i + 0.5) / n));
            const lit = packet > i / (n - 1) - 0.02 && packet > 0;
            const Icon = NODE_ICON[nd.id];
            return (
              <div
                key={nd.id}
                className={`relative z-10 rounded-2xl border p-3.5 text-center transition-shadow ${NODE_TONE[nd.tone]} ${lit ? "shadow-xl" : ""}`}
                style={{
                  opacity: k,
                  transform: `translateY(${(1 - k) * 16}px) scale(${lit ? 1.05 : 1})`,
                  boxShadow: lit ? "0 0 32px rgba(167,139,250,.45)" : undefined,
                }}
              >
                <Icon className="mx-auto h-6 w-6" />
                <div className="mt-2 text-[13px] font-semibold leading-tight">{nd.label}</div>
                <div className="mt-0.5 text-[10px]">{nd.sub}</div>
              </div>
            );
          })}
          {packet > 0 && packet < 1 && (
            <span
              className="absolute top-1/2 z-20 h-4 w-4 -translate-y-1/2 rounded-full bg-white shadow-[0_0_20px_6px_rgba(255,255,255,.6)]"
              style={{ left: `calc(${(0.125 + 0.75 * packet) * 100}% - 8px)` }}
            />
          )}
        </div>
        <div className="grid grid-cols-2 gap-3 text-[12px]" style={{ opacity: seg(p, 0.62, 0.78) }}>
          <div className="rounded-xl border border-amber-400/30 bg-amber-500/10 p-3">
            <div className="mb-0.5 font-medium text-amber-200">{tHC("s12")}</div>
            <div className="text-zinc-400">
              {tHC("s11")}
            </div>
          </div>
          <div className="rounded-xl border border-white/10 bg-white/5 p-3">
            <div className="mb-0.5 font-medium text-zinc-200">{tHC("s10")}</div>
            <div className="text-zinc-400">
              {tHC("s9")}
            </div>
          </div>
        </div>
      </div>
    </Panel>
  );
}

export function RunScene({ p }: SceneProps) {
    const tHC = useTranslations("hcComponentsStudioShowtimeScenesRun");
  const stepT = seg(p, 0.14, 0.62);
  return (
    <Panel title={tHC("s8")} icon={Zap}>
      <div className="flex h-full flex-col gap-3">
        <div
          className="rounded-xl border border-white/10 bg-black/25 px-3.5 py-2.5 text-sm"
          style={{ opacity: seg(p, 0.03, 0.1) }}
        >
          <span className="text-[10px] uppercase tracking-wider text-zinc-400">{tHC("s7")}</span>
          <div className="mt-0.5 text-zinc-100">
            {APP.button} <span className="text-zinc-400">· crop: {APP.crop}</span>
          </div>
        </div>
        <ol className="space-y-2.5">
          {SAMPLE_TRACE.map((s, i) => {
            const start = i / SAMPLE_TRACE.length;
            const k = seg(stepT, start, start + 0.12);
            const done = seg(stepT, start + 0.16, start + 0.3);
            const Icon = s.tool === "web_search" ? Globe : s.tool === "fetch_web_page" ? FileText : Code2;
            return (
              <li
                key={s.tool}
                className="rounded-xl border border-emerald-400/25 bg-emerald-500/10 p-3"
                style={{ opacity: k, transform: `translateX(${(1 - k) * -18}px)` }}
              >
                <div className="flex items-center gap-2 text-sm font-medium">
                  <Icon className="h-4 w-4 text-emerald-300" /> {s.label}
                  <span className="ms-auto text-[10px] text-zinc-400">{done > 0.5 ? "done" : "running…"}</span>
                </div>
                <div className="mt-1 font-mono text-[11px] text-zinc-400">
                  {s.tool}({JSON.stringify(s.detail)})
                </div>
                {done > 0.5 && <div className="mt-1 text-[11px] text-emerald-200">↳ {s.result}</div>}
              </li>
            );
          })}
        </ol>
        <div className="mt-auto grid grid-cols-2 gap-2 text-[11px]" style={{ opacity: seg(p, 0.66, 0.8) }}>
          <div className="rounded-xl border border-white/10 bg-white/5 p-2.5">
            <span className="text-zinc-400">{tHC("s6")}</span>
            <div className="text-lg font-semibold">3</div>
          </div>
          <div className="rounded-xl border border-amber-400/30 bg-amber-500/10 p-2.5">
            <span className="text-amber-200">Status</span>
            <div className="text-sm font-semibold text-amber-100">
              {p > 0.95 ? "Approved · sent" : p > 0.84 ? "Waiting for approval" : "Answer ready"}
            </div>
          </div>
        </div>
        <div className="text-center text-[10px] uppercase tracking-wider text-zinc-600">
          {tHC("s5")}
        </div>
      </div>
    </Panel>
  );
}

export function ShipScene({ p }: SceneProps) {
    const tHC = useTranslations("hcComponentsStudioShowtimeScenesRun");
  const qr = useMemo(() => {
    try {
      return qrPath(`${SITE_URL}/try`, "M");
    } catch {
      return null;
    }
  }, []);
  const k = ease(seg(p, 0.05, 0.3));
  const orbit = [
    { icon: Link2, label: "Link" },
    { icon: Send, label: "Telegram" },
    { icon: MessageCircle, label: "WhatsApp" },
    { icon: Smartphone, label: "Android app" },
  ];
  return (
    <Panel title={tHC("s4")} icon={Send}>
      <div className="relative flex h-full flex-col items-center justify-center gap-5">
        <div
          className="relative rounded-3xl bg-white p-3 shadow-2xl shadow-sky-500/30"
          style={{ opacity: k, transform: `scale(${0.7 + 0.3 * k}) rotate(${(1 - k) * -6}deg)` }}
        >
          {qr && (
            <svg
              viewBox={`0 0 ${qr.size + QR_QUIET * 2} ${qr.size + QR_QUIET * 2}`}
              className="h-44 w-44"
              role="img"
              aria-label={tHC("s3")}
            >
              <path d={qr.d} fill="#09090b" />
            </svg>
          )}
        </div>
        <div className="text-center text-[10px] uppercase tracking-wider text-zinc-400" style={{ opacity: k }}>
          {tHC("s2")}
        </div>
        <div className="flex gap-3">
          {orbit.map((o, i) => {
            const kk = ease(seg(p, 0.35 + i * 0.1, 0.5 + i * 0.1));
            return (
              <div
                key={o.label}
                className="flex w-[84px] flex-col items-center gap-1.5 rounded-2xl border border-sky-400/30 bg-sky-500/10 py-3"
                style={{ opacity: kk, transform: `translateY(${(1 - kk) * 14}px)` }}
              >
                <o.icon className="h-5 w-5 text-sky-200" />
                <span className="text-[11px]">{o.label}</span>
              </div>
            );
          })}
        </div>
        <div className="text-center text-xs text-zinc-400" style={{ opacity: seg(p, 0.78, 0.92) }}>
          {tHC("s1")}
        </div>
      </div>
    </Panel>
  );
}
