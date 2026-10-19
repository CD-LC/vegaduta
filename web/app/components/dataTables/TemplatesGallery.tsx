"use client";

import { useState } from "react";
import {
  Bug,
  CalendarDays,
  Check,
  LayoutTemplate,
  ListChecks,
  Loader2,
  NotebookPen,
  Plus,
  Receipt,
  Users,
  type LucideIcon,
} from "lucide-react";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { TABLE_TEMPLATES, type TableTemplate } from "../../lib/dataTableTemplates";
import { isoToday } from "../../lib/dataTableSnippets";
import type { DataTableSummary, DataTablesConfig } from "../../lib/dataTables";
import type { TemplateId } from "../../lib/dataTableTemplates";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { createTableWithRows } from "./createTable";
import WizardFrame from "./WizardFrame";

/**
 * Starter tables: pick a template, rename it if you like, optionally keep its sample rows, and the table is created
 * with the columns and the sample rows through the ordinary create and bulk-insert endpoints.
 */

const ICONS: Record<TemplateId, LucideIcon> = {
  crm: Users,
  expenses: Receipt,
  content: CalendarDays,
  notebook: NotebookPen,
  ledger: ListChecks,
  bugs: Bug,
};
// A card shows the first few column names; the rest are one "+n more" chip, the full list appears once it is picked.
const CHIP_COLUMNS = 4;

type Props = {
  config: DataTablesConfig;
  onCreated: (table: DataTableSummary) => void;
  onCancel: () => void;
};

