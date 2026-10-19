"use client";

// A thin IDE-style status bar under the box: who is answering, how the box sends, whether a
// folder is attached, how many background tasks are running, and the shortcut that opens the
// palette. Read-only except the palette button; the controls live in the box and the header.

import { Command, FolderOpen, Layers, Loader2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { ComposerMode } from "./assistantTypes";

export default function AssistantStatusBar({
  agentName,
  mode,
  folderName,
  liveTasks,
  sending,
  onOpenPalette,
}: {
  agentName: string | null;
  mode: ComposerMode;
  folderName: string | null;
  liveTasks: number;
  sending: boolean;
  onOpenPalette: () => void;
}) {
  const t = useTranslations("assistant");
  return (
    <div
      className="flex items-center gap-3 border-t border-border bg-secondary/30 px-3 py-1 text-[11px] text-muted-foreground sm:px-6"
      data-testid="assistant-statusbar"
    >
      <span className="inline-flex items-center gap-1.5" role="status">
        <span
          aria-hidden="true"
          className={sending ? "size-1.5 animate-pulse rounded-full bg-primary" : "size-1.5 rounded-full bg-emerald-500"}
        />
        {sending ? t("statusBusy") : t("statusReady")}
        {agentName && <span className="hidden text-foreground/80 sm:inline">· {agentName}</span>}
      </span>
      <span className="hidden items-center gap-1 sm:inline-flex">
        <Layers size={11} aria-hidden="true" />
        {t(`statusMode_${mode}`)}
      </span>
      {folderName && (
        <span className="hidden max-w-[10rem] items-center gap-1 truncate md:inline-flex">
          <FolderOpen size={11} aria-hidden="true" />
          {folderName}
        </span>
      )}
      {liveTasks > 0 && (
        <span className="inline-flex items-center gap-1 text-primary">
          <Loader2 size={11} className="animate-spin" aria-hidden="true" />
          {t("statusTasks", { n: liveTasks })}
        </span>
      )}
      <span className="flex-1" />
      <button
        type="button"
        onClick={onOpenPalette}
        className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 transition-colors hover:bg-secondary hover:text-foreground"
      >
        <Command size={11} aria-hidden="true" />
        {t("statusPalette")}
      </button>
    </div>
  );
}
