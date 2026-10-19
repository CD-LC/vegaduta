"use client";

import { useEffect, useRef, useState } from "react";
import {
  Brain,
  Compass,
  FileText,
  Hand,
  NotebookPen,
  Radio,
  ShieldCheck,
  Smartphone,
  UserCheck,
  ArrowRight,
  Check,
} from "lucide-react";
import { Button } from "../ui/button";
import { PLATFORM_FACTS } from "../../lib/platform-facts";
import PhoneStage, { type PhoneScene } from "./PhoneStage";
import { TONES, type Tone } from "./studioTheme";

// The whole journey, explained, one scroll. The phone on the left is sticky and
// changes scene as each chapter reaches the middle of the screen, so the words
// and the picture always agree. Every bullet names something the studio below
// does - or, where it is a workspace feature, says so out loud. Numbers come
// from PLATFORM_FACTS.

type Chapter = {
  id: string;
  scene: PhoneScene;
  tone: Tone;
  icon: typeof Brain;
  kicker: string;
  title: string;
  body: string;
  points: { text: string; where: "here" | "workspace" }[];
};

const CHAPTERS: Chapter[] = [
  {
    id: "say",
    scene: "brief",
    tone: "violet",
    icon: NotebookPen,
    kicker: "1 - Say it",
    title: "Describe the job in your own words",
    body: "Five plain questions: the job, who it serves, what it must never do, what it needs, how careful to be. No prompt engineering - the studio turns your answers into a working brief you can rewrite line by line.",
    points: [
      { text: "Nothing is pre-built. The agent is composed from what you say.", where: "here" },
      { text: "Any job at all - invoices, tutoring, contracts, crops, code reviews.", where: "here" },
    ],
  },
  {
    id: "dials",
    scene: "built",
    tone: "violet",
    icon: Brain,
    kicker: "2 - It is built",
    title: "Then turn the dials that make it precise",
    body: "A real agent appears with its own hands. Choose how hard it thinks, how freely it acts, and which rules run on every single turn.",
    points: [
      { text: "Thinking depth: fast, careful, rigorous or planned.", where: "here" },
      { text: "Autonomy and creativity dials, with the trade-off explained beside each.", where: "here" },
      {
        text: `Guardrails on every turn - personal data, tone, policy, jailbreaks (${PLATFORM_FACTS.guardrailTypes} rule types in a workspace).`,
        where: "here",
      },
    ],
  },
  {
    id: "feed",
    scene: "docs",
    tone: "sky",
    icon: FileText,
    kicker: "3 - Feed it",
    title: "Give it your documents. Plug in real tools.",
    body: "Upload the policy, the price list, the contract. It answers from your pages and tells you which one. Search the MCP registry for tools - calendars, code hosts, maps, data services - to see what your agent could reach.",
    points: [
      { text: "Text documents are searched and cited here.", where: "here" },
      { text: "PDF, Word and scanned files (with OCR), and connecting MCP servers live.", where: "workspace" },
    ],
  },
  {
    id: "work",
    scene: "run",
    tone: "emerald",
    icon: Hand,
    kicker: "4 - Watch it work",
    title: "Every search, page, API call and line of code - on screen",
    body: "Give it a mission and watch the trace stream in: what it searched, what it opened, what it computed, and why. No black box, so you know exactly how far to trust it.",
    points: [
      { text: "Live web search, page reading, API calls and code execution.", where: "here" },
      { text: `${PLATFORM_FACTS.builtInToolsFloor}+ built-in tools, plus any MCP server.`, where: "workspace" },
    ],
  },
  {
    id: "ask",
    scene: "ask",
    tone: "amber",
    icon: UserCheck,
    kicker: "5 - It asks you",
    title: "Chain agents together - and stay the decision-maker",
    body: "Line agents up into a workflow and drop in an approval step. The run really pauses, waits for you, and only continues on your yes. Autonomy is something you grant, one step at a time.",
    points: [
      { text: "A real approval pause inside a chained workflow.", where: "here" },
      {
        text: `Visual builder with ${PLATFORM_FACTS.workflowNodeTypes} node types, schedules, audit trail and per-user spending limits.`,
        where: "workspace",
      },
    ],
  },
  {
    id: "pocket",
    scene: "pocket",
    tone: "sky",
    icon: Smartphone,
    kicker: "6 - In your pocket",
    title: "Scan a code. It is on your phone.",
    body: "Publish to a link that opens on any phone - with a QR code to scan straight off this screen - or pair it with Telegram. Create the free account, sign in on the Android app, and your agent is there to chat with.",
    points: [
      { text: "Share link + QR, live now. Telegram pairing when the studio has a bot free.", where: "here" },
      {
        text: `${PLATFORM_FACTS.channels} channels in a workspace: ${PLATFORM_FACTS.freeChannels.join(", ")} free, then Slack, Discord, Instagram, Messenger, X and voice.`,
        where: "workspace",
      },
    ],
  },
];

