"use client";

import { Clapperboard, Download, Eye, Loader2 } from "lucide-react";
import { Button } from "../components/ui/button";
import { Checkbox } from "../components/ui/checkbox";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "../components/ui/table";
import { useTranslations } from "../lib/i18n";
import { previewKind, rowKey } from "./filesLogic";
import { resolveRowIcon, rowTypeLabel, type FileRow } from "./fileRows";

interface FilesTableProps {
  rows: FileRow[];
  selected: Set<string>;
  onSelectedChange: (updater: (prev: Set<string>) => Set<string>) => void;
  downloadingKey: string | null;
  previewBusyKey: string | null;
  onDownload: (row: FileRow) => void;
  onPreview: (row: FileRow) => void;
}

/** One page of the merged files list. Selection, download and preview state live in the page. */
export default function FilesTable({
  rows,
  selected,
  onSelectedChange,
  downloadingKey,
  previewBusyKey,
  onDownload,
  onPreview,
}: FilesTableProps) {
  const t = useTranslations("filesPage");
  const allSelected = rows.length > 0 && rows.every((x) => selected.has(rowKey(x)));
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="w-8">
            <Checkbox
              aria-label={t("selectAll")}
              checked={allSelected}
              onCheckedChange={(on) =>
                onSelectedChange((prev) => {
                  const next = new Set(prev);
                  rows.forEach((x) => (on ? next.add(rowKey(x)) : next.delete(rowKey(x))));
                  return next;
                })
              }
            />
          </TableHead>
          <TableHead className="w-8">
            <span className="sr-only">{t("colType")}</span>
          </TableHead>
          <TableHead>{t("colName")}</TableHead>
          <TableHead>{t("colDetails")}</TableHead>
          <TableHead>{t("colDate")}</TableHead>
          <TableHead className="text-right">{t("colActions")}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => {
          const { Icon, colorClassName } = resolveRowIcon(row);
          const key = rowKey(row);
          return (
            <TableRow key={key}>
              <TableCell className="w-8">
                <Checkbox
                  aria-label={t("selectRow", { name: row.name })}
                  checked={selected.has(key)}
                  onCheckedChange={(on) =>
                    onSelectedChange((prev) => {
                      const next = new Set(prev);
                      if (on) next.add(key);
                      else next.delete(key);
                      return next;
                    })
                  }
                />
              </TableCell>
              <TableCell className="w-8">
                <Icon size={15} className={colorClassName} aria-hidden="true" />
                <span className="sr-only">{rowTypeLabel(row, t)}</span>
              </TableCell>
              <TableCell className="min-w-0">
                <div className="truncate text-sm font-medium text-foreground" title={row.name}>
                  {row.name}
                </div>
                {row.collectionName && <div className="truncate text-xs text-muted-foreground">{row.collectionName}</div>}
              </TableCell>
              <TableCell className="whitespace-nowrap text-xs text-muted-foreground">{row.detail || "—"}</TableCell>
              <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
                {row.createdAt ? new Date(row.createdAt).toLocaleDateString() : "—"}
              </TableCell>
              <TableCell className="text-right">
                {/* Clip Studio entry point: images can be animated into a short clip. */}
                {row.kind === "image" && (
                  <Button variant="ghost" size="sm" className="mr-1 gap-1" asChild>
                    <a href={`/clips?imageId=${encodeURIComponent(row.id)}`}>
                      <Clapperboard size={12} aria-hidden="true" />
                      {t("makeClip")}
                    </a>
                  </Button>
                )}
                {row.downloadable && previewKind(row.kind) && (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="mr-1 gap-1"
                    disabled={previewBusyKey === key}
                    onClick={() => onPreview(row)}
                  >
                    {previewBusyKey === key ? (
                      <Loader2 size={12} className="animate-spin" aria-hidden="true" />
                    ) : (
                      <Eye size={12} aria-hidden="true" />
                    )}
                    {t("preview")}
                  </Button>
                )}
                {row.downloadable ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="gap-1"
                    disabled={downloadingKey === key}
                    onClick={() => onDownload(row)}
                  >
                    {downloadingKey === key ? (
                      <Loader2 size={12} className="animate-spin" aria-hidden="true" />
                    ) : (
                      <Download size={12} aria-hidden="true" />
                    )}
                    {t("download")}
                  </Button>
                ) : (
                  <span className="text-xs text-muted-foreground">
                    {row.kind === "knowledge-document" ? t("noOriginal") : t("notReady")}
                  </span>
                )}
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
