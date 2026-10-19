"use client";

import { useMemo, useState } from "react";
import { Check, Copy } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { QUERY_TOOL, WRITE_TOOL, buildSnippets, isoToday } from "../../lib/dataTableSnippets";
import type { DataTableSummary } from "../../lib/dataTables";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";

/**
 * "Use this table": ready-to-paste settings for a workflow Data Table step and ready-to-use arguments for the
 * data_table_query / data_table_write agent tools, written with this table's real name and columns.
 */
export default function UseTablePanel({ table }: { table: DataTableSummary }) {
  const t = useTranslations("dataTables");
  const snippets = useMemo(() => buildSnippets(table, isoToday()), [table]);
  const [copied, setCopied] = useState<string | null>(null);

  const copy = async (id: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(id);
      setTimeout(() => setCopied((c) => (c === id ? null : c)), 1500);
    } catch {
      /* clipboard blocked: the text is selectable on screen */
    }
  };

  if (snippets.length === 0) {
    return (
      <Card data-testid="data-table-use">
        <CardContent className="pt-4 text-sm text-muted-foreground">{t("useNoColumns")}</CardContent>
      </Card>
    );
  }

  return (
    <Card data-testid="data-table-use">
      <CardContent className="flex flex-col gap-4 pt-4">
        <div>
          <h3 className="text-sm font-semibold text-foreground">{t("useHeading")}</h3>
          <p className="text-xs text-muted-foreground">{t("useIntro", { name: table.name })}</p>
        </div>
        {(["workflow", "agent"] as const).map((surface) => (
          <section key={surface} className="flex flex-col gap-2">
            <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {t(surface === "workflow" ? "useWorkflowHeading" : "useAgentHeading")}
            </h4>
            <p className="text-xs text-muted-foreground">
              {surface === "workflow"
                ? t("useWorkflowHint")
                : t("useAgentHint", { query: QUERY_TOOL, write: WRITE_TOOL })}
            </p>
            {snippets
              .filter((s) => s.surface === surface)
              .map((s) => (
                <div key={s.id} className="rounded-md border border-border">
                  <div className="flex items-center justify-between gap-2 border-b border-border px-2 py-1">
                    <span className="text-xs font-medium text-foreground">{t(`useSnippet_${s.id}`)}</span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => void copy(s.id, s.text)}
                      aria-label={t("useCopy")}
                    >
                      {copied === s.id ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}{" "}
                      {copied === s.id ? t("useCopied") : t("useCopy")}
                    </Button>
                  </div>
                  <pre className="max-h-56 overflow-auto p-2 text-xs text-foreground">
                    <code>{s.text}</code>
                  </pre>
                </div>
              ))}
          </section>
        ))}
      </CardContent>
    </Card>
  );
}
