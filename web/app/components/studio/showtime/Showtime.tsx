"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Image from "next/image";
import { ArrowRight, Clapperboard, Hammer, Moon, Maximize2, Minimize2, X, Loader2 } from "lucide-react";
import { ACTS, ACT_STARTS, TOTAL_MS } from "./script";
import { OpenScene, TrailerScene, phoneViewFor } from "./Scenes";
import AppPhone from "./AppPhone";
import LiveBuilder from "./LiveBuilder";
import { PhoneFit } from "./PhoneFit";
import DayStage from "./DayStage";
import { DAY_CHAPTERS, DAY_TOTAL_MS, skyFor } from "./day";
import { pledgeAccepted, rememberPledge } from "../StudioPledge";
import ShowtimeTransport from "./ShowtimeTransport";
import EndCard from "./ShowtimeEndCard";
import LiveConsent from "./LiveConsent";

// Showtime: the platform as a film you can drive. Full screen, dark, one phone,
// one job. Two modes on the same stage:
//   Watch  - the scripted trailer: play / pause / scrub / speed / chapters /
//            captions / voice-over, with an "under the hood" card per act that
//            names the real feature and where to find it.
//   Build  - the same six moves for real, on the real demo backend (LiveBuilder).
// The trailer is labelled as a script wherever it shows results; the live mode
// is where results are real.

type Mode = "day" | "watch" | "build";

/** A track is anything with a total length and chapters: the film's transport works on either. */
type Chapter = {
  short: string;
  kicker: string;
  title: string;
  caption: string;
  start: number;
  ms: number;
  explain: { feature: string; where: string }[];
  build?: string | null;
  h0?: number;
  h1?: number;
};
type Track = { total: number; chapters: Chapter[] };
const TRAILER: Track = {
  total: TOTAL_MS,
  chapters: ACTS.map((a, i) => ({
    short: a.short,
    kicker: a.kicker,
    title: a.title,
    caption: a.caption,
    start: ACT_STARTS[i],
    ms: a.ms,
    explain: a.explain,
  })),
};
const DAY: Track = { total: DAY_TOTAL_MS, chapters: DAY_CHAPTERS };
function locateIn(track: Track, t: number): { index: number; p: number } {
  const tt = Math.min(Math.max(t, 0), track.total - 1);
  let index = 0;
  for (let i = 0; i < track.chapters.length; i++) if (tt >= track.chapters[i].start) index = i;
  const c = track.chapters[index];
  return { index, p: (tt - c.start) / c.ms };
}

type Props = {
  open: boolean;
  onClose: () => void;
  token: string | null;
  signupHref: string;
  onBudget: (used: number, limit: number) => void;
  initialMode?: Mode;
  /** Close the film and drop a brief into the studio launcher ("Build this one", "What is YOUR impossible job?"). */
  onLaunch: (job: string) => void;
  /** Address shown in the consent card for live mode. */
  clientIp?: string | null;
};

const AURA = ["#7c3aed", "#8b5cf6", "#0ea5e9", "#6366f1", "#f59e0b", "#10b981", "#38bdf8", "#7c3aed"];

