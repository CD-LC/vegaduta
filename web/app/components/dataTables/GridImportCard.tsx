"use client";

import { useState } from "react";
import { Loader2, Upload } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import type { DataTableImportResult, DataTableSummary } from "../../lib/dataTables";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Input } from "../ui/input";

/**
 * CSV import into an existing table. Stays mounted while hidden (`open`
 * false renders nothing) so a picked file and the last result survive
 * toggling the panel, as they did when this state lived in the grid.
 */
export default function GridImportCard({
  open,
  tableId,
  onImported,
}: {
  open: boolean;
  tableId: string;
  /** Rows landed: the grid refreshes its summary and reloads. */
  onImported: (table: DataTableSummary) => void;
}) {
  const t = useTranslations("dataTables");
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<DataTableImportResult | null>(null);

  const runImport = async () => {
    if (!importFile) return;
    setImporting(true);
    setImportResult(null);
    try {
      const result = await api.importDataTableCsv(tableId, importFile);
      setImportResult(result);
      setImportFile(null);
      toast.success(t("importDone", { inserted: result.inserted, skipped: result.skipped }));
      onImported(result.table);
    } catch {
      /* the server's reason was already shown by the API layer's toast */
    } finally {
      setImporting(false);
    }
  };

  if (!open) return null;
  return (
    <Card>
      <CardContent className="flex flex-col gap-3 pt-4">
        <p className="text-sm text-muted-foreground">{t("importExistingHint")}</p>
        <div className="flex flex-wrap items-center gap-2">
          <Input
            type="file"
            accept=".csv,.txt,text/csv"
            className="max-w-sm"
            aria-label={t("chooseFile")}
            onChange={(e) => {
              setImportFile(e.target.files?.[0] ?? null);
              setImportResult(null);
            }}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={importing || !importFile}
            onClick={() => void runImport()}
          >
            {importing ? (
              <Loader2 size={14} className="animate-spin" aria-hidden="true" />
            ) : (
              <Upload size={14} aria-hidden="true" />
            )}{" "}
            {t("importRun")}
          </Button>
        </div>
        {importResult && (
          <div className="text-sm" role="status">
            <p>{t("importDone", { inserted: importResult.inserted, skipped: importResult.skipped })}</p>
            {importResult.ignoredHeaders.length > 0 && (
              <p className="text-xs text-muted-foreground">
                {t("importIgnored", { names: importResult.ignoredHeaders.join(", ") })}
              </p>
            )}
            {importResult.errors.length > 0 && (
              <ul className="mt-1 list-disc pl-5 text-xs text-muted-foreground">
                {importResult.errors.slice(0, 10).map((msg) => (
                  <li key={msg}>{msg}</li>
                ))}
              </ul>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
