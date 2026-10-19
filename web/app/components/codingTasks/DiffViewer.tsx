"use client";

import { useMemo } from "react";
import { classifyDiff, diffStats, type DiffLineKind } from "../../lib/codingTasks";
import { useTranslations } from "../../lib/i18n";

const LINE_CLASS: Record<DiffLineKind, string> = {
  add: "bg-[color-mix(in_srgb,var(--state-success)_14%,transparent)] text-foreground",
  del: "bg-[color-mix(in_srgb,var(--state-error)_14%,transparent)] text-foreground",
  hunk: "bg-secondary/60 text-muted-foreground",
  file: "bg-secondary font-semibold text-foreground",
  meta: "text-muted-foreground",
  ctx: "text-foreground",
};

/**
 * A unified diff, coloured per line. Plain text only: every line is rendered as a React text child, never as HTML,
 * so a diff that contains markup (it is model-written from an untrusted repository) cannot inject anything.
 */
export default function DiffViewer({ diff }: { diff: string }) {
  const t = useTranslations("codingTasks");
  const lines = useMemo(() => classifyDiff(diff), [diff]);
  const stats = useMemo(() => diffStats(diff), [diff]);

  if (lines.length === 0) return <p className="text-sm text-muted-foreground">{t("diffEmpty")}</p>;

  return (
    <div className="flex flex-col gap-2">
      <p className="text-xs text-muted-foreground" data-testid="coding-diff-stats">
        {t("diffStats", { added: stats.added, removed: stats.removed })}
      </p>
      <pre
        className="max-h-[28rem] overflow-auto rounded-md border border-border bg-card p-0 font-mono text-xs leading-5"
        data-testid="coding-diff"
        tabIndex={0}
        aria-label={t("diffAria")}
      >
        {lines.map((line, i) => (
          <div key={i} className={`whitespace-pre px-3 ${LINE_CLASS[line.kind]}`}>
            {line.text || " "}
          </div>
        ))}
      </pre>
    </div>
  );
}
