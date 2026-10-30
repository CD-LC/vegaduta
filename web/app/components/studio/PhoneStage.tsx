"use client";

import { useEffect, useMemo, useState } from "react";
import Image from "next/image";
import { motion, AnimatePresence } from "motion/react";
import {
  Check,
  FileText,
  Globe,
  Link2,
  MessageCircle,
  Send,
  ShieldCheck,
  Sparkles,
  Wifi,
  BatteryFull,
  Signal,
  Code2,
  Bell,
} from "lucide-react";
import { qrPath, QR_QUIET } from "../../lib/qr";
import { toolLabel } from "./studioContent";
import { useTranslations } from "../../lib/i18n";

// The phone. The one object the whole page is built around: whatever the
// visitor has made, it ends up here - so the phone is shown on the first screen,
// follows them down the page, and finishes as the thing they scan.
//
// Two honest modes, and the label says which:
//   - illustration (hero / scroll story): a looping sample, tagged as such.
//   - live (studio): rendered from the visitor's real agent - its name, its
//     tools, its mission and, once it exists, the real share link as a real QR.
// The trace rows in the live "run" scene are a PREVIEW of which of the agent's
// tools it can reach for; the real trace is in the Run stage. The footer tag
// says so rather than letting a preview pass for a transcript.

export type PhoneScene = "idle" | "brief" | "built" | "docs" | "run" | "ask" | "pocket";

export const SCENE_ORDER: PhoneScene[] = ["brief", "built", "docs", "run", "ask", "pocket"];

export const SCENE_LABEL: Record<PhoneScene, string> = {
  idle: "Waiting",
  brief: "Say it",
  built: "Built",
  docs: "Fed",
  run: "Working",
  ask: "Asks you",
  pocket: "In your pocket",
};

type Props = {
  scene: PhoneScene;
  agentName?: string;
  job?: string;
  mission?: string;
  tools?: string[];
  shareUrl?: string | null;
  /** True = a looping sample rather than the visitor's own agent. */
  illustration?: boolean;
  className?: string;
};

const SAMPLE = {
  name: "Invoice chaser",
  job: "Every Monday, find invoices unpaid for 15+ days and draft a polite reminder for each customer.",
  tools: ["fetch_web_page", "run_code", "search_knowledge"],
};

function initialOf(name: string): string {
  const m = name.trim().match(/[\p{L}\p{N}]/u);
  return (m ? m[0] : "A").toUpperCase();
}

/** Types a string out once. Restarts whenever the text changes. */
function useTyped(text: string, active: boolean, msPerChar = 22): string {
  const [n, setN] = useState(active ? 0 : text.length);
  useEffect(() => {
    if (!active) {
      setN(text.length);
      return undefined;
    }
    setN(0);
    const id = window.setInterval(() => setN((c) => (c >= text.length ? c : c + 1)), msPerChar);
    return () => window.clearInterval(id);
  }, [text, active, msPerChar]);
  return text.slice(0, n);
}

