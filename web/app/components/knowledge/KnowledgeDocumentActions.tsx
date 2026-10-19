"use client";

import { useState } from "react";
import { Download, Loader2, Trash2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";

type Doc = { id: string; filename: string; hasOriginalFile?: boolean };

/**
 * Row actions of the Knowledge file table: download the stored original (only
 * when the backend kept one) and delete the document. Delete asks twice - the
 * first click arms the button, a second click on the same row deletes - the
 * same two-step the collection sidebar uses, so no modal is needed.
 */
export default function KnowledgeDocumentActions({
  doc,
  onDeleted,
  onError,
}: {
  doc: Doc;
  onDeleted: () => void;
  onError: (message: string | null) => void;
}) {
  const t = useTranslations("knowledgePanel");
  const [downloading, setDownloading] = useState(false);
  const [armed, setArmed] = useState(false);
  const [deleting, setDeleting] = useState(false);

  // Fetches the ORIGINAL uploaded file (not the extracted text) and hands it
  // to the browser as a real download; a plain <a href> cannot carry the
  // bearer token (see api.downloadKnowledgeDocumentFile).
  async function download() {
    onError(null);
    setDownloading(true);
    try {
      const { blob, filename } = await api.downloadKnowledgeDocumentFile(doc.id);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename || doc.filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      onError(errorMessage(err, t("downloadFileError")));
    } finally {
      setDownloading(false);
    }
  }

  async function remove() {
    if (!armed) {
      setArmed(true);
      return;
    }
    onError(null);
    setDeleting(true);
    try {
      await api.deleteKnowledgeDocument(doc.id);
      onDeleted();
    } catch (err) {
      onError(errorMessage(err, t("deleteFileError")));
    } finally {
      setDeleting(false);
      setArmed(false);
    }
  }

  const deleteLabel = armed
    ? t("deleteFileConfirmTooltip", { filename: doc.filename })
    : t("deleteFileTooltip", { filename: doc.filename });

  return (
    <div className="flex items-center justify-end gap-0.5">
      {doc.hasOriginalFile ? (
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="size-7 text-muted-foreground hover:text-foreground"
              aria-label={t("downloadFileTooltip", { filename: doc.filename })}
              onClick={() => void download()}
              disabled={downloading}
            >
              {downloading ? (
                <Loader2 aria-hidden="true" size={13} className="animate-spin" />
              ) : (
                <Download aria-hidden="true" size={13} />
              )}
            </Button>
          </TooltipTrigger>
          <TooltipContent>{t("downloadFileTooltip", { filename: doc.filename })}</TooltipContent>
        </Tooltip>
      ) : null}
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            data-testid="knowledge-document-delete"
            className={cn(
              "size-7",
              armed
                ? "bg-destructive/15 text-destructive hover:text-destructive"
                : "text-muted-foreground hover:text-destructive"
            )}
            aria-label={deleteLabel}
            onClick={() => void remove()}
            onBlur={() => setArmed(false)}
            disabled={deleting}
          >
            {deleting ? (
              <Loader2 aria-hidden="true" size={13} className="animate-spin" />
            ) : (
              <Trash2 aria-hidden="true" size={13} />
            )}
          </Button>
        </TooltipTrigger>
        <TooltipContent>{deleteLabel}</TooltipContent>
      </Tooltip>
    </div>
  );
}
