"use client";

import { Check, Loader2, Sprout, Bell, ShieldCheck, Sparkles } from "lucide-react";
import { seg, ease } from "./script";

// The phone the whole show is about: the farmer's app. Rendered from a plain
// view object so the SAME component draws the scripted trailer (values computed
// from progress) and the live mode (values from the real run). Nothing in here
// knows which one it is in, except that live mode passes handlers and the phone
// becomes tappable.

export type AppStep = { label: string; detail?: string; done: boolean };

export type AppView = {
  /** 0..1: how much of the app has been dropped onto the screen (design act). */
  build: number;
  title: string;
  tagline: string;
  crop: string;
  button: string;
  /** Button is highlighted as bound to an agent. */
  bound?: boolean;
  /** 0..1 press animation on the button. */
  pressed?: number;
  busy?: boolean;
  steps?: AppStep[];
  answer?: string;
  /** "ask" = an approval push is waiting; "approved" = it was approved. */
  push?: null | "ask" | "approved";
  sample?: boolean;
  /** The screen shows nothing at all (cold open). */
  hidden?: boolean;
};

type Handlers = {
  onCrop?: (v: string) => void;
  onTap?: () => void;
  onApprove?: (ok: boolean) => void;
};

export default function AppPhone({ view, onCrop, onTap, onApprove }: { view: AppView } & Handlers) {
  const b = view.build;
  const vis = (a: number, z: number) => ease(seg(b, a, z));
  const placing = (a: number, z: number) => b > a && b < z;
  const interactive = !!onTap;

  const item = (a: number, z: number): React.CSSProperties => ({
    opacity: vis(a, z),
    transform: `translateY(${(1 - vis(a, z)) * 14}px) scale(${0.96 + 0.04 * vis(a, z)})`,
  });
  const outline = (a: number, z: number) =>
    placing(a, z) ? "outline outline-2 outline-dashed outline-violet-400/80 outline-offset-2" : "";

  return (
    <div className="relative h-[640px] w-[312px] shrink-0 rounded-[2.8rem] border border-white/10 bg-zinc-950 p-[10px] shadow-[0_30px_80px_-20px_rgba(124,58,237,0.5)] ring-1 ring-black/60">
      <span className="absolute -left-[3px] top-28 h-12 w-[3px] rounded-l bg-zinc-800" />
      <span className="absolute -right-[3px] top-36 h-20 w-[3px] rounded-r bg-zinc-800" />
      <div className="relative h-full w-full overflow-hidden rounded-[2.2rem] bg-gradient-to-b from-emerald-950 via-zinc-950 to-zinc-950 text-zinc-100">
        <div className="absolute inset-x-0 top-0 z-20 flex items-center justify-between px-6 pt-2.5 text-[10px] font-medium text-zinc-300">
          <span>9:41</span>
          <span className="absolute left-1/2 top-2.5 h-5 w-[76px] -translate-x-1/2 rounded-full bg-black" />
          <span>5G</span>
        </div>

        <div
          className="absolute inset-0 flex flex-col px-5 pt-14"
          style={{ opacity: view.hidden ? 0 : 1, transition: "opacity .5s" }}
        >
          {/* header */}
          <div style={item(0.05, 0.22)} className={`rounded-2xl ${outline(0.05, 0.22)}`}>
            <div className="flex items-center gap-2.5">
              <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-500/20 text-emerald-300">
                <Sprout aria-hidden="true" className="h-5 w-5 shrink-0" />
              </span>
              <div>
                <div className="text-[17px] font-semibold leading-tight">{view.title}</div>
                <div className="text-[11px] text-emerald-300" style={{ opacity: vis(0.2, 0.32) }}>
                  {view.tagline}
                </div>
              </div>
            </div>
          </div>

          {/* crop input */}
          <div style={item(0.4, 0.58)} className={`mt-6 rounded-2xl ${outline(0.4, 0.58)}`}>
            <div className="mb-1 text-[10px] uppercase tracking-wider text-zinc-400">Your crop</div>
            {onCrop ? (
              <input
                value={view.crop}
                onChange={(e) => onCrop(e.target.value)}
                aria-label="Your crop"
                className="w-full rounded-xl border border-white/15 bg-white/5 px-3 py-2.5 text-[15px] outline-none focus:border-emerald-400"
              />
            ) : (
              <div className="rounded-xl border border-white/15 bg-white/5 px-3 py-2.5 text-[15px]">{view.crop}</div>
            )}
          </div>

          {/* the button */}
          <div style={item(0.62, 0.8)} className={`mt-4 rounded-2xl ${outline(0.62, 0.8)}`}>
            <button
              type="button"
              disabled={!interactive || view.busy}
              onClick={onTap}
              tabIndex={interactive ? 0 : -1}
              aria-label={view.button}
              className={`relative flex w-full items-center justify-center gap-2 rounded-2xl bg-gradient-to-r from-emerald-500 to-teal-500 py-3.5 text-[15px] font-semibold text-emerald-950 shadow-lg shadow-emerald-500/30 transition ${interactive && !view.busy ? "hover:brightness-110 active:scale-[0.98]" : ""}`}
              style={{ transform: `scale(${1 - 0.05 * Math.sin(Math.PI * (view.pressed ?? 0))})` }}
            >
              {view.busy ? (
                <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin shrink-0" />
              ) : (
                <Sparkles aria-hidden="true" className="h-4 w-4 shrink-0" />
              )}{" "}
              {view.button}
              {view.bound && (
                <span
                  className="absolute -right-1.5 -top-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-violet-600 text-white ring-2 ring-zinc-950"
                  title="Agent attached"
                >
                  <Sparkles aria-hidden="true" className="h-3 w-3 shrink-0" />
                </span>
              )}
            </button>
          </div>

          {/* work + answer */}
          <div
            className="mt-5 min-h-0 flex-1 space-y-2 overflow-hidden"
            style={{ opacity: vis(0.82, 0.95) || (view.steps?.length || view.answer ? 1 : 0) }}
          >
            {(view.steps ?? []).map((s, i) => (
              <div
                key={i}
                className="flex items-center gap-2 rounded-lg border border-emerald-400/25 bg-emerald-500/10 px-2.5 py-1.5 text-[11px]"
              >
                {s.done ? (
                  <Check aria-hidden="true" className="h-3.5 w-3.5 shrink-0 text-emerald-300" />
                ) : (
                  <Loader2 aria-hidden="true" className="h-3.5 w-3.5 shrink-0 animate-spin text-emerald-300" />
                )}
                <span className="min-w-0 flex-1 truncate">
                  {s.label}
                  {s.detail ? <span className="text-zinc-400"> · {s.detail}</span> : null}
                </span>
              </div>
            ))}
            {view.answer ? (
              <div className="rounded-2xl rounded-tl-sm border border-white/10 bg-white/10 p-3 text-[12px] leading-relaxed">
                {view.answer}
                {view.sample && (
                  <div className="mt-1.5 text-[9px] uppercase tracking-wider text-zinc-400">Sample run</div>
                )}
              </div>
            ) : null}
          </div>
        </div>

        {/* approval push */}
        {view.push && (
          <div
            className="absolute inset-x-3 top-11 z-30 rounded-2xl border border-amber-400/40 bg-zinc-800/95 p-3 shadow-2xl shadow-amber-500/20 backdrop-blur"
            style={{ animation: "showtime-push .45s cubic-bezier(.2,1.2,.4,1)" }}
          >
            <div className="mb-1 flex items-center gap-1.5 text-[9px] uppercase tracking-wider text-amber-300">
              <Bell aria-hidden="true" className="h-3 w-3 shrink-0" /> Mandi analyst needs your OK
            </div>
            <p className="text-[12px] leading-snug">Send this advice to Ramesh&apos;s phone?</p>
            <div className="mt-2 flex gap-2">
              {view.push === "ask" ? (
                <>
                  <button
                    type="button"
                    disabled={!onApprove}
                    onClick={() => onApprove?.(true)}
                    className="flex-1 rounded-lg bg-emerald-600 py-1.5 text-[12px] font-medium disabled:cursor-default"
                  >
                    Approve
                  </button>
                  <button
                    type="button"
                    disabled={!onApprove}
                    onClick={() => onApprove?.(false)}
                    className="flex-1 rounded-lg bg-white/10 py-1.5 text-[12px] disabled:cursor-default"
                  >
                    Decline
                  </button>
                </>
              ) : (
                <div className="flex flex-1 items-center justify-center gap-1.5 rounded-lg bg-emerald-600/25 py-1.5 text-[12px] text-emerald-200">
                  <ShieldCheck aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Approved - logged
                </div>
              )}
            </div>
          </div>
        )}

        <div className="absolute inset-x-0 bottom-1.5 flex justify-center">
          <span className="h-1 w-24 rounded-full bg-zinc-600" />
        </div>
      </div>
    </div>
  );
}