export default function PhoneStage({
  scene,
  agentName,
  job,
  mission,
  tools,
  shareUrl,
  illustration = false,
  className = "",
}: Props) {
  const name = agentName?.trim() || (illustration ? SAMPLE.name : "Your agent");
  const jobText = (job?.trim() || (illustration ? SAMPLE.job : "")).slice(0, 140);
  const toolList = tools && tools.length ? tools : illustration ? SAMPLE.tools : [];

  return (
    <div
      className={`relative mx-auto w-[248px] select-none ${className}`}
      aria-hidden={illustration ? true : undefined}
    >
      {/* Aura: colour follows the scene so the phone feels alive from across the page. */}
      <motion.div
        aria-hidden
        className="pointer-events-none absolute -inset-10 -z-10 rounded-full blur-3xl"
        animate={{ background: AURA[scene] }}
        transition={{ duration: 0.8 }}
      />
      <div className="relative rounded-[2.4rem] border border-white/10 bg-zinc-950 p-[9px] shadow-2xl shadow-violet-900/30 ring-1 ring-black/40">
        {/* side buttons */}
        <span className="absolute -left-[3px] top-24 h-10 w-[3px] rounded-l bg-zinc-800" />
        <span className="absolute -right-[3px] top-32 h-16 w-[3px] rounded-r bg-zinc-800" />
        <div className="relative h-[500px] overflow-hidden rounded-[1.9rem] bg-gradient-to-b from-zinc-900 to-zinc-950 text-zinc-100">
          {/* status bar + island */}
          <div className="absolute inset-x-0 top-0 z-20 flex items-center justify-between px-5 pt-2 text-[10px] font-medium text-zinc-300">
            <span>9:41</span>
            <span className="absolute left-1/2 top-2 h-[18px] w-[64px] -translate-x-1/2 rounded-full bg-black" />
            <span className="flex items-center gap-1">
              <Signal aria-hidden="true" className="h-2.5 w-2.5 shrink-0" />
              <Wifi aria-hidden="true" className="h-2.5 w-2.5 shrink-0" />
              <BatteryFull aria-hidden="true" className="h-3 w-3 shrink-0" />
            </span>
          </div>

          <AnimatePresence mode="wait">
            <motion.div
              key={scene}
              initial={{ opacity: 0, y: 10, filter: "blur(4px)" }}
              animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
              exit={{ opacity: 0, y: -8, filter: "blur(4px)" }}
              transition={{ duration: 0.35 }}
              className="absolute inset-0 pt-10"
            >
              {scene === "idle" && <Idle />}
              {scene === "brief" && <Brief job={jobText} animate={illustration} />}
              {scene === "built" && <Built name={name} job={jobText} tools={toolList} />}
              {scene === "docs" && <Docs name={name} />}
              {scene === "run" && (
                <Run name={name} mission={mission || jobText} tools={toolList} illustration={illustration} />
              )}
              {scene === "ask" && <Ask name={name} />}
              {scene === "pocket" && <Pocket name={name} shareUrl={shareUrl ?? null} />}
            </motion.div>
          </AnimatePresence>

          {/* home indicator + honesty tag */}
          <div className="absolute inset-x-0 bottom-0 z-20 flex flex-col items-center gap-1 pb-1.5">
            <span className="rounded-full bg-white/5 px-2 py-0.5 text-[8px] uppercase tracking-wider text-zinc-400">
              {illustration ? "Illustration" : scene === "idle" ? "Yours appears here" : "Preview of your agent"}
            </span>
            <span className="h-1 w-20 rounded-full bg-zinc-600" />
          </div>
        </div>
      </div>
    </div>
  );
}

const AURA: Record<PhoneScene, string> = {
  idle: "rgba(139,92,246,0.18)",
  brief: "rgba(139,92,246,0.28)",
  built: "rgba(99,102,241,0.30)",
  docs: "rgba(14,165,233,0.28)",
  run: "rgba(16,185,129,0.28)",
  ask: "rgba(245,158,11,0.30)",
  pocket: "rgba(56,189,248,0.32)",
};

function AppBar({ name, sub }: { name: string; sub?: string }) {
  return (
    <div className="flex items-center gap-2 border-b border-white/5 px-3 pb-2">
      <span className="inline-flex h-7 w-7 items-center justify-center rounded-full bg-gradient-to-br from-violet-500 to-indigo-600 text-xs font-semibold text-white">
        {initialOf(name)}
      </span>
      <div className="min-w-0 leading-tight">
        <div className="truncate text-[12px] font-medium">{name}</div>
        <div className="flex items-center gap-1 text-[9px] text-emerald-400">
          <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" /> {sub ?? "online"}
        </div>
      </div>
    </div>
  );
}

