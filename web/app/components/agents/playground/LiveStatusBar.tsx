"use client";

import { AlertTriangle, Brain, Cog, Hourglass, Loader2, PenLine, RefreshCcw, ShieldQuestion, Wrench } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { cn } from "../../../lib/utils";
import { chipText, type LiveChip, type LiveChipKind } from "./liveStatus";

const ICONS: Record<LiveChipKind, typeof Loader2> = {
  waitingApproval: ShieldQuestion,
  preparing: Hourglass,
  modelCall: Cog,
  thinking: Brain,
  tool: Wrench,
  writing: PenLine,
  continuing: RefreshCcw,
  retrying: RefreshCcw,
  failover: AlertTriangle,
  toolsOff: RefreshCcw,
  failoverBlocked: AlertTriangle,
};

/**
 * The live "what is the agent doing" chips above the transcript: preparing, model call, a tool running, waiting
 * for an approval, continuing, retrying, switched to a backup model. A polite live region, so a screen reader
 * hears each change once; it renders no box at all while there is nothing to say.
 */
export function LiveStatusBar({ chips, className }: { chips: LiveChip[]; className?: string }) {
  const t = useTranslations("agentPlayground");
  return (
    <div role="status" aria-live="polite" data-testid="live-status" className={chips.length ? className : undefined}>
      {chips.length > 0 && (
        <ul
          aria-label={t("chipsLabel")}
          className="m-0 flex list-none flex-wrap items-center gap-1.5 border-b border-border bg-secondary/20 px-5 py-2"
        >
          {chips.map((chip) => {
            const Icon = ICONS[chip.kind];
            const spinning = chip.kind === "preparing" || chip.kind === "modelCall";
            return (
              <li
                key={chip.id}
                data-testid={`chip-${chip.kind}`}
                className={cn(
                  "inline-flex max-w-full items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs",
                  chip.tone === "attention"
                    ? "border-[color-mix(in_srgb,var(--state-warning)_40%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_12%,transparent)] text-foreground"
                    : "border-border bg-card text-foreground"
                )}
              >
                {spinning ? (
                  <Loader2 size={12} className="size-3 shrink-0 animate-spin" aria-hidden="true" />
                ) : (
                  <Icon size={12} className="size-3 shrink-0" aria-hidden="true" />
                )}
                <span className="min-w-0 truncate">{chipText(t, chip)}</span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
