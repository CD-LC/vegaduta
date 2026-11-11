"use client";

import { useMemo } from "react";
import { diffLines, diffStats } from "../../lib/skillLibrary";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";

const MARK: Record<string, string> = { same: " ", add: "+", del: "-", change: "~" };

/**
 * Side-by-side comparison of the saved skill text and the edited draft. Every
 * changed row carries a +/-/~ marker and a screen-reader label, so the diff
 * never depends on colour alone. Text is rendered as text nodes, never markup.
 */
export default function SkillDiffView({ before, after }: { before: string; after: string }) {
  const t = useTranslations("skillLibrary");
  const rows = useMemo(() => diffLines(before, after), [before, after]);
  const stats = useMemo(() => diffStats(rows), [rows]);
  const identical = stats.added + stats.removed + stats.changed === 0;

  if (identical) {
    return (
      <p className="text-sm text-muted-foreground" data-testid="skill-diff-empty">
        {t("diffNoChanges")}
      </p>
    );
  }

  const cell = (kind: string, side: "left" | "right", text: string | null) => {
    const tone =
      text === null
        ? "bg-muted/30"
        : kind === "change"
          ? "bg-amber-500/10"
          : kind === "add"
            ? "bg-emerald-500/10"
            : kind === "del"
              ? "bg-red-500/10"
              : "";
    const sr =
      text === null
        ? ""
        : kind === "same"
          ? ""
          : kind === "change"
            ? t("diffChanged")
            : side === "left"
              ? t("diffRemoved")
              : t("diffAdded");
    return (
      <div className={cn("flex min-w-0 gap-2 px-2 py-0.5", tone)}>
        <span aria-hidden="true" className="w-3 shrink-0 select-none text-muted-foreground">
          {text === null ? "" : MARK[kind]}
        </span>
        {sr && <span className="sr-only">{sr}: </span>}
        <span className="min-w-0 whitespace-pre-wrap break-words">{text ?? ""}</span>
      </div>
    );
  };

  return (
    <div data-testid="skill-diff">
      <p className="mb-2 text-xs text-muted-foreground" role="status">
        {t("diffSummary", { added: stats.added, removed: stats.removed, changed: stats.changed })}
      </p>
      <div className="overflow-x-auto rounded-md border border-border">
        <div
          role="table"
          aria-label={t("diffTableLabel")}
          className="grid min-w-[32rem] grid-cols-2 font-mono text-xs leading-relaxed"
        >
          <div role="row" className="contents">
            <div role="columnheader" className="border-b border-border bg-muted px-2 py-1 font-sans font-medium">
              {t("diffSaved")}
            </div>
            <div
              role="columnheader"
              className="border-b border-s border-border bg-muted px-2 py-1 font-sans font-medium"
            >
              {t("diffDraft")}
            </div>
          </div>
          {rows.map((r, i) => (
            <div role="row" className="contents" key={i} data-diff-kind={r.kind}>
              <div role="cell" className="contents">
                {cell(r.kind, "left", r.left)}
              </div>
              <div role="cell" className="contents">
                <div className="min-w-0 border-l border-border">{cell(r.kind, "right", r.right)}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
