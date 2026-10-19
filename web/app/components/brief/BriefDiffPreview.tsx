"use client";

import { Loader2, Minus, Pencil, Plus } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { describePatch, type DiffLine } from "./briefDiff";
import type { Brief, JsonPatchOp } from "./types";

// The plain-language preview of a tune: one sentence per change, the planner's
// own explanation above it, and nothing applied until the person says so.
// Names and values are the user's content and are shown as they are; only the
// connecting words come from the brief namespace.

function useSentence() {
  const t = useTranslations("brief");
  return (l: DiffLine): string => {
    const scope = t(`diff.scope.${l.scope}`);
    const where = l.subject ? `${scope} ${l.subject}` : scope;
    if (l.entity) return t(l.op === "removed" ? "diff.entityRemoved" : "diff.entityAdded", { where });
    const field = l.field ? t(`diff.field.${l.field}`) : l.path;
    if (l.op === "added") return t("diff.added", { where, field, item: l.to ?? "" });
    if (l.op === "removed") return t("diff.removed", { where, field, item: l.to ?? l.from ?? "" });
    if (l.from == null || l.from === "") return t("diff.setTo", { where, field, to: l.to ?? "" });
    return t("diff.changed", { where, field, from: l.from, to: l.to ?? "" });
  };
}

const OP_ICON = { changed: Pencil, added: Plus, removed: Minus } as const;

export default function BriefDiffPreview({
  diff,
  before,
  after,
  narrative,
  busy,
  applyLabel,
  error,
  onApply,
  onDiscard,
}: {
  diff: readonly JsonPatchOp[];
  before: Brief;
  after: Brief;
  /** Planner prose for the change, rendered as written. */
  narrative?: string;
  busy?: boolean;
  /** Overrides the apply button label (e.g. while re-scoring). */
  applyLabel?: string;
  error?: string | null;
  onApply: () => void;
  onDiscard: () => void;
}) {
  const t = useTranslations("brief");
  const sentence = useSentence();
  const lines = describePatch(diff, before, after);

  return (
    <div className="space-y-3 rounded-xl border border-border bg-card p-4" aria-live="polite">
      <h4 className="text-sm font-semibold text-foreground">{t("diff.title")}</h4>
      {narrative && <p className="text-sm leading-relaxed text-muted-foreground">{narrative}</p>}

      {lines.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("diff.none")}</p>
      ) : (
        <ul className="space-y-2">
          {lines.map((l) => {
            const Icon = OP_ICON[l.op];
            return (
              <li key={l.id} className="flex items-start gap-2 text-sm text-foreground">
                <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full border border-border" title={t(`diff.op.${l.op}`)}>
                  <Icon className="size-3" aria-hidden="true" />
                  <span className="sr-only">{t(`diff.op.${l.op}`)}</span>
                </span>
                <div className="min-w-0">
                  <p className="break-words">{sentence(l)}</p>
                  {l.full && (
                    <details className="mt-1 text-xs text-muted-foreground">
                      <summary className="cursor-pointer">{t("diff.showFull")}</summary>
                      <p className="mt-1 whitespace-pre-wrap break-words">
                        <span className="font-medium text-foreground">{t("diff.before")}</span> {l.full.from}
                      </p>
                      <p className="mt-1 whitespace-pre-wrap break-words">
                        <span className="font-medium text-foreground">{t("diff.after")}</span> {l.full.to}
                      </p>
                    </details>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <div className="flex flex-wrap gap-2">
        <Button size="sm" onClick={onApply} disabled={busy || lines.length === 0}>
          {busy && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
          {applyLabel ?? t("diff.apply")}
        </Button>
        <Button size="sm" variant="outline" onClick={onDiscard} disabled={busy}>
          {t("diff.discard")}
        </Button>
      </div>
    </div>
  );
}
