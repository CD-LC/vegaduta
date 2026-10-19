"use client";

import { Copy, Download, FileText, Pencil, Trash2 } from "lucide-react";
import type { SkillDetail } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";

/** The skill viewer's title row: export, download text, duplicate, and (custom skills) edit/delete. */
export default function SkillViewerToolbar({
  detail,
  filePath,
  canManageSkills,
  busy,
  onExport,
  onDownloadText,
  onDuplicate,
  onEdit,
  onDelete,
}: {
  detail: SkillDetail;
  filePath: string | null;
  canManageSkills: boolean;
  busy: boolean;
  onExport: () => void;
  onDownloadText: () => void;
  onDuplicate: () => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const t = useTranslations("skillsPage");
  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="mr-auto font-mono text-base font-semibold text-foreground">{detail.name}</h2>
        <Button size="sm" variant="outline" disabled={busy} onClick={onExport}>
          <Download aria-hidden="true" size={14} /> {t("exportButton")}
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          title="Download this text as a plain file"
          onClick={onDownloadText}
        >
          <FileText aria-hidden="true" size={14} /> {filePath === null ? "SKILL.md" : "File"}
        </Button>
        {canManageSkills && (
          <Button
            size="sm"
            variant="outline"
            disabled={busy}
            title="Start a new custom skill from a copy of this one"
            onClick={onDuplicate}
          >
            <Copy aria-hidden="true" size={14} />{" "}
            {detail.source === "BUILT_IN" ? "Customize" : "Duplicate"}
          </Button>
        )}
        {canManageSkills && detail.source === "CUSTOM" && (
          <>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={onEdit}
            >
              <Pencil aria-hidden="true" size={14} /> {t("editButton")}
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={onDelete}
            >
              <Trash2 aria-hidden="true" size={14} /> {t("deleteButton")}
            </Button>
          </>
        )}
      </div>
      {detail.source === "BUILT_IN" && (
        <p className="text-xs text-muted-foreground">{t("builtInReadOnly")}</p>
      )}
    </>
  );
}