function Idle() {
  const tHC = useTranslations("hcComponentsStudioPhoneStage");
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 px-6 text-center">
      <div className="relative">
        <motion.span
          className="absolute inset-0 rounded-full border border-violet-400/40"
          animate={{ scale: [1, 1.5], opacity: [0.6, 0] }}
          transition={{ duration: 2.4, repeat: Infinity, ease: "easeOut" }}
        />
        <motion.span
          className="absolute inset-0 rounded-full border border-violet-400/40"
          animate={{ scale: [1, 1.5], opacity: [0.6, 0] }}
          transition={{ duration: 2.4, repeat: Infinity, ease: "easeOut", delay: 1.2 }}
        />
        <div className="relative flex h-16 w-16 items-center justify-center rounded-full bg-zinc-800 ring-1 ring-white/10">
          <Image src="/logo-mark.png" alt="" width={36} height={36} className="opacity-90" />
        </div>
      </div>
      <div>
        <div className="text-sm font-medium">{tHC("s18")}</div>
        <p className="mt-1 text-[11px] leading-relaxed text-zinc-400">{tHC("s17")}</p>
      </div>
      <div className="w-full space-y-1.5 opacity-60">
        {[70, 90, 55].map((w, i) => (
          <motion.div
            key={i}
            className="h-2 rounded bg-white/10"
            style={{ width: `${w}%` }}
            animate={{ opacity: [0.4, 1, 0.4] }}
            transition={{ duration: 2, repeat: Infinity, delay: i * 0.3 }}
          />
        ))}
      </div>
    </div>
  );
}

function Brief({ job, animate }: { job: string; animate: boolean }) {
  const tHC = useTranslations("hcComponentsStudioPhoneStage");
  const shown = useTyped(job || "Describe the job…", animate);
  return (
    <div className="flex h-full flex-col px-3 pb-10">
      <div className="mb-3 flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wider text-violet-300">
        <Sparkles aria-hidden="true" className="h-3 w-3 shrink-0" /> {tHC("s16")}
      </div>
      <div className="rounded-2xl border border-violet-400/30 bg-violet-500/10 p-3">
        <div className="mb-1 text-[10px] text-violet-200">{tHC("s15")}</div>
        <p className="min-h-[92px] text-[12.5px] leading-relaxed">
          {shown}
          <motion.span
            className="ms-0.5 inline-block h-3.5 w-[2px] translate-y-0.5 bg-violet-300"
            animate={{ opacity: [1, 0, 1] }}
            transition={{ duration: 0.9, repeat: Infinity }}
          />
        </p>
      </div>
      <div className="mt-3 flex flex-wrap gap-1.5">
        {["Who it serves", "Never do", "Needs", "Care"].map((c, i) => (
          <motion.span
            key={c}
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: 0.4 + i * 0.15 }}
            className="rounded-full border border-white/10 px-2 py-0.5 text-[9.5px] text-zinc-300"
          >
            {c}
          </motion.span>
        ))}
      </div>
      <motion.div
        className="mt-auto rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 py-2 text-center text-[12px] font-medium"
        animate={{ boxShadow: ["0 0 0 0 rgba(139,92,246,0.5)", "0 0 0 10px rgba(139,92,246,0)"] }}
        transition={{ duration: 1.6, repeat: Infinity }}
      >
        {tHC("s14")}
      </motion.div>
    </div>
  );
}

