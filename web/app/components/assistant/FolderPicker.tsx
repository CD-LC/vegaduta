"use client";

import { useState, type FormEvent } from "react";
import { Check, FolderOpen, FolderPlus, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import type { AssistantWorkspace } from "../../lib/api/assistant";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";

/**
 * "Attach a folder": which project in the person's projects folder the Assistant works in. The
 * choice is told to the model with every message (app_context); the tools confine every path to
 * the projects folder whatever the model does.
 *
 * Renders nothing where the install has no projects folder API at all; a one-line hint where the
 * API answers but the folder is not set up (a personal install without --profile workspace).
 */
export default function FolderPicker({
  workspace,
  folder,
  onAttach,
  onCreate,
  disabled,
}: {
  workspace: AssistantWorkspace | null;
  folder: string | null;
  onAttach: (name: string | null) => void;
  onCreate: (name: string) => Promise<void>;
  disabled?: boolean;
}) {
  const t = useTranslations("assistant");
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!workspace) return null;
  if (!workspace.available) {
    return (
      <p className="px-4 pt-2 text-xs text-muted-foreground sm:px-6" data-testid="folder-picker-off">
        {t("folderOffHint")}
      </p>
    );
  }

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!name.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await onCreate(name.trim());
      setName("");
      setOpen(false);
    } catch (err) {
      setError(errorMessage(err, t("folderCreateFailed")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2 px-4 pt-2 sm:px-6" data-testid="folder-picker">
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button type="button" variant="outline" size="sm" disabled={disabled} className="h-7 rounded-full text-xs">
            <FolderOpen aria-hidden="true" className="size-3.5" />
            {folder ? t("folderAttached", { name: folder }) : t("folderAttach")}
          </Button>
        </PopoverTrigger>
        <PopoverContent align="start" className="w-80 p-3">
          <p className="text-xs text-muted-foreground">
            {workspace.hostPath ? t("folderWhere", { path: workspace.hostPath }) : t("folderWhereUnknown")}
          </p>
          {!workspace.terminal && <p className="mt-1 text-xs text-muted-foreground">{t("folderNoTerminal")}</p>}
          <ul className="m-0 mt-2 max-h-56 list-none overflow-y-auto p-0" aria-label={t("folderListLabel")}>
            {workspace.folders.length === 0 && (
              <li className="py-1 text-xs text-muted-foreground">{t("folderNone")}</li>
            )}
            {workspace.folders.map((f) => (
              <li key={f.name}>
                <button
                  type="button"
                  className="flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
                  onClick={() => {
                    onAttach(f.name);
                    setOpen(false);
                  }}
                >
                  <span className="truncate">{f.name}</span>
                  {folder === f.name && <Check aria-hidden="true" className="size-3.5 text-primary" />}
                </button>
              </li>
            ))}
          </ul>
          <form onSubmit={(e) => void create(e)} className="mt-2 flex gap-2 border-t border-border pt-2">
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t("folderNewPlaceholder")}
              aria-label={t("folderNewPlaceholder")}
              className="h-8 text-sm"
              maxLength={80}
            />
            <Button type="submit" size="sm" disabled={!name.trim() || busy}>
              <FolderPlus aria-hidden="true" className="size-3.5" />
              {t("folderCreate")}
            </Button>
          </form>
          <p className="mt-2 border-t border-border pt-2 text-xs text-muted-foreground">
            {t("folderSwitchHint")} <code className="rounded bg-secondary/60 px-1">scripts/vegaduta projects ~/my-folder</code>
          </p>
          {error && (
            <p role="alert" className="mt-1 text-xs text-destructive">
              {error}
            </p>
          )}
        </PopoverContent>
      </Popover>
      {folder && (
        <button
          type="button"
          className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          onClick={() => onAttach(null)}
          disabled={disabled}
        >
          <X aria-hidden="true" className="size-3" />
          {t("folderDetach")}
        </button>
      )}
      {folder && <span className="text-xs text-muted-foreground">{t("folderLongTaskHint")}</span>}
    </div>
  );
}