export default function TemplatesGallery({ config, onCreated, onCancel }: Props) {
  const t = useTranslations("dataTables");
  const [picked, setPicked] = useState<TableTemplate | null>(null);
  const [name, setName] = useState("");
  const [withSamples, setWithSamples] = useState(true);
  const [busy, setBusy] = useState(false);

  const pick = (tpl: TableTemplate) => {
    setPicked(tpl);
    setName(tpl.name);
  };

  const create = async () => {
    if (!picked || !name.trim() || busy) return;
    setBusy(true);
    try {
      const rows = withSamples ? picked.samples(isoToday()) : [];
      const result = await createTableWithRows({
        name: name.trim(),
        description: t(`tpl_${picked.id}_desc`),
        columns: picked.columns,
        rows,
        batchSize: Math.min(500, config.maxBulkInsert || 500),
      });
      onCreated(result.table);
      toast.success(t("tplCreated", { name: result.table.name }));
    } catch {
      /* the server's reason (duplicate name, table limit, ...) was shown by the API layer */
    } finally {
      setBusy(false);
    }
  };

  const sampleCount = picked ? picked.samples(isoToday()).length : 0;
  const step = busy ? 3 : picked ? 2 : 1;
  const steps = [t("tplStepPick"), t("tplStepName"), t("tplStepCreate")];

  return (
    <WizardFrame
      testId="data-table-templates"
      icon={<LayoutTemplate size={20} aria-hidden="true" />}
      title={t("tplTitle")}
      intro={t("tplHint")}
      stepsLabel={t("tplStepsLabel")}
      steps={steps}
      step={step}
      closeLabel={t("tplClose")}
      onClose={onCancel}
      footer={
        <>
          <p className="text-sm text-muted-foreground" role="status">
            {picked && name.trim()
              ? t("tplFooterReady", {
                  name: name.trim(),
                  columns: picked.columns.length,
                  rows: withSamples ? sampleCount : 0,
                })
              : t("tplFooterIdle")}
          </p>
          <div className="flex gap-2">
            <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
              {t("cancel")}
            </Button>
            <Button type="button" size="sm" disabled={!picked || !name.trim() || busy} onClick={() => void create()}>
              {busy ? (
                <Loader2 size={14} className="animate-spin" aria-hidden="true" />
              ) : (
                <Plus size={14} aria-hidden="true" />
              )}{" "}
              {t("tplCreate")}
            </Button>
          </div>
        </>
      }
    >
      {/* Step 1: pick a starting point. */}
      <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {TABLE_TEMPLATES.map((tpl) => {
          const Icon = ICONS[tpl.id];
          const on = picked?.id === tpl.id;
          const shown = tpl.columns.slice(0, CHIP_COLUMNS);
          const more = tpl.columns.length - shown.length;
          return (
            <li key={tpl.id}>
              <button
                type="button"
                aria-pressed={on}
                onClick={() => pick(tpl)}
                className={`relative flex h-full w-full flex-col items-start gap-2 rounded-lg border p-4 text-left transition-colors focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/40 ${
                  on
                    ? "border-primary bg-primary/5 ring-1 ring-primary"
                    : "border-border bg-card hover:border-primary/40 hover:bg-muted/30"
                }`}
              >
                <span className="flex w-full items-center gap-2.5">
                  <span
                    className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-foreground"
                    aria-hidden="true"
                  >
                    <Icon size={16} aria-hidden="true" />
                  </span>
                  <span className="min-w-0 flex-1 truncate text-sm font-semibold text-foreground">
                    {t(`tpl_${tpl.id}_title`)}
                  </span>
                  {on && (
                    <span
                      className="flex size-5 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground"
                      aria-hidden="true"
                    >
                      <Check size={12} aria-hidden="true" />
                    </span>
                  )}
                </span>
                <span className="line-clamp-3 text-xs leading-relaxed text-muted-foreground">
                  {t(`tpl_${tpl.id}_desc`)}
                </span>
                <span className="mt-auto flex flex-wrap gap-1 pt-1">
                  {shown.map((c) => (
                    <span
                      key={c.name}
                      className="rounded bg-muted px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground"
                    >
                      {c.name}
                    </span>
                  ))}
                  {more > 0 && (
                    <span className="px-1 py-0.5 text-[11px] text-muted-foreground">
                      {t("tplMoreColumns", { count: more })}
                    </span>
                  )}
                </span>
              </button>
            </li>
          );
        })}
      </ul>

      {/* Step 2: name it, choose whether to keep the sample rows, and see every column it will have. */}
      {picked && (
        <section
          aria-labelledby="data-table-template-your"
          className="grid gap-5 border-t border-border pt-5 md:grid-cols-2"
        >
          <div className="flex flex-col gap-4">
            <h4 id="data-table-template-your" className="text-sm font-semibold text-foreground">
              {t("tplYourTable")}
            </h4>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="data-table-template-name">{t("nameLabel")}</Label>
              <Input
                id="data-table-template-name"
                value={name}
                maxLength={100}
                autoComplete="off"
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    void create();
                  }
                }}
              />
            </div>
            {sampleCount > 0 && (
              <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-border bg-muted/20 p-3 text-sm text-foreground hover:bg-muted/40">
                <input
                  type="checkbox"
                  className="mt-0.5"
                  checked={withSamples}
                  onChange={(e) => setWithSamples(e.target.checked)}
                />
                <span>
                  <span className="block font-medium">{t("tplSamples")}</span>
                  <span className="block text-xs text-muted-foreground">
                    {t("tplSamplesHint", { count: sampleCount })}
                  </span>
                </span>
              </label>
            )}
          </div>
          <div className="flex flex-col gap-2">
            <h4 className="text-sm font-semibold text-foreground">
              {t("tplColumnsHeading", { count: picked.columns.length })}
            </h4>
            <ul className="max-h-64 overflow-auto rounded-lg border border-border">
              {picked.columns.map((c, i) => (
                <li
                  key={c.name}
                  className="flex items-center justify-between gap-3 border-b border-border px-3 py-1.5 text-sm last:border-b-0 odd:bg-muted/10"
                >
                  <span className="flex min-w-0 items-center gap-2">
                    <span className="w-5 text-xs tabular-nums text-muted-foreground">{i + 1}</span>
                    <span className="truncate font-mono text-xs text-foreground">{c.name}</span>
                  </span>
                  <span className="shrink-0 text-xs text-muted-foreground">{t(`type_${c.type}`)}</span>
                </li>
              ))}
            </ul>
          </div>
        </section>
      )}
    </WizardFrame>
  );
}