export default function FlightPlan({ onStart, onShowtime }: { onStart: () => void; onShowtime: () => void }) {
  const [active, setActive] = useState(0);
  const refs = useRef<(HTMLElement | null)[]>([]);

  useEffect(() => {
    const els = refs.current.filter((e): e is HTMLElement => !!e);
    if (!els.length || typeof IntersectionObserver === "undefined") return undefined;
    const io = new IntersectionObserver(
      (entries) => {
        for (const en of entries) {
          if (en.isIntersecting) {
            const i = Number((en.target as HTMLElement).dataset.idx);
            if (!Number.isNaN(i)) setActive(i);
          }
        }
      },
      // A thin band across the middle of the viewport: the chapter under it is "current".
      { rootMargin: "-45% 0px -45% 0px", threshold: 0 }
    );
    els.forEach((e) => io.observe(e));
    return () => io.disconnect();
  }, []);

  const current = CHAPTERS[active];

  return (
    <section aria-labelledby="flight-title" className="mt-24">
      <div className="max-w-2xl">
        <div className="mb-2 inline-flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-violet-700 dark:text-violet-300">
          <Compass aria-hidden="true" className="h-4 w-4 shrink-0" /> The flight plan
        </div>
        <h2 id="flight-title" className="text-3xl font-semibold tracking-tight sm:text-4xl">
          From an empty page to a phone in your hand. Six moves.
        </h2>
        <p className="mt-3 text-muted-foreground">
          Everything marked <Badge2 kind="here" /> you can do in this page, right now. Everything marked{" "}
          <Badge2 kind="workspace" /> is what a free workspace adds - said plainly, so nothing here surprises you later.
        </p>
      </div>

      <div className="mt-10 grid gap-10 lg:grid-cols-[300px_minmax(0,1fr)] lg:gap-16">
        <div className="hidden lg:block">
          <div className="sticky top-24 flex flex-col items-center">
            <PhoneStage scene={current.scene} illustration />
            <ol className="mt-6 flex gap-1.5" aria-hidden>
              {CHAPTERS.map((c, i) => (
                <li
                  key={c.id}
                  className={`h-1.5 rounded-full transition-all ${i === active ? `w-8 ${TONES[c.tone].solid}` : "w-3 bg-muted-foreground/25"}`}
                />
              ))}
            </ol>
          </div>
        </div>

        <ol className="space-y-6 lg:space-y-[18vh]">
          {CHAPTERS.map((c, i) => {
            const t = TONES[c.tone];
            const on = i === active;
            return (
              <li
                key={c.id}
                ref={(el) => {
                  refs.current[i] = el;
                }}
                data-idx={i}
                className={`rounded-3xl border p-6 transition-all duration-500 sm:p-8 ${on ? `${t.selected} lg:scale-[1.01]` : "border-border/60 lg:opacity-60"}`}
              >
                <div
                  className={`mb-3 inline-flex items-center gap-2 text-xs font-medium uppercase tracking-wide ${t.text}`}
                >
                  <span className={`inline-flex h-7 w-7 items-center justify-center rounded-full ${t.soft}`}>
                    <c.icon className="h-4 w-4" />
                  </span>
                  {c.kicker}
                </div>
                <h3 className="text-2xl font-semibold leading-snug tracking-tight">{c.title}</h3>
                <p className="mt-2 text-muted-foreground">{c.body}</p>
                <ul className="mt-4 space-y-2">
                  {c.points.map((p) => (
                    <li key={p.text} className="flex items-start gap-2.5 text-sm">
                      <Check aria-hidden="true" className={`mt-0.5 h-4 w-4 shrink-0 ${t.text}`} />
                      <span className="flex-1">{p.text}</span>
                      <Badge2 kind={p.where} />
                    </li>
                  ))}
                </ul>
                {/* On phones there is no sticky phone: show the scene as a compact chip instead of hiding the picture entirely. */}
                <div className="mt-4 flex items-center gap-2 text-xs text-muted-foreground lg:hidden">
                  <Radio aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> On your phone this looks like:{" "}
                  <b className="text-foreground">{sceneName(c.scene)}</b>
                </div>
              </li>
            );
          })}
        </ol>
      </div>

      <div className="mt-12 flex flex-wrap items-center gap-3 rounded-2xl border border-violet-500/30 bg-violet-500/5 p-5">
        <ShieldCheck aria-hidden="true" className="h-5 w-5 text-violet-600 dark:text-violet-300 shrink-0" />
        <span className="flex-1 text-sm">
          Six moves, and the first four take minutes. Start with the job - the rest follows.
        </span>
        <Button variant="outline" onClick={onShowtime}>
          Watch it as a film
        </Button>
        <Button className={TONES.violet.cta} onClick={onStart}>
          Do it for real <ArrowRight aria-hidden="true" className="ml-2 h-4 w-4 shrink-0" />
        </Button>
      </div>
    </section>
  );
}

function sceneName(s: PhoneScene): string {
  return {
    idle: "waiting",
    brief: "a brief being written",
    built: "an agent card",
    docs: "documents being read",
    run: "live steps",
    ask: "a push asking your OK",
    pocket: "an app icon and a QR",
  }[s];
}

function Badge2({ kind }: { kind: "here" | "workspace" }) {
  return kind === "here" ? (
    <span className="inline-flex shrink-0 items-center rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-medium text-emerald-700 dark:text-emerald-300">
      Do it here
    </span>
  ) : (
    <span className="inline-flex shrink-0 items-center rounded-full bg-violet-500/15 px-2 py-0.5 text-[10px] font-medium text-violet-700 dark:text-violet-300">
      In a workspace
    </span>
  );
}
