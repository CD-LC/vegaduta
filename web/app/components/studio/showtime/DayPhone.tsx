"use client";

import { Bell, Check, ShieldAlert, ShieldCheck } from "lucide-react";
import type { DayEvent, PhoneItem, PhoneScreen, statsAt } from "./day";
import { ease, seg } from "./script";
import { TONE } from "./dayTone";

// The phone in "An Impossible Day": what the outside world saw for the current
// chapter's event (split out of DayStage). Pure function of (event, p, stats).

// ---------------------------------------------------------------------------- phone
export default function DayPhone({
  ev,
  p,
  stats,
}: {
  ev: DayEvent | null;
  p: number;
  stats: ReturnType<typeof statsAt>;
}) {
  const screen: PhoneScreen = ev
    ? ev.id === "report"
      ? {
          ...ev.phone,
          items: [
            { at: 0.4, kind: "out", text: "Evening report, Meera.", sub: "Your agents today" },
            { at: 0.5, kind: "line", text: `${stats.msgs} messages sent, ${stats.tools} tool calls.` },
            { at: 0.6, kind: "line", text: `${stats.docs} documents and pages read.` },
            {
              at: 0.7,
              kind: "alert",
              text: `${stats.asks} things needed you - both approved.`,
              sub: "Logged with your name and the time",
            },
            { at: 0.82, kind: "ok", text: `${stats.blocked} mistake stopped before it left.` },
          ],
        }
      : ev.phone
    : { title: "Meera's phone", sub: "do not disturb", tone: "violet", items: [] };
  const t = TONE[screen.tone];
  return (
    <div className="relative h-[640px] w-[312px] shrink-0 rounded-[2.8rem] border border-white/10 bg-zinc-950 p-[10px] shadow-[0_30px_80px_-20px_rgba(124,58,237,0.45)] ring-1 ring-black/60">
      <div className="relative h-full w-full overflow-hidden rounded-[2.2rem] bg-gradient-to-b from-zinc-900 to-zinc-950 text-zinc-100">
        <div className="absolute inset-x-0 top-0 z-20 flex items-center justify-between px-6 pt-2.5 text-[10px] font-medium text-zinc-300">
          <span>{ev ? ev.time : "00:00"}</span>
          <span className="absolute left-1/2 top-2.5 h-5 w-[76px] -translate-x-1/2 rounded-full bg-black" />
          <span>5G</span>
        </div>
        <div className="absolute inset-0 flex flex-col pt-12">
          <div className="border-b border-white/10 px-5 pb-3" style={{ borderColor: `${t.ring}33` }}>
            <div className="flex items-center gap-2 text-[15px] font-semibold">
              <span className="h-2 w-2 rounded-full" style={{ background: t.ring }} /> {screen.title}
            </div>
            <div className={`text-[11px] ${t.text}`}>{screen.sub}</div>
          </div>
          <div className="flex-1 space-y-2.5 overflow-hidden px-4 pt-4">
            {!ev && (
              <p className="mt-10 text-center text-[12px] text-zinc-400">
                Meera is asleep.
                <br />
                Her phone is on silent.
              </p>
            )}
            {screen.items.map((it, i) => {
              const k = ease(seg(p, it.at, it.at + 0.06));
              if (k <= 0) return null;
              return <Item key={i} it={it} k={k} tone={screen.tone} />;
            })}
          </div>
          <div className="pb-2 text-center text-[9px] uppercase tracking-wider text-zinc-600">
            Illustration - a sample day
          </div>
        </div>
      </div>
    </div>
  );
}

function Item({ it, k, tone }: { it: PhoneItem; k: number; tone: string }) {
  const style = { opacity: k, transform: `translateY(${(1 - k) * 12}px)` };
  const t = TONE[tone];
  switch (it.kind) {
    case "in":
      return (
        <div
          style={style}
          className="max-w-[86%] rounded-2xl rounded-tl-sm bg-white/10 px-3 py-2 text-[13px] leading-snug"
        >
          {it.text}
          {it.sub && <div className="mt-1 text-[10px] text-zinc-400">{it.sub}</div>}
        </div>
      );
    case "out":
      return (
        <div
          style={{ ...style, background: `${t.ring}33` }}
          className="ml-auto max-w-[86%] rounded-2xl rounded-tr-sm px-3 py-2 text-[13px] leading-snug"
        >
          {it.text}
          {it.sub && <div className="mt-1 text-[10px] text-zinc-300">{it.sub}</div>}
        </div>
      );
    case "push":
      return (
        <div
          style={style}
          className="rounded-2xl border border-amber-400/40 bg-zinc-800 p-3 shadow-xl shadow-amber-500/10"
        >
          <div className="mb-1 flex items-center gap-1.5 text-[9px] uppercase tracking-wider text-amber-300">
            <Bell aria-hidden="true" className="h-3 w-3 shrink-0" /> {it.text}
          </div>
          <p className="text-[12px] leading-snug">{it.sub}</p>
          <div className="mt-2 flex gap-2">
            <span className="flex-1 rounded-lg bg-emerald-600 py-1.5 text-center text-[12px] font-medium">Approve</span>
            <span className="flex-1 rounded-lg bg-white/10 py-1.5 text-center text-[12px]">Change it</span>
          </div>
        </div>
      );
    case "approved":
      return (
        <div
          style={style}
          className="flex items-center justify-center gap-1.5 rounded-xl bg-emerald-600/25 py-2 text-[12px] text-emerald-200"
        >
          <ShieldCheck aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> {it.text}
        </div>
      );
    case "alert":
      return (
        <div
          style={style}
          className="rounded-xl border border-rose-400/40 bg-rose-500/10 px-3 py-2 text-[12.5px] leading-snug"
        >
          <div className="flex items-start gap-1.5">
            <ShieldAlert aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 shrink-0 text-rose-300" /> {it.text}
          </div>
          {it.sub && <div className="mt-1 pl-5 text-[10px] text-zinc-400">{it.sub}</div>}
        </div>
      );
    case "ok":
      return (
        <div
          style={style}
          className="rounded-xl border border-emerald-400/30 bg-emerald-500/10 px-3 py-2 text-[12.5px]"
        >
          <div className="flex items-start gap-1.5">
            <Check aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-300" /> {it.text}
          </div>
          {it.sub && <div className="mt-1 pl-5 text-[10px] text-zinc-400">{it.sub}</div>}
        </div>
      );
    case "note":
      return (
        <div
          style={style}
          className="rounded-xl border border-white/15 bg-white/5 px-3 py-2 text-[12.5px] leading-snug"
        >
          {it.text}
          {it.sub && <div className="mt-1 text-[10px] text-zinc-400">{it.sub}</div>}
        </div>
      );
    default:
      return (
        <div style={style} className="flex items-baseline gap-2 border-l-2 pl-3 text-[12.5px]">
          <span className="text-zinc-100">{it.text}</span>
          {it.sub && <span className="text-[10px] text-zinc-400">{it.sub}</span>}
        </div>
      );
  }
}
