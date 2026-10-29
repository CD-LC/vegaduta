"use client";

// What a visitor sees when Build fails. It answers the four questions a stuck
// person actually has, in order: what happened, why, what do I do, and can I just
// try again. The raw server message is kept behind a fold-out (and a Copy button)
// so nothing is hidden from support, but it is never the headline.

import { useState } from "react";
import Link from "next/link";
import { motion } from "motion/react";
import { ArrowLeft, ArrowUpRight, Check, ClipboardCopy, LifeBuoy, RotateCw, TriangleAlert } from "lucide-react";
import { Button } from "../components/ui/button";
import UpgradePrompt from "../components/UpgradePrompt";
import type { BuildErrorExplanation } from "../lib/build/explainError";
import { useTranslations } from "../lib/i18n";

export default function BuildErrorPanel({
  explanation,
  onRetry,
  onBack,
  keptNote,
}: {
  explanation: BuildErrorExplanation;
  onRetry: () => void;
  onBack: () => void;
  /** Honest note about what was (not) left behind, when the caller knows. */
  keptNote?: string | null;
}) {
    const tHC = useTranslations("hcBuildBuildErrorPanel");
  const [copied, setCopied] = useState(false);
  const [upgradeOpen, setUpgradeOpen] = useState(false);
  const x = explanation;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(`${x.title}\n${x.details}`);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      // Clipboard blocked: the text is still selectable in the fold-out below.
    }
  };

  return (
    <motion.div
      role="alert"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25 }}
      className="overflow-hidden rounded-2xl border border-destructive/30 bg-card shadow-sm"
    >
      <div className="flex items-start gap-3 border-b border-destructive/20 bg-destructive/10 p-4">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-destructive/15 text-destructive">
          <TriangleAlert aria-hidden="true" className="size-5" />
        </span>
        <div className="min-w-0">
          <h3 className="text-base font-semibold text-foreground">{x.title}</h3>
          <p className="mt-0.5 text-sm text-muted-foreground">{x.what}</p>
        </div>
      </div>

      <div className="space-y-4 p-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tHC("hcBuildBuildErrorPanel.s6")}</p>
          <p className="mt-1 text-sm text-foreground">{x.why}</p>
        </div>

        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tHC("hcBuildBuildErrorPanel.s5")}</p>
          <ol className="mt-1.5 space-y-1.5">
            {x.fixes.map((f, i) => (
              <li key={f} className="flex items-start gap-2 text-sm text-foreground">
                <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[11px] font-semibold text-primary">
                  {i + 1}
                </span>
                <span>{f}</span>
              </li>
            ))}
          </ol>
        </div>

        {keptNote && <p className="rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">{keptNote}</p>}

        <div className="flex flex-wrap items-center gap-2">
          {x.upgrade && (
            <Button onClick={() => setUpgradeOpen(true)}>
              <ArrowUpRight aria-hidden="true" className="size-4" /> {tHC("hcBuildBuildErrorPanel.s4")}
            </Button>
          )}
          {x.action && (
            <Button asChild variant={x.upgrade ? "outline" : "default"}>
              <Link href={x.action.href}>
                {x.action.label} <ArrowUpRight aria-hidden="true" className="size-4" />
              </Link>
            </Button>
          )}
          {x.retryable && (
            <Button variant={x.action ? "outline" : "default"} onClick={onRetry}>
              <RotateCw aria-hidden="true" className="size-4" /> {tHC("hcBuildBuildErrorPanel.s3")}
            </Button>
          )}
          <Button variant="ghost" onClick={onBack}>
            <ArrowLeft aria-hidden="true" className="size-4" /> {tHC("hcBuildBuildErrorPanel.s2")}
          </Button>
        </div>

        <details className="group rounded-lg border border-border">
          <summary className="flex cursor-pointer items-center justify-between gap-2 px-3 py-2 text-xs font-medium text-muted-foreground hover:text-foreground">
            <span className="flex items-center gap-1.5">
              <LifeBuoy aria-hidden="true" className="size-3.5" /> {tHC("hcBuildBuildErrorPanel.s1")}
            </span>
          </summary>
          <div className="space-y-2 border-t border-border p-3">
            <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md bg-muted p-2 font-mono text-[11px] text-foreground">
              {x.details}
            </pre>
            <Button size="sm" variant="outline" onClick={() => void copy()}>
              {copied ? <Check aria-hidden="true" className="size-3.5" /> : <ClipboardCopy aria-hidden="true" className="size-3.5" />}
              {copied ? "Copied" : "Copy details"}
            </Button>
          </div>
        </details>
      </div>
      <UpgradePrompt open={upgradeOpen} onClose={() => setUpgradeOpen(false)} reason={x.what} />
    </motion.div>
  );
}
