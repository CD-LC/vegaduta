"use client";

import { useRef, useState, type DragEvent, type KeyboardEvent } from "react";
import { UploadCloud } from "lucide-react";
import { checkImportFile, MAX_IMPORT_BYTES } from "../../lib/skillLibrary";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";

/**
 * Drop a skill zip or SKILL.md here (or press Enter / Space to browse).
 * Screens type and size locally for fast feedback; the server still decides.
 */
export default function SkillImportDropzone({
  onImport,
  busy,
}: {
  onImport: (file: File) => Promise<void> | void;
  busy: boolean;
}) {
  const t = useTranslations("skillLibrary");
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const accept = (file: File | undefined) => {
    if (!file || busy) return;
    const check = checkImportFile(file);
    if (check.ok === false) {
      setProblem(
        check.reason === "type"
          ? t("importBadType")
          : check.reason === "empty"
            ? t("importEmpty")
            : t("importTooBig", { mb: Math.round(MAX_IMPORT_BYTES / (1024 * 1024)) })
      );
      return;
    }
    setProblem(null);
    void onImport(file);
  };

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setOver(false);
    accept(e.dataTransfer?.files?.[0]);
  };

  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      if (!busy) input.current?.click();
    }
  };

  return (
    <div>
      <div
        role="button"
        tabIndex={0}
        aria-disabled={busy}
        aria-describedby="skill-import-help"
        data-testid="skill-dropzone"
        onClick={() => !busy && input.current?.click()}
        onKeyDown={onKey}
        onDragOver={(e) => {
          e.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={onDrop}
        className={cn(
          "flex cursor-pointer flex-col items-center gap-1 rounded-lg border-2 border-dashed border-border px-4 py-5 text-center outline-none transition-colors",
          "hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring",
          over && "border-primary bg-accent",
          busy && "cursor-progress opacity-60"
        )}
      >
        <UploadCloud size={20} aria-hidden="true" className="text-muted-foreground" />
        <span className="text-sm font-medium">{busy ? t("importing") : t("importTitle")}</span>
        <span id="skill-import-help" className="text-xs text-muted-foreground">
          {t("importHelp")}
        </span>
        <input
          ref={input}
          type="file"
          accept=".zip,.md,application/zip,text/markdown"
          className="hidden"
          tabIndex={-1}
          aria-label={t("importInputLabel")}
          data-testid="skill-import-input"
          onChange={(e) => {
            const file = e.target.files?.[0];
            e.target.value = "";
            accept(file);
          }}
        />
      </div>
      <p role="alert" className="mt-1 min-h-4 text-xs text-destructive">
        {problem}
      </p>
    </div>
  );
}
