"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "motion/react";
import { Bot, Check, FileText, MessageSquare, ShieldCheck, Send } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

// Same request, two very different mornings. An illustration (and labelled as
// one): the point is the SHAPE of the difference - one answers, the other
// works - and every step the agent side shows is a step the studio below
// really performs (read documents, run code, pause for approval, message).

const REQUEST = "Which customers haven't paid me in over two weeks - and can you chase them?";

const AGENT_STEPS = [
  { icon: FileText, text: "Read invoices.txt from your documents", tone: "text-sky-600 dark:text-sky-300" },
  {
    icon: Bot,
    text: "Ran code to check which are 15+ days overdue: 4 found",
    tone: "text-emerald-600 dark:text-emerald-300",
  },
  {
    icon: MessageSquare,
    text: "Drafted a polite reminder for each, in their language",
    tone: "text-violet-600 dark:text-violet-300",
  },
  {
    icon: ShieldCheck,
    text: "Stopped. Waiting for your OK before anything is sent.",
    tone: "text-amber-600 dark:text-amber-300",
  },
  {
    icon: Send,
    text: "On approval: sent, and logged who approved what.",
    tone: "text-emerald-600 dark:text-emerald-300",
  },
];

export default function ChatbotVsAgent() {
    const tHC = useTranslations("hcComponentsStudioChatbotVsAgent");
  const [mode, setMode] = useState<"bot" | "agent">("agent");
  return (
    <div className="mt-16 rounded-3xl border border-border/60 bg-background/60 p-5 backdrop-blur sm:p-7">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {tHC("hcComponentsStudioChatbotVsAgent.s7")}
          </div>
          <div className="mt-1 max-w-2xl text-lg font-medium">&ldquo;{REQUEST}&rdquo;</div>
        </div>
        <div
          className="inline-flex rounded-full border border-border/60 p-1 text-sm"
          role="tablist"
          aria-label={tHC("hcComponentsStudioChatbotVsAgent.s6")}
        >
          {(["bot", "agent"] as const).map((m) => (
            <button
              key={m}
              type="button"
              role="tab"
              aria-selected={mode === m}
              onClick={() => setMode(m)}
              className={`rounded-full px-4 py-1.5 transition-colors ${mode === m ? (m === "agent" ? "bg-violet-600 text-white shadow shadow-violet-500/40" : "bg-muted text-foreground") : "text-muted-foreground hover:text-foreground"}`}
            >
              {m === "bot" ? "A chatbot" : "An agent"}
            </button>
          ))}
        </div>
      </div>

      <div className="mt-5 min-h-[248px]">
        <AnimatePresence mode="wait">
          {mode === "bot" ? (
            <motion.div
              key="bot"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              className="max-w-2xl rounded-2xl rounded-ss-sm bg-muted/60 p-4 text-sm leading-relaxed text-muted-foreground"
            >
              {tHC("hcComponentsStudioChatbotVsAgent.s5")}{" "}
              <i>{tHC("hcComponentsStudioChatbotVsAgent.s4")}</i> {tHC("hcComponentsStudioChatbotVsAgent.s3")}
              <div className="mt-3 text-xs">{tHC("hcComponentsStudioChatbotVsAgent.s2")}</div>
            </motion.div>
          ) : (
            <motion.ol
              key="agent"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="space-y-2.5"
            >
              {AGENT_STEPS.map((s, i) => (
                <motion.li
                  key={s.text}
                  initial={{ opacity: 0, x: -14 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: 0.15 + i * 0.28 }}
                  className="flex max-w-2xl items-center gap-3 rounded-xl border border-border/60 bg-background/70 px-3.5 py-2.5 text-sm"
                >
                  <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-muted">
                    <s.icon className={`h-3.5 w-3.5 ${s.tone}`} />
                  </span>
                  <span className="flex-1">{s.text}</span>
                  <Check aria-hidden="true" className="h-4 w-4 text-emerald-600 dark:text-emerald-400 shrink-0" />
                </motion.li>
              ))}
              <li className="max-w-2xl pt-1 text-xs text-muted-foreground">
                {tHC("hcComponentsStudioChatbotVsAgent.s1")}
              </li>
            </motion.ol>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}
