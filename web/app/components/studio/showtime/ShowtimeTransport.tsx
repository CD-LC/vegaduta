"use client";

import { useRef } from "react";
import { Captions, Hammer, Pause, Play, RotateCcw, SkipBack, SkipForward, Volume2, VolumeX } from "lucide-react";

// Showtime's transport (split out of Showtime.tsx): the scrubbable timeline with
// chapter ticks, play/pause, chapter jumps, speed, captions and voice-over.

export const SPEEDS = [0.75, 1, 1.5, 2];

export const mmss = (ms: number) =>
  `${Math.floor(ms / 60000)}:${String(Math.floor((ms % 60000) / 1000)).padStart(2, "0")}`;

export default function ShowtimeTransport({
  t,
  total,
  title,
  chapters,
  index,
  playing,
  speed,
  captions,
  narrate,
  seek,
  jump,
  setPlaying,
  setSpeed,
  setCaptions,
  setNarrate,
  enterBuild,
}: {
  t: number;
  total: number;
  /** The current chapter's title, for the slider's spoken value. */
  title: string;
  chapters: { start: number; title: string; short: string }[];
  index: number;
  playing: boolean;
  speed: number;
  captions: boolean;
  narrate: boolean;
  seek: (ms: number) => void;
  jump: (i: number) => void;
  setPlaying: (update: (v: boolean) => boolean) => void;
  setSpeed: (update: (s: number) => number) => void;
  setCaptions: (update: (v: boolean) => boolean) => void;
  setNarrate: (update: (v: boolean) => boolean) => void;
  enterBuild: () => void;
}) {
  const barRef = useRef<HTMLDivElement | null>(null);
  const dragging = useRef(false);
  const scrub = (clientX: number) => {
    const r = barRef.current?.getBoundingClientRect();
    if (!r) return;
    seek(((clientX - r.left) / r.width) * total);
  };

  return (
    <div className="relative z-10 border-t border-white/10 bg-black/50 px-4 py-3 backdrop-blur sm:px-6">
      <div
        ref={barRef}
        role="slider"
        tabIndex={0}
        aria-label="Timeline"
        aria-valuemin={0}
        aria-valuemax={Math.round(total / 1000)}
        aria-valuenow={Math.round(t / 1000)}
        aria-valuetext={`${title}, ${mmss(t)} of ${mmss(total)}`}
        onPointerDown={(e) => {
          dragging.current = true;
          (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
          scrub(e.clientX);
        }}
        onPointerMove={(e) => dragging.current && scrub(e.clientX)}
        onPointerUp={() => {
          dragging.current = false;
        }}
        className="group relative h-6 cursor-pointer touch-none"
      >
        <div className="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 overflow-hidden rounded-full bg-white/10 transition-all group-hover:h-2.5">
          <div
            className="h-full rounded-full bg-gradient-to-r from-violet-500 via-emerald-400 to-sky-400"
            style={{ width: `${(t / total) * 100}%` }}
          />
        </div>
        {chapters.slice(1).map((c) => (
          <span
            key={c.start}
            title={c.title}
            className="absolute top-1/2 h-3 w-[2px] -translate-y-1/2 bg-zinc-950/80"
            style={{ left: `${(c.start / total) * 100}%` }}
          />
        ))}
        <span
          className="absolute top-1/2 h-3.5 w-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white shadow-lg shadow-violet-500/50"
          style={{ left: `${(t / total) * 100}%` }}
        />
      </div>

      <div className="mt-1.5 flex flex-wrap items-center gap-1.5 sm:gap-2">
        <Btn label="Previous chapter (←)" onClick={() => jump(index - 1)}>
          <SkipBack aria-hidden="true" className="h-4 w-4 shrink-0" />
        </Btn>
        <button
          type="button"
          onClick={() => {
            if (t >= total - 2) seek(0);
            setPlaying((v) => !v);
          }}
          aria-label={playing ? "Pause (Space)" : "Play (Space)"}
          title="Play / pause (Space)"
          className="inline-flex h-10 w-10 items-center justify-center rounded-full bg-white text-zinc-950 shadow-lg hover:scale-105"
        >
          {playing ? (
            <Pause aria-hidden="true" className="h-5 w-5 shrink-0" />
          ) : t >= total - 2 ? (
            <RotateCcw aria-hidden="true" className="h-5 w-5 shrink-0" />
          ) : (
            <Play aria-hidden="true" className="h-5 w-5 translate-x-0.5 shrink-0" />
          )}
        </button>
        <Btn label="Next chapter (→)" onClick={() => jump(index + 1)}>
          <SkipForward aria-hidden="true" className="h-4 w-4 shrink-0" />
        </Btn>
        <span className="ml-1 font-mono text-xs tabular-nums text-zinc-400">
          {mmss(t)} / {mmss(total)}
        </span>

        <div
          className="mx-2 hidden min-w-0 flex-1 items-center gap-1 overflow-x-auto md:flex"
          role="tablist"
          aria-label="Chapters"
        >
          {chapters.map((a, i) => (
            <button
              key={a.start}
              type="button"
              role="tab"
              aria-selected={i === index}
              onClick={() => jump(i)}
              className={`rounded-full px-2.5 py-1 text-[11px] transition-colors ${i === index ? "bg-white/15 text-white" : "bg-transparent text-zinc-400 hover:text-zinc-200"}`}
            >
              {a.short}
            </button>
          ))}
        </div>

        <div className="ml-auto flex items-center gap-1">
          <button
            type="button"
            onClick={() => setSpeed((s) => SPEEDS[(SPEEDS.indexOf(s) + 1) % SPEEDS.length])}
            title="Speed"
            aria-label={`Speed ${speed}x`}
            className="rounded-lg bg-transparent px-2.5 py-1.5 font-mono text-xs text-zinc-300 hover:bg-white/10"
          >
            {speed}×
          </button>
          <Btn label="Captions (C)" onClick={() => setCaptions((v) => !v)} active={captions}>
            <Captions aria-hidden="true" className="h-4 w-4 shrink-0" />
          </Btn>
          <Btn label="Voice-over" onClick={() => setNarrate((v) => !v)} active={narrate}>
            {narrate ? (
              <Volume2 aria-hidden="true" className="h-4 w-4 shrink-0" />
            ) : (
              <VolumeX aria-hidden="true" className="h-4 w-4 shrink-0" />
            )}
          </Btn>
          <button
            type="button"
            onClick={enterBuild}
            className="ml-1 hidden items-center gap-1.5 rounded-xl bg-emerald-600 px-3 py-1.5 text-xs font-medium hover:bg-emerald-500 sm:inline-flex"
          >
            <Hammer aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Build it live
          </button>
        </div>
      </div>
    </div>
  );
}

function Btn({
  children,
  label,
  onClick,
  active,
}: {
  children: React.ReactNode;
  label: string;
  onClick: () => void;
  active?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      aria-pressed={active}
      title={label}
      className={`rounded-lg p-2 hover:bg-white/10 ${active ? "bg-white/10 text-white" : "bg-transparent text-zinc-300"}`}
    >
      {children}
    </button>
  );
}
