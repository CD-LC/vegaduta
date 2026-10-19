"use client";

import { useState } from "react";
import { Check, Pencil, X } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import type { DataTableSummary } from "../../lib/dataTables";
import { Button } from "../ui/button";
import { Input } from "../ui/input";

/** The table's name/description (with inline rename) and its row usage against the cap. */
export default function GridTitle({
  table,
  readOnly,
  percent,
  maxRows,
  onChanged,
}: {
  table: DataTableSummary;
  readOnly: boolean;
  percent: number;
  maxRows: number;
  onChanged: (table: DataTableSummary) => void;
}) {
  const t = useTranslations("dataTables");
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState(table.name);
  const [descDraft, setDescDraft] = useState(table.description ?? "");

  const saveRename = async () => {
    const name = nameDraft.trim();
    if (!name) return;
    try {
      onChanged(await api.updateDataTable(table.id, { name, description: descDraft }));
      setRenaming(false);
    } catch {
      /* the server's reason (duplicate name, ...) was shown by the API layer */
    }
  };

  return (
    <div className="min-w-0 flex-1">
      {renaming ? (
        <div className="flex flex-wrap items-center gap-2">
          <Input
            className="max-w-xs"
            value={nameDraft}
            onChange={(e) => setNameDraft(e.target.value)}
            aria-label={t("nameLabel")}
          />
          <Input
            className="max-w-sm"
            value={descDraft}
            onChange={(e) => setDescDraft(e.target.value)}
            placeholder={t("descriptionLabel")}
            aria-label={t("descriptionLabel")}
          />
          <Button type="button" size="sm" disabled={!nameDraft.trim()} onClick={() => void saveRename()}>
            <Check size={14} aria-hidden="true" /> {t("save")}
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={() => setRenaming(false)}>
            <X size={14} aria-hidden="true" /> {t("cancel")}
          </Button>
        </div>
      ) : (
        <>
          <h2 className="flex items-center gap-1 truncate text-lg font-semibold text-foreground">
            <span className="truncate">{table.name}</span>
            {!readOnly && (
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="size-7"
                aria-label={t("rename")}
                title={t("rename")}
                onClick={() => {
                  setNameDraft(table.name);
                  setDescDraft(table.description ?? "");
                  setRenaming(true);
                }}
              >
                <Pencil size={14} aria-hidden="true" />
              </Button>
            )}
          </h2>
          {table.description && <p className="text-sm text-muted-foreground">{table.description}</p>}
        </>
      )}
      <p className={`text-xs ${percent >= 90 ? "text-[var(--state-warning)]" : "text-muted-foreground"}`}>
        {t("usage", { count: table.rowCount.toLocaleString(), max: maxRows.toLocaleString() })}
      </p>
    </div>
  );
}