function Built({ name, job, tools }: { name: string; job: string; tools: string[] }) {
  const tHC = useTranslations("hcComponentsStudioPhoneStage");
  return (
    <div className="flex h-full flex-col px-3 pb-10">
      <motion.div
        initial={{ scale: 0.6, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ type: "spring", stiffness: 260, damping: 18 }}
        className="mx-auto mt-2 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-violet-500 to-indigo-600 text-2xl font-semibold shadow-lg shadow-violet-500/40"
      >
        {initialOf(name)}
      </motion.div>
      <div className="mt-2 text-center">
        <div className="text-[14px] font-semibold">{name}</div>
        <div className="mt-0.5 inline-flex items-center gap-1 rounded-full bg-emerald-500/15 px-2 py-0.5 text-[9px] text-emerald-300">
          <Check aria-hidden="true" className="h-2.5 w-2.5 shrink-0" /> {tHC("s13")}
        </div>
      </div>
      {job && (
        <p className="mt-3 line-clamp-4 rounded-xl border border-white/10 bg-white/5 p-2.5 text-[11px] leading-relaxed text-zinc-300">
          {job}
        </p>
      )}
      <div className="mt-3 text-[9px] uppercase tracking-wider text-zinc-400">Hands</div>
      <div className="mt-1 flex flex-wrap gap-1.5">
        {tools.length === 0 && <span className="text-[10px] text-zinc-400">{tHC("s12")}</span>}
        {tools.slice(0, 6).map((t, i) => (
          <motion.span
            key={t}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.15 + i * 0.12 }}
            className="rounded-full border border-emerald-400/30 bg-emerald-500/10 px-2 py-0.5 text-[10px] text-emerald-200"
          >
            {toolLabel(t)}
          </motion.span>
        ))}
      </div>
    </div>
  );
}

function Docs({ name }: { name: string }) {
  const tHC = useTranslations("hcComponentsStudioPhoneStage");
  const files = [
    { n: "price-list.txt", d: "read" },
    { n: "returns-policy.txt", d: "read" },
    { n: "faq.txt", d: "read" },
  ];
  return (
    <div className="flex h-full flex-col px-3 pb-10">
      <AppBar name={name} sub="learning your documents" />
      <div className="mt-3 space-y-2">
        {files.map((f, i) => (
          <motion.div
            key={f.n}
            initial={{ x: 60, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            transition={{ delay: 0.15 + i * 0.35, type: "spring", stiffness: 200, damping: 20 }}
            className="flex items-center gap-2 rounded-xl border border-sky-400/25 bg-sky-500/10 px-2.5 py-2"
          >
            <FileText aria-hidden="true" className="h-4 w-4 text-sky-300 shrink-0" />
            <span className="flex-1 truncate text-[11px]">{f.n}</span>
            <motion.span
              initial={{ scale: 0 }}
              animate={{ scale: 1 }}
              transition={{ delay: 0.5 + i * 0.35 }}
              className="text-emerald-300"
            >
              <Check aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
            </motion.span>
          </motion.div>
        ))}
      </div>
      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 1.4 }}
        className="mt-4 rounded-2xl rounded-ss-sm bg-white/10 p-2.5 text-[11.5px] leading-relaxed"
      >
        {tHC("s11")} <b>{tHC("s10")}</b> {tHC("s9")}
      </motion.div>
    </div>
  );
}

