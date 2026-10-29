"use client";

import { useEffect, useState } from "react";
import { motion } from "motion/react";
import { ArrowRight, Clapperboard, Loader2, ShieldCheck, Sparkles, Plug } from "lucide-react";
import { Button } from "../ui/button";
import { PLATFORM_FACTS } from "../../lib/platform-facts";
import { JOB_PLACEHOLDERS } from "./topicDraft";
import { TONES } from "./studioTheme";
import { PhoneReel } from "./PhoneStage";
import ChatbotVsAgent from "./ChatbotVsAgent";
import { useTranslations } from "../../lib/i18n";

// The opening. One promise - say a job, hold the agent in your hand - and the
// smallest possible first step: a text box that already IS the first question
// of the briefing. Typing here and pressing Go carries the words into the real
// launcher below, so the visitor's first action is also their first real step,
// not a click that scrolls them somewhere else.
//
// The breadth strip reads PLATFORM_FACTS (the one number source) - counts, not
// capacity or speed claims - see the do-not-claim list in that file.

type Props = {
  phase: "booting" | "ready" | "unavailable";
  bootLine: string;
  onLaunch: (job: string) => void;
  /** Opens the full-screen trailer. */
  onShowtime: () => void;
};

export default function StudioHero({ phase, bootLine, onLaunch, onShowtime }: Props) {
    const tHC = useTranslations("hcComponentsStudioStudioHero");
  const [text, setText] = useState("");
  const [ph, setPh] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => setPh((n) => (n + 1) % JOB_PLACEHOLDERS.length), 3200);
    return () => window.clearInterval(id);
  }, []);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    onLaunch(text.trim());
  };

  return (
    <section aria-labelledby="hero-title" className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -top-24 left-1/2 -z-10 h-[420px] w-[820px] -translate-x-1/2 rounded-full bg-gradient-to-r from-violet-500/20 via-sky-500/10 to-emerald-500/20 blur-3xl"
      />
      <div className="grid items-center gap-10 lg:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
        <div>
          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            className="mb-4 inline-flex items-center gap-2 rounded-full border border-violet-500/30 bg-violet-500/10 px-3 py-1 text-xs text-violet-700 dark:text-violet-300"
          >
            <ShieldCheck aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> {tHC("hcComponentsStudioStudioHero.s11")}
          </motion.div>
          <h1 id="hero-title" className="text-4xl font-semibold leading-[1.05] tracking-tight sm:text-6xl">
            {tHC("hcComponentsStudioStudioHero.s10")}
            <br />
            <span className="bg-gradient-to-r from-violet-600 via-indigo-500 to-sky-500 bg-clip-text text-transparent">
              {tHC("hcComponentsStudioStudioHero.s9")}
            </span>
          </h1>
          <p className="mt-5 max-w-xl text-lg text-muted-foreground">
            {tHC("hcComponentsStudioStudioHero.s8")} <b className="text-foreground">{tHC("hcComponentsStudioStudioHero.s7")}</b> {tHC("hcComponentsStudioStudioHero.s6")}
          </p>

          <form onSubmit={submit} className="mt-7 max-w-xl" data-tour="hero-launch">
            <label
              htmlFor="hero-job"
              className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground"
            >
              {tHC("hcComponentsStudioStudioHero.s5")}
            </label>
            <div className="flex flex-col gap-2 rounded-2xl border border-violet-500/30 bg-background/70 p-2 shadow-lg shadow-violet-500/10 backdrop-blur sm:flex-row">
              <input
                id="hero-job"
                value={text}
                maxLength={240}
                onChange={(e) => setText(e.target.value)}
                placeholder={JOB_PLACEHOLDERS[ph]}
                className="min-w-0 flex-1 rounded-xl bg-transparent px-3 py-2.5 text-base outline-none placeholder:text-muted-foreground"
              />
              <Button type="submit" size="lg" className={TONES.violet.cta} disabled={phase === "unavailable"}>
                <Sparkles aria-hidden="true" className="me-2 h-4 w-4 shrink-0" /> {tHC("hcComponentsStudioStudioHero.s4")}{" "}
                <ArrowRight aria-hidden="true" className="ms-2 h-4 w-4 shrink-0" />
              </Button>
            </div>
            <div className="mt-2 flex min-h-5 items-center gap-2 text-xs text-muted-foreground" aria-live="polite">
              {phase === "booting" ? (
                <>
                  <Loader2 aria-hidden="true" className="h-3.5 w-3.5 animate-spin shrink-0" /> {bootLine}
                </>
              ) : (
                <>
                  <Plug aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> {tHC("hcComponentsStudioStudioHero.s3")}
                </>
              )}
            </div>
          </form>

          <button
            type="button"
            onClick={onShowtime}
            data-tour="showtime-btn"
            className="group mt-4 inline-flex items-center gap-3 rounded-2xl border border-emerald-500/30 bg-emerald-500/10 py-2 ps-2 pe-5 text-start transition hover:border-emerald-400 hover:bg-emerald-500/15"
          >
            <span className="inline-flex h-11 w-11 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-500 to-teal-500 text-emerald-950 shadow-lg shadow-emerald-500/30 transition-transform group-hover:scale-105">
              <Clapperboard aria-hidden="true" className="h-5 w-5 shrink-0" />
            </span>
            <span>
              <span className="block text-sm font-semibold">{tHC("hcComponentsStudioStudioHero.s2")}</span>
              <span className="block text-xs text-muted-foreground">
                {tHC("hcComponentsStudioStudioHero.s1")}
              </span>
            </span>
          </button>

          <dl className="mt-8 grid max-w-xl grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-4">
            {[
              { k: `${PLATFORM_FACTS.channels}`, v: "channels to reach people" },
              { k: `${PLATFORM_FACTS.builtInToolsFloor}+`, v: "built-in tools" },
              { k: `${PLATFORM_FACTS.byokProviders}`, v: "model providers you can bring" },
              { k: `${PLATFORM_FACTS.interfaceLocales}`, v: "interface languages" },
            ].map((s) => (
              <div key={s.v}>
                <dt className="text-2xl font-semibold tracking-tight">{s.k}</dt>
                <dd className="text-[11px] leading-snug text-muted-foreground">{s.v}</dd>
              </div>
            ))}
          </dl>
        </div>

        <div className="relative pb-2">
          <PhoneReel />
        </div>
      </div>

      <ChatbotVsAgent />
    </section>
  );
}
