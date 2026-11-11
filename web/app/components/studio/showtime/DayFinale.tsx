"use client";

import { useState } from "react";
import { ArrowRight, Hammer } from "lucide-react";
import type { statsAt } from "./day";
import { ease, seg } from "./script";
import { useTranslations } from "../../../lib/i18n";

// The closing card of "An Impossible Day" (split out of DayStage): the day's
// totals and the visitor's own job, handed to the live builder.

// ---------------------------------------------------------------------------- finale
export default function Finale({
  stats,
  onBuild,
  signupHref,
  p,
}: {
  stats: ReturnType<typeof statsAt>;
  onBuild: (job: string) => void;
  signupHref: string;
  p: number;
}) {
  const tHC = useTranslations("hcComponentsStudioShowtimeDayFinale");
  const [job, setJob] = useState("");
  const k = ease(seg(p, 0.05, 0.3));
  return (
    <div
      className="flex h-full flex-col justify-center gap-4 rounded-3xl border border-white/10 bg-zinc-900/70 p-6 backdrop-blur"
      style={{ opacity: k, transform: `translateY(${(1 - k) * 14}px)` }}
    >
      <div>
        <div className="text-[11px] uppercase tracking-wider text-violet-300">{tHC("s7")}</div>
        <h3 className="mt-1 text-2xl font-semibold leading-tight">{tHC("s6")}</h3>
        <p className="mt-1 text-sm text-zinc-400">{tHC("s5")}</p>
      </div>
      <div className="grid grid-cols-2 gap-2 text-center">
        {[
          { v: stats.msgs, l: "messages sent", c: "" },
          { v: stats.tools, l: "tool calls", c: "" },
          { v: stats.asks, l: "needed her", c: "text-amber-300" },
          { v: stats.blocked, l: "mistake stopped", c: "text-rose-300" },
        ].map((s) => (
          <div key={s.l} className="rounded-xl border border-white/10 bg-black/25 py-2.5">
            <div className={`text-2xl font-semibold tabular-nums ${s.c}`}>{s.v}</div>
            <div className="text-[11px] text-zinc-400">{s.l}</div>
          </div>
        ))}
      </div>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          onBuild(job.trim());
        }}
        className="space-y-2"
      >
        <label htmlFor="day-job" className="block text-[11px] uppercase tracking-wider text-zinc-400">
          {tHC("s4")}
        </label>
        <textarea
          id="day-job"
          value={job}
          onChange={(e) => setJob(e.target.value.slice(0, 240))}
          rows={3}
          placeholder={tHC("s3")}
          className="w-full resize-none rounded-xl border border-white/15 bg-black/30 p-3 text-sm outline-none placeholder:text-zinc-400 focus:border-violet-400"
        />
        <button
          type="submit"
          className="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-4 py-3 text-sm font-semibold shadow-lg shadow-violet-500/30 hover:brightness-110"
        >
          <Hammer aria-hidden="true" className="h-4 w-4 shrink-0" /> {tHC("s2")}{" "}
          <ArrowRight aria-hidden="true" className="h-4 w-4 shrink-0" />
        </button>
      </form>
      <a
        href={signupHref}
        className="text-center text-xs text-zinc-400 underline-offset-2 hover:text-zinc-100 hover:underline"
      >
        {tHC("s1")}
      </a>
    </div>
  );
}