export default function Showtime({
  open,
  onClose,
  token,
  signupHref,
  onBudget,
  initialMode = "day",
  onLaunch,
  clientIp,
}: Props) {
  const [mode, setMode] = useState<Mode>(initialMode);
  const [t, setT] = useState(0);
  const [playing, setPlaying] = useState(true);
  const [speed, setSpeed] = useState(1);
  const [captions, setCaptions] = useState(true);
  const [narrate, setNarrate] = useState(false);
  const [full, setFull] = useState(false);
  const [consented, setConsented] = useState(true);
  const [agree, setAgree] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const tRef = useRef(0);

  const track = mode === "day" ? DAY : TRAILER;
  const total = track.total;
  const { index, p } = locateIn(track, t);
  const act = track.chapters[index];
  const trailerAct = ACTS[Math.min(index, ACTS.length - 1)];
  const hour = mode === "day" ? act.h0! + (act.h1! - act.h0!) * p : 0;

  // Reset on every open.
  useEffect(() => {
    if (!open) return;
    setMode(initialMode);
    setT(0);
    tRef.current = 0;
    setPlaying(true);
    setConsented(pledgeAccepted());
  }, [open, initialMode]);

  // Each mode is its own timeline: switching starts it from the top.
  useEffect(() => {
    tRef.current = 0;
    setT(0);
    setPlaying(true);
  }, [mode]);

  // Lock the page behind the overlay.
  useEffect(() => {
    if (!open) return undefined;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  // The clock.
  useEffect(() => {
    if (!open || !playing || mode === "build") return undefined;
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const dt = now - last;
      last = now;
      const next = Math.min(total - 1, tRef.current + dt * speed);
      tRef.current = next;
      setT(next);
      if (next >= total - 1) {
        setPlaying(false);
        return;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [open, playing, speed, mode, total]);

  const seek = useCallback(
    (ms: number) => {
      const v = Math.min(total - 1, Math.max(0, ms));
      tRef.current = v;
      setT(v);
    },
    [total]
  );
  const jump = useCallback(
    (i: number) => {
      const k = Math.min(track.chapters.length - 1, Math.max(0, i));
      seek(track.chapters[k].start + 1);
      setPlaying(true);
    },
    [seek, track]
  );

  const toggleFull = useCallback(async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await rootRef.current?.requestFullscreen();
    } catch {
      // fullscreen refused (iframe / policy) - the overlay is already full-window
    }
  }, []);
  useEffect(() => {
    const h = () => setFull(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", h);
    return () => document.removeEventListener("fullscreenchange", h);
  }, []);

  const close = useCallback(() => {
    try {
      if (document.fullscreenElement) void document.exitFullscreen();
      window.speechSynthesis?.cancel();
    } catch {
      // nothing to undo
    }
    onClose();
  }, [onClose]);

  // Voice-over: the caption, read once per act, only while playing.
  useEffect(() => {
    if (!open || !narrate || !playing || mode === "build") {
      try {
        window.speechSynthesis?.cancel();
      } catch {
        /* unsupported */
      }
      return;
    }
    try {
      window.speechSynthesis?.cancel();
      const u = new SpeechSynthesisUtterance(act.caption);
      u.rate = Math.min(1.6, speed);
      window.speechSynthesis?.speak(u);
    } catch {
      // no speech engine - captions still show
    }
  }, [open, narrate, playing, index, mode, act.caption, speed]);

  // Keyboard.
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA")) return;
      if (e.key === "Escape") {
        close();
        return;
      }
      if (mode === "build") return;
      if (e.key === " ") {
        e.preventDefault();
        setPlaying((v) => !v);
      } else if (e.key === "ArrowRight") jump(index + 1);
      else if (e.key === "ArrowLeft") jump(index - 1);
      else if (e.key.toLowerCase() === "f") void toggleFull();
      else if (e.key.toLowerCase() === "c") setCaptions((v) => !v);
      else if (/^[1-9]$/.test(e.key)) jump(Number(e.key));
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, mode, index, jump, close, toggleFull]);

  const view = useMemo(() => phoneViewFor(trailerAct.id, p), [trailerAct.id, p]);

  if (!open) return null;

  const enterBuild = () => {
    setPlaying(false);
    setMode("build");
  };

  return (
    <div
      ref={rootRef}
      role="region"
      aria-label="Showtime - the platform in full screen"
      className="fixed inset-0 z-[90] flex flex-col overflow-hidden bg-zinc-950 text-zinc-100"
    >
      <style>{`
        @keyframes showtime-push{from{transform:translateY(-28px) scale(.96);opacity:0}to{transform:none;opacity:1}}
        @keyframes showtime-drift{0%{transform:translate3d(-4%,0,0) scale(1)}50%{transform:translate3d(4%,-3%,0) scale(1.12)}100%{transform:translate3d(-4%,0,0) scale(1)}}
        @media (prefers-reduced-motion: reduce){.showtime-aura{animation:none!important}}
      `}</style>

      {/* Aurora: colour follows the act */}
      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div
          className="showtime-aura absolute -left-1/4 -top-1/3 h-[80vh] w-[80vw] rounded-full opacity-30 blur-[120px] transition-colors duration-1000"
          style={{
            background: mode === "day" ? skyFor(hour) : AURA[index % AURA.length],
            animation: "showtime-drift 18s ease-in-out infinite",
          }}
        />
        <div
          className="showtime-aura absolute -bottom-1/3 -right-1/4 h-[70vh] w-[70vw] rounded-full opacity-20 blur-[120px] transition-colors duration-1000"
          style={{
            background: mode === "day" ? skyFor((hour + 8) % 24) : AURA[(index + 3) % AURA.length],
            animation: "showtime-drift 22s ease-in-out infinite reverse",
          }}
        />
      </div>

      {/* top bar */}
      <div className="relative z-10 flex items-center gap-3 px-4 py-3 sm:px-6">
        <Image src="/logo-mark.png" alt="" width={26} height={26} style={{ width: 26, height: "auto" }} />
        <span className="hidden text-sm font-semibold tracking-tight sm:inline">Showtime</span>
        <div
          className="ml-2 inline-flex rounded-full border border-white/10 bg-white/5 p-0.5 text-xs"
          role="tablist"
          aria-label="Mode"
        >
          <button
            type="button"
            role="tab"
            aria-selected={mode === "day"}
            onClick={() => setMode("day")}
            className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 ${mode === "day" ? "bg-violet-600 text-white" : "bg-transparent text-zinc-400 hover:text-zinc-100"}`}
          >
            <Moon aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> An impossible day
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === "watch"}
            onClick={() => setMode("watch")}
            className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 ${mode === "watch" ? "bg-sky-600 text-white" : "bg-transparent text-zinc-400 hover:text-zinc-100"}`}
          >
            <Clapperboard aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> How it&apos;s built
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === "build"}
            onClick={enterBuild}
            className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 ${mode === "build" ? "bg-emerald-600 text-white" : "bg-transparent text-zinc-400 hover:text-zinc-100"}`}
          >
            <Hammer aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Build it live
          </button>
        </div>
        <div className="ml-auto flex items-center gap-1">
          <button
            type="button"
            onClick={toggleFull}
            aria-label={full ? "Exit full screen" : "Full screen"}
            title="Full screen (F)"
            className="rounded-lg bg-transparent p-2 text-zinc-300 hover:bg-white/10"
          >
            {full ? (
              <Minimize2 aria-hidden="true" className="h-4 w-4 shrink-0" />
            ) : (
              <Maximize2 aria-hidden="true" className="h-4 w-4 shrink-0" />
            )}
          </button>
          <button
            type="button"
            onClick={close}
            aria-label="Close (Esc)"
            title="Close (Esc)"
            className="rounded-lg bg-transparent p-2 text-zinc-300 hover:bg-white/10"
          >
            <X aria-hidden="true" className="h-5 w-5 shrink-0" />
          </button>
        </div>
      </div>

      {/* stage */}
      <div className="relative z-10 min-h-0 flex-1 px-4 pb-2 sm:px-6">
        {mode !== "build" ? (
          <div className="flex h-full min-h-0 flex-col gap-3">
            <div className="min-h-0 flex-1">
              {mode === "day" ? (
                <DayStage index={index} p={p} hour={hour} onBuild={onLaunch} signupHref={signupHref} />
              ) : trailerAct.id === "open" ? (
                <OpenScene p={p} />
              ) : (
                <div className="flex h-full min-h-0 flex-col gap-4 lg:flex-row lg:justify-center lg:gap-8">
                  <div className="min-h-0 flex-1 lg:max-w-[820px]">
                    {trailerAct.id === "end" ? (
                      <EndCard
                        onBuild={enterBuild}
                        onReplay={() => {
                          seek(0);
                          setPlaying(true);
                        }}
                        signupHref={signupHref}
                        p={p}
                      />
                    ) : (
                      <TrailerScene id={trailerAct.id} p={p} />
                    )}
                  </div>
                  <div className="hidden h-full shrink-0 lg:block lg:w-[360px]">
                    <PhoneFit>
                      <AppPhone view={view} />
                    </PhoneFit>
                  </div>
                </div>
              )}
            </div>

            {/* caption + under the hood */}
            <div className="grid gap-3 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
              <div className="rounded-2xl border border-white/10 bg-black/40 px-4 py-3 backdrop-blur">
                <div className="flex items-center gap-2 text-[11px] uppercase tracking-wider text-violet-300">
                  <span>{act.kicker}</span> <span className="text-zinc-600">·</span>{" "}
                  <span className="text-zinc-300">{act.title}</span>
                </div>
                {captions && <p className="mt-1 text-[15px] leading-snug text-zinc-100 sm:text-base">{act.caption}</p>}
                {mode === "day" && act.build && (
                  <button
                    type="button"
                    onClick={() => onLaunch(act.build!)}
                    className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-violet-400/40 bg-violet-500/15 px-3 py-1.5 text-xs font-medium text-violet-100 hover:bg-violet-500/25"
                  >
                    <Hammer aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Build this one yourself{" "}
                    <ArrowRight aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
                  </button>
                )}
              </div>
              <div className="hidden rounded-2xl border border-white/10 bg-black/40 px-4 py-3 backdrop-blur lg:block">
                <div className="mb-1 text-[11px] uppercase tracking-wider text-zinc-400">Under the hood</div>
                <ul className="space-y-1 text-xs">
                  {act.explain.map((e) => (
                    <li key={e.feature} className="flex gap-2">
                      <b className="shrink-0 text-emerald-300">{e.feature}</b>
                      <span className="text-zinc-400">{e.where}</span>
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          </div>
        ) : token ? (
          consented ? (
            <LiveBuilder token={token} signupHref={signupHref} onBudget={onBudget} onClose={() => setMode("day")} />
          ) : (
            <LiveConsent
              clientIp={clientIp}
              agree={agree}
              onAgree={setAgree}
              onAccept={() => {
                rememberPledge();
                setConsented(true);
              }}
            />
          )
        ) : (
          <div className="mx-auto mt-16 flex max-w-md flex-col items-center gap-3 text-center text-zinc-400">
            <Loader2 aria-hidden="true" className="h-6 w-6 animate-spin shrink-0" />
            <p>
              The live studio is still opening a clean space for you. One moment - or watch the film while you wait.
            </p>
            <button
              type="button"
              onClick={() => setMode("day")}
              className="rounded-xl border border-white/15 bg-transparent px-4 py-2 text-sm text-zinc-200 hover:bg-white/5"
            >
              Back to the film
            </button>
          </div>
        )}
      </div>

      {/* transport */}
      {mode !== "build" && (
        <ShowtimeTransport
          t={t}
          total={total}
          title={act.title}
          chapters={track.chapters}
          index={index}
          playing={playing}
          speed={speed}
          captions={captions}
          narrate={narrate}
          seek={seek}
          jump={jump}
          setPlaying={setPlaying}
          setSpeed={setSpeed}
          setCaptions={setCaptions}
          setNarrate={setNarrate}
          enterBuild={enterBuild}
        />
      )}
    </div>
  );
}
