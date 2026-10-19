"use client";

import { MousePointer2, type Bot } from "lucide-react";
import { ease, lerp, seg } from "./script";

// Shared kit for the trailer scenes (Scenes.tsx, ScenesRun.tsx): the scripted
// cursor path, the cursor itself and the window-chrome panel every scene sits in.

export type SceneProps = { p: number };

// -------------------------------------------------------------------- helpers
type Waypoint = { t: number; x: number; y: number; click?: boolean };

export function usePath(points: Waypoint[], p: number): { x: number; y: number; clicking: number } {
  let x = points[0].x;
  let y = points[0].y;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1];
    const b = points[i];
    if (p >= a.t && p <= b.t) {
      const k = ease(seg(p, a.t, b.t));
      x = lerp(a.x, b.x, k);
      y = lerp(a.y, b.y, k);
      break;
    }
    if (p > b.t) {
      x = b.x;
      y = b.y;
    }
  }
  const clicking = points.reduce((m, w) => (w.click ? Math.max(m, 1 - Math.min(1, Math.abs(p - w.t) / 0.035)) : m), 0);
  return { x, y, clicking };
}

export function Cursor({ x, y, clicking }: { x: number; y: number; clicking: number }) {
  return (
    <div
      className="pointer-events-none absolute z-30"
      style={{ left: `${x}%`, top: `${y}%`, transform: "translate(-4px,-2px)" }}
      aria-hidden
    >
      {clicking > 0 && (
        <span
          className="absolute -left-3 -top-3 h-8 w-8 rounded-full border-2 border-violet-300"
          style={{ opacity: clicking, transform: `scale(${1.6 - clicking * 0.6})` }}
        />
      )}
      <MousePointer2
        aria-hidden="true"
        className="h-6 w-6 fill-white text-zinc-900 drop-shadow-lg shrink-0"
        style={{ transform: `scale(${1 - clicking * 0.12})` }}
      />
    </div>
  );
}

export function Panel({
  title,
  icon: Icon,
  children,
  className = "",
}: {
  title: string;
  icon: typeof Bot;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`relative flex h-full min-h-0 flex-col overflow-hidden rounded-3xl border border-white/10 bg-zinc-900/70 shadow-2xl shadow-black/40 backdrop-blur ${className}`}
    >
      <div className="flex items-center gap-2 border-b border-white/10 px-5 py-3 text-xs font-medium uppercase tracking-wider text-zinc-400">
        <Icon className="h-4 w-4 text-violet-300" /> {title}
        <span className="ml-auto flex gap-1.5">
          <i className="h-2.5 w-2.5 rounded-full bg-zinc-700" />
          <i className="h-2.5 w-2.5 rounded-full bg-zinc-700" />
          <i className="h-2.5 w-2.5 rounded-full bg-zinc-700" />
        </span>
      </div>
      <div className="relative min-h-0 flex-1 overflow-y-auto p-5">{children}</div>
    </div>
  );
}
