"use client";

import { useId, useMemo, useState } from "react";
import { FileSpreadsheet, Loader2, AlertTriangle, Upload, UploadCloud } from "lucide-react";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { buildImportPlan, mapRows, type ImportColumn, type ImportPlan } from "../../lib/dataTableCsv";
import {
  DATA_TABLE_COLUMN_TYPES,
  isValidColumnName,
  type DataTableColumnType,
  type DataTableSummary,
  type DataTablesConfig,
} from "../../lib/dataTables";
import Select from "../Select";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { createTableWithRows } from "./createTable";
import WizardFrame from "./WizardFrame";

/**
 * "Import as a new table" with a preview: the file is read and typed in the browser (same naming and type-inference
 * rules as core's own CSV import), the person can rename a column or change its type, and only then is the table
 * created (POST /api/data-tables with the columns) and filled (POST .../rows/bulk). A cell that does not fit its
 * column's type leaves its row out and says which; nothing is sent for it.
 */

type Props = {
  config: DataTablesConfig;
  onCreated: (table: DataTableSummary) => void;
  onCancel: () => void;
};

const PREVIEW_ROWS = 5;
const BATCH = 500;

function formatSize(bytes: number): string {
  return bytes >= 1024 * 1024
    ? `${(bytes / (1024 * 1024)).toFixed(1)} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

export default function ImportWizard({ config, onCreated, onCancel }: Props) {
  const t = useTranslations("dataTables");
  const [plan, setPlan] = useState<ImportPlan | null>(null);
  const [columns, setColumns] = useState<ImportColumn[]>([]);
  const [fileError, setFileError] = useState<string | null>(null);
  const [fileName, setFileName] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);
  const fileInputId = useId();

  const rowLimit = Math.min(config.maxImportRows || Infinity, config.maxRowsPerTable || Infinity);
  const typeOptions = DATA_TABLE_COLUMN_TYPES.map((v) => ({ value: v, label: t(`type_${v}`) }));

  const readFile = async (file: File | null) => {
    setPlan(null);
    setColumns([]);
    setFileError(null);
    if (!file) return;
    if (config.maxImportBytes > 0 && file.size > config.maxImportBytes) {
      setFileError(t("impTooBig", { size: formatSize(file.size), max: formatSize(config.maxImportBytes) }));
      return;
    }
    try {
      const built = buildImportPlan(await file.text());
      if (built.columns.length === 0) {
        setFileError(t("impEmptyFile"));
        return;
      }
      if (config.maxColumns > 0 && built.columns.length > config.maxColumns) {
        setFileError(t("impTooManyColumns", { count: built.columns.length, max: config.maxColumns }));
        return;
      }
      if (built.rows.length > rowLimit) {
        setFileError(t("impTooManyRows", { rows: built.rows.length, max: rowLimit }));
        return;
      }
      setPlan(built);
      setColumns(built.columns);
      setFileName(file.name);
      setName((n) => n || file.name.replace(/\.[^.]+$/, "").slice(0, 100));
    } catch {
      setFileError(t("impReadFailed"));
    }
  };

  const update = (index: number, patch: Partial<ImportColumn>) =>
    setColumns((cols) => cols.map((c, i) => (i === index ? { ...c, ...patch } : c)));

  const mapped = useMemo(() => (plan ? mapRows(columns, plan.rows) : null), [plan, columns]);

  const lowerNames = columns.map((c) => c.name.trim().toLowerCase());
  const clash = columns.find((c, i) => lowerNames.indexOf(c.name.trim().toLowerCase()) !== i);
  const badName = columns.some((c) => !isValidColumnName(c.name));
  const canCreate = !!plan && !!mapped && name.trim() !== "" && !badName && !clash && !busy;

  const create = async () => {
    if (!plan || !mapped || !canCreate) return;
    setBusy(true);
    try {
      const result = await createTableWithRows({
        name: name.trim(),
        columns: columns.map((c) => ({ name: c.name.trim(), type: c.type })),
        rows: mapped.rows,
        batchSize: Math.min(BATCH, config.maxBulkInsert || BATCH),
      });
      onCreated(result.table);
      if (result.incomplete) toast.error(t("impIncomplete", { inserted: result.inserted, total: mapped.rows.length }));
      else toast.success(t("impCreated", { name: result.table.name, inserted: result.inserted }));
    } catch {
      /* the server's reason (duplicate name, table limit, ...) was shown by the API layer */
    } finally {
      setBusy(false);
    }
  };

  const preview = plan ? plan.rows.slice(0, PREVIEW_ROWS) : [];
  const step = busy ? 3 : plan ? 2 : 1;
  const steps = [t("impStepFile"), t("impStepColumns"), t("impStepImport")];
  const dropText =
    config.maxImportBytes > 0 ? t("impDropHint", { max: formatSize(config.maxImportBytes) }) : t("impDropHintNoMax");

  return (
    <WizardFrame
      testId="data-table-import-wizard"
      icon={<FileSpreadsheet size={20} aria-hidden="true" />}
      title={t("importNewTitle")}
      intro={t("impIntro")}
      stepsLabel={t("impStepsLabel")}
      steps={steps}
      step={step}
      closeLabel={t("impClose")}
      onClose={onCancel}
      footer={
        <>
          <p className="text-sm text-muted-foreground" role="status">
            {plan && mapped
              ? t("impSummary", { rows: mapped.rows.length, skipped: mapped.skipped })
              : t("impFooterIdle")}
          </p>
          <div className="flex gap-2">
            <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
              {t("cancel")}
            </Button>
            <Button type="button" size="sm" disabled={!canCreate} onClick={() => void create()}>
              {busy ? (
                <Loader2 size={14} className="animate-spin" aria-hidden="true" />
              ) : (
                <Upload size={14} aria-hidden="true" />
              )}{" "}
              {t("impCreate")}
            </Button>
          </div>
        </>
      }
    >
      {/* Step 1: the file. A drop zone until a file is read, then a compact summary with a way to swap it. */}
      <div>
        <label
          htmlFor={fileInputId}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            void readFile(e.dataTransfer.files?.[0] ?? null);
          }}
          className={`flex cursor-pointer items-center gap-3 rounded-lg border-2 border-dashed transition-colors focus-within:ring-[3px] focus-within:ring-ring/40 ${
            plan ? "px-4 py-3" : "flex-col justify-center px-4 py-8 text-center"
          } ${dragging ? "border-primary bg-primary/5" : "border-border bg-muted/20 hover:border-primary/50 hover:bg-muted/40"}`}
        >
          <input
            id={fileInputId}
            type="file"
            className="sr-only"
            accept=".csv,.tsv,.txt,text/csv"
            aria-label={t("chooseFile")}
            onChange={(e) => {
              void readFile(e.target.files?.[0] ?? null);
              // Same file picked again after a fix must still fire onChange.
              e.target.value = "";
            }}
          />
          {plan ? (
            <>
              <FileSpreadsheet size={20} className="shrink-0 text-primary" aria-hidden="true" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-foreground">{fileName}</p>
                <p className="text-xs text-muted-foreground">
                  {t("impDetected", { rows: plan.rows.length, columns: columns.length })}
                </p>
              </div>
              <span className="shrink-0 text-xs font-medium text-primary">{t("impChangeFile")}</span>
            </>
          ) : (
            <>
              <UploadCloud size={28} className="text-muted-foreground" aria-hidden="true" />
              <div>
                <p className="text-sm font-medium text-foreground">{t("impDropTitle")}</p>
                <p className="text-xs text-muted-foreground">{dropText}</p>
              </div>
            </>
          )}
        </label>
        {fileError && (
          <p
            className="mt-2 flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive"
            role="alert"
          >
            <AlertTriangle size={14} className="mt-0.5 shrink-0" aria-hidden="true" /> {fileError}
          </p>
        )}
      </div>

      {plan && mapped && (
        <>
          {/* Step 2a: what the table is called. */}
          <div className="flex max-w-md flex-col gap-1.5">
            <Label htmlFor="data-table-wizard-name">{t("nameLabel")}</Label>
            <Input
              id="data-table-wizard-name"
              value={name}
              placeholder={fileName.replace(/\.[^.]+$/, "")}
              maxLength={100}
              autoComplete="off"
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          {/* Step 2b: the columns, one compact line each, scrolling inside the card so the footer never leaves the screen. */}
          <section aria-labelledby={`${fileInputId}-cols`}>
            <div className="mb-2 flex flex-wrap items-baseline justify-between gap-x-3">
              <h4 id={`${fileInputId}-cols`} className="text-sm font-semibold text-foreground">
                {t("impColumnsHeading")}
              </h4>
              <p className="text-xs text-muted-foreground">{t("impColumnsHint")}</p>
            </div>
            <div className="max-h-80 overflow-auto rounded-lg border border-border">
              <div role="table" aria-label={t("impColumnsHeading")} className="min-w-[30rem]">
                <div
                  role="row"
                  className="sticky top-0 z-10 grid grid-cols-[2rem_minmax(0,1.3fr)_9rem_minmax(0,1fr)] items-center gap-3 border-b border-border bg-muted px-3 py-2 text-xs font-medium text-muted-foreground"
                >
                  <span role="columnheader">#</span>
                  <span role="columnheader">{t("columnNameLabel")}</span>
                  <span role="columnheader">{t("columnTypeLabel")}</span>
                  <span role="columnheader">{t("impColHeadSample")}</span>
                </div>
                {columns.map((c, index) => {
                  const invalid =
                    !isValidColumnName(c.name) || lowerNames.indexOf(c.name.trim().toLowerCase()) !== index;
                  return (
                    <div
                      key={index}
                      role="row"
                      data-testid="data-table-wizard-column"
                      className="grid grid-cols-[2rem_minmax(0,1.3fr)_9rem_minmax(0,1fr)] items-center gap-3 border-b border-border px-3 py-1.5 last:border-b-0 odd:bg-muted/10"
                    >
                      <span role="cell" className="text-xs tabular-nums text-muted-foreground">
                        {index + 1}
                      </span>
                      <span role="cell">
                        <Input
                          value={c.name}
                          aria-label={t("columnNameLabel")}
                          aria-invalid={invalid || undefined}
                          autoComplete="off"
                          className={invalid ? "border-destructive" : undefined}
                          onChange={(e) => update(index, { name: e.target.value })}
                        />
                      </span>
                      <span role="cell">
                        <Select
                          value={c.type}
                          onChange={(e: any) => update(index, { type: e.target.value as DataTableColumnType })}
                          options={typeOptions}
                          aria-label={t("columnTypeLabel")}
                        />
                      </span>
                      <span role="cell" className="truncate text-xs text-muted-foreground" title={c.sample.join(" | ")}>
                        {c.sample.length > 0 ? c.sample.join(" | ") : "-"}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
            {badName && (
              <p className="mt-2 text-xs text-destructive" role="alert">
                {t("columnNameInvalid")}
              </p>
            )}
            {clash && (
              <p className="mt-2 text-xs text-destructive" role="alert">
                {t("impNameClash", { name: clash.name.trim() })}
              </p>
            )}
          </section>

          {/* Step 2c: a look at the first rows as they will be stored. */}
          {preview.length > 0 && (
            <section>
              <h4 className="mb-2 text-sm font-semibold text-foreground">
                {t("impPreviewHeading", { count: preview.length })}
              </h4>
              <div className="overflow-x-auto rounded-lg border border-border">
                <table className="w-full text-start text-xs">
                  <thead className="bg-muted">
                    <tr>
                      {columns.map((c, i) => (
                        <th key={i} className="whitespace-nowrap px-3 py-2 font-medium text-muted-foreground">
                          {c.name}
                          <span className="ms-1.5 font-normal">{t(`type_${c.type}`)}</span>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {preview.map((r, ri) => (
                      <tr key={ri} className="border-t border-border odd:bg-muted/10">
                        {columns.map((_c, ci) => (
                          <td key={ci} className="max-w-[12rem] truncate whitespace-nowrap px-3 py-1.5">
                            {r[ci] ?? ""}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          {mapped.problems.length > 0 && (
            <div className="rounded-lg border border-border bg-muted/20 p-3">
              <p className="mb-1 flex items-center gap-2 text-xs font-medium text-foreground">
                <AlertTriangle size={14} aria-hidden="true" /> {t("impProblemsHeading", { count: mapped.skipped })}
              </p>
              <ul className="list-disc ps-5 text-xs text-muted-foreground">
                {mapped.problems.slice(0, 5).map((p) => (
                  <li key={`${p.row}-${p.column}`}>
                    {t("impProblem", { row: p.row, column: p.column, message: t(`cellError_${p.error}`) })}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </WizardFrame>
  );
}
