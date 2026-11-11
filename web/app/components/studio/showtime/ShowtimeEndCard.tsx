"use client";

import Link from "next/link";
import { ArrowRight, Hammer, RotateCcw } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";

// The trailer's last card (split out of Showtime.tsx): "that was a script, the
// studio is real" with the way into live mode, signup and replay.

export default function EndCard({
  onBuild,
  onReplay,
  signupHref,
  p,
}: {
  onBuild: () => void;
  onReplay: () => void;
  signupHref: string;
  p: number;
}) {
  const tHC = useTranslations("hcComponentsStudioShowtimeShowtimeEndCard");
  const k = Math.min(1, p * 4);
  return (
    <div
      className="flex h-full flex-col items-center justify-center rounded-3xl border border-white/10 bg-zinc-900/60 px-6 text-center backdrop-blur"
      style={{ opacity: k, transform: `translateY(${(1 - k) * 16}px)` }}
    >
      <h2 className="max-w-xl text-4xl font-semibold tracking-tight sm:text-5xl">
        {tHC("s5")}
        <br />
        <span className="bg-gradient-to-r from-violet-300 to-sky-300 bg-clip-text text-transparent">{tHC("s4")}</span>
      </h2>
      <p className="mt-4 max-w-md text-zinc-300">{tHC("s3")}</p>
      <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
        <button
          type="button"
          onClick={onBuild}
          className="inline-flex items-center gap-2 rounded-2xl bg-gradient-to-r from-emerald-500 to-teal-500 px-6 py-3 text-base font-semibold text-emerald-950 shadow-xl shadow-emerald-500/30 hover:brightness-110"
        >
          <Hammer aria-hidden="true" className="h-5 w-5 shrink-0" /> {tHC("s2")}{" "}
          <ArrowRight aria-hidden="true" className="h-4 w-4 shrink-0" />
        </button>
        <Link
          href={signupHref}
          className="inline-flex items-center gap-2 rounded-2xl border border-white/20 px-5 py-3 text-base hover:bg-white/10"
        >
          {tHC("s1")}
        </Link>
        <button
          type="button"
          onClick={onReplay}
          className="inline-flex items-center gap-1.5 rounded-2xl bg-transparent px-4 py-3 text-sm text-zinc-400 hover:text-zinc-100"
        >
          <RotateCcw aria-hidden="true" className="h-4 w-4 shrink-0" /> Replay
        </button>
      </div>
    </div>
  );
}