function Run({
  name,
  mission,
  tools,
  illustration,
}: {
  name: string;
  mission: string;
  tools: string[];
  illustration: boolean;
}) {
  const steps = useMemo(() => {
    const base = tools.length ? tools.slice(0, 3).map((t) => toolLabel(t)) : ["Think it through"];
    return [...base, "Write the answer"];
  }, [tools]);
  return (
    <div className="flex h-full flex-col px-3 pb-10">
      <AppBar name={name} sub="working" />
      <div className="ms-auto mt-3 max-w-[85%] rounded-2xl rounded-se-sm bg-violet-600 p-2.5 text-[11.5px] leading-relaxed">
        {(mission || "Run today's job").slice(0, 110)}
      </div>
      <div className="mt-3 space-y-1.5">
        {steps.map((s, i) => (
          <motion.div
            key={s + i}
            initial={{ opacity: 0, x: -10 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ delay: 0.3 + i * 0.7 }}
            className="flex items-center gap-2 rounded-lg border border-emerald-400/25 bg-emerald-500/10 px-2 py-1.5 text-[10.5px]"
          >
            {i === 0 ? (
              <Globe aria-hidden="true" className="h-3 w-3 text-emerald-300 shrink-0" />
            ) : i === steps.length - 1 ? (
              <Sparkles aria-hidden="true" className="h-3 w-3 text-emerald-300 shrink-0" />
            ) : (
              <Code2 aria-hidden="true" className="h-3 w-3 text-emerald-300 shrink-0" />
            )}
            <span className="flex-1 truncate">{s}</span>
            <motion.span
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ delay: 0.3 + i * 0.7 + 0.5 }}
              className="text-emerald-300"
            >
              <Check aria-hidden="true" className="h-3 w-3 shrink-0" />
            </motion.span>
          </motion.div>
        ))}
      </div>
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.3 + steps.length * 0.7 }}
        className="mt-3 space-y-1.5 rounded-2xl rounded-ss-sm bg-white/10 p-2.5"
      >
        {[95, 80, 60].map((w) => (
          <div key={w} className="h-1.5 rounded bg-white/25" style={{ width: `${w}%` }} />
        ))}
      </motion.div>
      <div className="mt-auto text-center text-[9px] text-zinc-400">
        {illustration ? "Every step is visible - nothing hidden." : "Real steps stream in the Run stage."}
      </div>
    </div>
  );
}

function Ask({ name }: { name: string }) {
  const tHC = useTranslations("hcComponentsStudioPhoneStage");
  const [decided, setDecided] = useState(false);
  useEffect(() => {
    setDecided(false);
    const id = window.setTimeout(() => setDecided(true), 3200);
    return () => window.clearTimeout(id);
  }, []);
  return (
    <div className="flex h-full flex-col px-3 pb-10">
      <div className="mb-2 text-center text-[10px] text-zinc-400">{tHC("s8")}</div>
      <motion.div
        initial={{ y: -40, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        transition={{ type: "spring", stiffness: 220, damping: 20, delay: 0.2 }}
        className="rounded-2xl border border-amber-400/30 bg-zinc-800/90 p-3 shadow-xl shadow-amber-500/10"
      >
        <div className="mb-1 flex items-center gap-1.5 text-[9px] uppercase tracking-wider text-amber-300">
          <Bell aria-hidden="true" className="h-3 w-3 shrink-0" /> {name} {tHC("s7")}
        </div>
        <p className="text-[11.5px] leading-relaxed">{tHC("s6")}</p>
        <div className="mt-2.5 flex gap-2">
          <motion.button
            type="button"
            tabIndex={-1}
            className="flex-1 rounded-lg bg-emerald-600 py-1.5 text-[11px] font-medium"
            animate={decided ? { scale: [1, 1.1, 1] } : {}}
          >
            {decided ? (
              <span className="inline-flex items-center justify-center gap-1">
                Approved <Check className="h-3 w-3 shrink-0" aria-hidden="true" />
              </span>
            ) : (
              "Approve"
            )}
          </motion.button>
          <button type="button" tabIndex={-1} className="flex-1 rounded-lg bg-white/10 py-1.5 text-[11px]">
            {tHC("s5")}
          </button>
        </div>
      </motion.div>
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.9 }}
        className="mt-4 flex items-start gap-2 rounded-xl border border-white/10 bg-white/5 p-2.5 text-[10.5px] leading-relaxed text-zinc-300"
      >
        <ShieldCheck aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-300" />
        {tHC("s4")}
      </motion.div>
    </div>
  );
}

