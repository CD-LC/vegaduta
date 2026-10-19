"use client";

// Unified-diff viewer + the ONE explicit write affordance in this feature.
//
// Applying is deliberately a separate, second click from proposing: the
// proposal costs nothing (it lives in memory), the apply overwrites a file in
// a folder the person handed us. Browsers grant read and write separately —
// picking a folder does not imply consent to modify it — so the apply path
// calls ensureWritePermission() through the panel and reports a refused grant
// as an ordinary, recoverable state rather than an error.

import { Check, FileDiff, ShieldAlert, X } from "lucide-react";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import type { ProposedEdit } from "../../lib/localcode/edit";

/** Outcome of the last apply attempt, rendered inline under the diff.
 * `denied` is split out from `failed` because a refused write grant is not a
 * bug — it is the person saying no, and it gets calm copy plus a retry. */
export type ApplyOutcome =
  { kind: "none" } | { kind: "applied"; path: string } | { kind: "denied" } | { kind: "failed"; reason: string };

type LineKind = "add" | "remove" | "hunk" | "meta" | "context";

/** Classify a unified-diff line for colouring. The `+++`/`---` file headers
 * are checked BEFORE the single-character +/- cases — otherwise every diff
 * opens with a bogus "added"/"removed" line pair. */
function lineKind(line: string): LineKind {
  if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ")) {
    return "meta";
  }
  if (line.startsWith("@@")) return "hunk";
  if (line.startsWith("+")) return "add";
  if (line.startsWith("-")) return "remove";
  return "context";
}

export default function LocalCodeDiffView({
  edit,
  outcome,
  applying,
  onApply,
  onDiscard,
}: {
  edit: ProposedEdit;
  outcome: ApplyOutcome;
  applying: boolean;
  onApply: () => void;
  onDiscard: () => void;
}) {
  const t = useTranslations("localCode");
  const lines = edit.diff.split("\n");
  const added = lines.filter((l) => lineKind(l) === "add").length;
  const removed = lines.filter((l) => lineKind(l) === "remove").length;
  const applied = outcome.kind === "applied";

  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-2.5">
        <div className="inline-flex min-w-0 items-center gap-2 text-sm font-medium text-foreground">
          <FileDiff size={14} className="shrink-0 text-muted-foreground" aria-hidden="true" />
          <span className="truncate font-mono text-xs">{edit.path}</span>
        </div>
        <div className="flex items-center gap-3 text-[11px] text-muted-foreground">
          <span>{t("edit.diffStat", { added: String(added), removed: String(removed) })}</span>
        </div>
      </div>

      <div className="max-h-96 overflow-auto">
        <pre className="min-w-full font-mono text-[11px] leading-relaxed">
          {lines.map((line, i) => {
            const kind = lineKind(line);
            return (
              <div
                key={i}
                className={cn(
                  "px-4 py-[1px] whitespace-pre",
                  kind === "add" && "bg-primary/10 text-foreground",
                  kind === "remove" && "bg-destructive/10 text-muted-foreground line-through decoration-1",
                  kind === "hunk" && "bg-secondary/60 text-muted-foreground",
                  kind === "meta" && "text-muted-foreground",
                  kind === "context" && "text-muted-foreground"
                )}
              >
                {line.length ? line : " "}
              </div>
            );
          })}
        </pre>
      </div>

      <div className="flex flex-wrap items-center gap-2 border-t border-border px-4 py-3">
        {applied ? (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-2.5 py-1 text-[11px] font-medium text-primary">
            <Check size={11} aria-hidden="true" />
            {t("edit.applied", { path: outcome.path })}
          </span>
        ) : (
          <>
            <Button size="sm" onClick={onApply} disabled={applying}>
              <Check size={13} aria-hidden="true" />
              {applying ? t("edit.applying") : t("edit.apply")}
            </Button>
            <Button size="sm" variant="ghost" onClick={onDiscard} disabled={applying}>
              <X size={13} aria-hidden="true" />
              {t("edit.discard")}
            </Button>
            <span className="text-[11px] text-muted-foreground">{t("edit.applyHint")}</span>
          </>
        )}
      </div>

      {outcome.kind === "denied" && (
        <p className="flex items-start gap-2 border-t border-border px-4 py-2.5 text-xs text-muted-foreground">
          <ShieldAlert size={13} className="mt-0.5 shrink-0" aria-hidden="true" />
          {t("edit.permissionDenied")}
        </p>
      )}
      {outcome.kind === "failed" && (
        <p className="border-t border-border px-4 py-2.5 text-xs text-destructive">
          {t("edit.failed", { reason: outcome.reason })}
        </p>
      )}
    </div>
  );
}
