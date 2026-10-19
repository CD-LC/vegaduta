"use client";

import { useEffect, useState } from "react";
import { Download } from "lucide-react";
import { api, triggerBrowserDownload } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";
import { EXPORT_FORMATS, type ChatImportFeatures, type ExportFormat } from "../lib/chatPortability";
import { Button } from "./ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";

/**
 * "Export" for a chat surface: download this conversation as Markdown, JSON or plain text from
 * GET /api/agents/{id}/sessions/{sessionId}/export (core ChatExportController). Attachments come out as names only.
 *
 * Renders nothing until there is a session to export AND the server says chat export is on (the features probe;
 * app.chat-export.enabled is off by default), so a core without the feature, or with it off, shows no control and
 * raises no error. A failed download (the session expired, or access was withdrawn) already toasts through apiFetch.
 */
export default function ChatExportMenu({
  agentId,
  sessionId,
  className,
}: {
  agentId: string;
  sessionId: string | null;
  className?: string;
}) {
  const t = useTranslations("chatPortability");
  const [enabled, setEnabled] = useState(false);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState<ExportFormat | null>(null);

  useEffect(() => {
    let alive = true;
    api
      .getChatPortabilityFeatures()
      .then((f: ChatImportFeatures) => {
        if (alive) setEnabled(Boolean(f && f.exportEnabled));
      })
      .catch(() => {
        if (alive) setEnabled(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  if (!enabled || !sessionId) return null;

  const run = async (format: ExportFormat) => {
    if (busy) return;
    setBusy(format);
    try {
      const { blob, filename } = await api.exportChatSession(agentId, sessionId, format);
      triggerBrowserDownload(blob, filename);
      setOpen(false);
    } catch {
      // the error toast was already shown by the API layer
    } finally {
      setBusy(null);
    }
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        data-testid="chat-export-trigger"
        className={cn(
          "inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-accent hover:text-foreground",
          className
        )}
      >
        <Download size={12} className="size-3 shrink-0" aria-hidden="true" />
        <span>{t("exportButton")}</span>
      </PopoverTrigger>
      <PopoverContent align="start" className="p-3 text-sm" data-testid="chat-export-panel">
        <p className="font-medium text-foreground">{t("exportTitle")}</p>
        <p className="mt-0.5 text-xs text-muted-foreground">{t("exportNote")}</p>
        <div className="mt-2 flex flex-col gap-1">
          {EXPORT_FORMATS.map((f) => (
            <Button
              key={f.id}
              type="button"
              size="sm"
              variant="outline"
              className="justify-start"
              disabled={busy !== null}
              onClick={() => void run(f.id)}
            >
              {t(f.labelKey)}
            </Button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );
}