function Pocket({ name, shareUrl }: { name: string; shareUrl: string | null }) {
  const tHC = useTranslations("hcComponentsStudioPhoneStage");
  const qr = useMemo(() => {
    if (!shareUrl) return null;
    try {
      return qrPath(shareUrl, "M");
    } catch {
      return null;
    }
  }, [shareUrl]);
  const rows = [
    { icon: Link2, label: "Link", tone: "text-sky-300" },
    { icon: Send, label: "Telegram", tone: "text-cyan-300" },
    { icon: MessageCircle, label: "WhatsApp", tone: "text-emerald-300" },
  ];
  return (
    <div className="flex h-full flex-col items-center px-3 pb-10">
      <div className="text-[10px] uppercase tracking-wider text-zinc-400">{tHC("s3")}</div>
      <motion.div
        initial={{ scale: 0.4, rotate: -12, opacity: 0 }}
        animate={{ scale: 1, rotate: 0, opacity: 1 }}
        transition={{ type: "spring", stiffness: 240, damping: 14 }}
        className="relative mt-3 flex h-16 w-16 items-center justify-center rounded-[1.1rem] bg-gradient-to-br from-violet-500 to-indigo-600 text-2xl font-semibold shadow-lg shadow-violet-500/40"
      >
        {initialOf(name)}
        <motion.span
          initial={{ scale: 0 }}
          animate={{ scale: 1 }}
          transition={{ delay: 0.7 }}
          className="absolute -right-1.5 -top-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-rose-500 text-[10px] font-bold"
        >
          1
        </motion.span>
      </motion.div>
      <div className="mt-1.5 max-w-[150px] truncate text-[11px]">{name}</div>

      <div className="mt-4 flex gap-2">
        {rows.map((r, i) => (
          <motion.div
            key={r.label}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.5 + i * 0.15 }}
            className="flex w-[64px] flex-col items-center gap-1 rounded-xl border border-white/10 bg-white/5 py-2"
          >
            <r.icon className={`h-4 w-4 ${r.tone}`} />
            <span className="text-[9px] text-zinc-300">{r.label}</span>
          </motion.div>
        ))}
      </div>

      <motion.div
        initial={{ opacity: 0, scale: 0.9 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ delay: 1 }}
        className="mt-4 rounded-xl bg-white p-1.5"
      >
        {qr ? (
          <svg
            viewBox={`0 0 ${qr.size + QR_QUIET * 2} ${qr.size + QR_QUIET * 2}`}
            className="h-[104px] w-[104px]"
            role="img"
            aria-label={tHC("s2")}
          >
            <path d={qr.d} fill="#09090b" />
          </svg>
        ) : (
          <div className="flex h-[104px] w-[104px] items-center justify-center text-center text-[9px] leading-snug text-zinc-400">
            {tHC("s1")}
          </div>
        )}
      </motion.div>
      <div className="mt-1.5 text-[9.5px] text-zinc-400">
        {qr ? "Scan - it opens on any phone" : "One tap in Deploy makes it real"}
      </div>
    </div>
  );
}

/** Autoplaying illustration: cycles the scenes on a timer; tabs let a visitor jump. Used in the hero. */
export function PhoneReel({ intervalMs = 5200, className = "" }: { intervalMs?: number; className?: string }) {
  const [i, setI] = useState(0);
  const [paused, setPaused] = useState(false);
  useEffect(() => {
    if (paused) return undefined;
    const id = window.setInterval(() => setI((n) => (n + 1) % SCENE_ORDER.length), intervalMs);
    return () => window.clearInterval(id);
  }, [paused, intervalMs]);
  return (
    <div className={className} onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}>
      <PhoneStage scene={SCENE_ORDER[i]} illustration />
      <div className="mt-5 flex flex-wrap justify-center gap-1.5" role="tablist" aria-label="Scenes">
        {SCENE_ORDER.map((s, n) => (
          <button
            key={s}
            type="button"
            role="tab"
            aria-selected={n === i}
            onClick={() => {
              setI(n);
              setPaused(true);
            }}
            className={`rounded-full px-2.5 py-1 text-[11px] transition-colors ${n === i ? "bg-violet-600 text-white shadow shadow-violet-500/40" : "border border-border/60 text-muted-foreground hover:border-violet-400/60"}`}
          >
            {SCENE_LABEL[s]}
          </button>
        ))}
      </div>
    </div>
  );
}
