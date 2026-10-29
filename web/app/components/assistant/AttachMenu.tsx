"use client";

import { useRef, useState, type ChangeEvent } from "react";
import { Camera, FileUp, Phone, Plus } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { buildAccept, type AgentModalities } from "../ChatAttachments";
import { Button } from "../ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";

/**
 * The "+" in the box: upload files, take a photo, start a call. Files are searchable by the Assistant
 * where the install has a projects folder (`searchable`), and then any kind of file is accepted;
 * otherwise the picker offers only what the model can take.
 */
export default function AttachMenu({
  searchable,
  modalities,
  disabled,
  onFiles,
  onCamera,
  onCall,
}: {
  searchable: boolean;
  modalities: AgentModalities | null;
  disabled?: boolean;
  onFiles: (files: File[]) => void;
  onCamera: (() => void) | null;
  onCall: (() => void) | null;
}) {
  const t = useTranslations("assistant");
  const [open, setOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  function onChange(e: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files ?? []);
    e.target.value = "";
    if (files.length > 0) onFiles(files);
  }

  const canFiles = searchable || !!modalities;
  if (!canFiles && !onCamera && !onCall) return null;

  const item =
    "flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-start text-sm hover:bg-accent focus-visible:bg-accent focus-visible:outline-none disabled:opacity-50";

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        multiple
        accept={searchable ? undefined : modalities ? buildAccept(modalities) : undefined}
        onChange={onChange}
        className="sr-only"
        tabIndex={-1}
        aria-label={t("attachChoose")}
      />
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            size="icon"
            variant="ghost"
            className="size-9 shrink-0 rounded-full text-muted-foreground hover:text-foreground"
            disabled={disabled}
            aria-label={t("attachMenu")}
            title={t("attachMenu")}
          >
            <Plus size={18} aria-hidden="true" />
          </Button>
        </PopoverTrigger>
        <PopoverContent side="top" align="start" className="w-72 p-1.5">
          {canFiles && (
            <button
              type="button"
              className={item}
              onClick={() => {
                setOpen(false);
                inputRef.current?.click();
              }}
            >
              <FileUp size={16} aria-hidden="true" className="shrink-0 text-muted-foreground" />
              <span className="min-w-0">
                <span className="block font-medium text-foreground">{t("attachUpload")}</span>
                <span className="block text-xs text-muted-foreground">
                  {searchable ? t("attachUploadSearchable") : t("attachUploadPlain")}
                </span>
              </span>
            </button>
          )}
          {onCamera && (
            <button
              type="button"
              className={item}
              onClick={() => {
                setOpen(false);
                onCamera();
              }}
            >
              <Camera size={16} aria-hidden="true" className="shrink-0 text-muted-foreground" />
              <span className="min-w-0">
                <span className="block font-medium text-foreground">{t("attachCamera")}</span>
                <span className="block text-xs text-muted-foreground">{t("attachCameraHint")}</span>
              </span>
            </button>
          )}
          {onCall && (
            <button
              type="button"
              className={item}
              onClick={() => {
                setOpen(false);
                onCall();
              }}
            >
              <Phone size={16} aria-hidden="true" className="shrink-0 text-muted-foreground" />
              <span className="min-w-0">
                <span className="block font-medium text-foreground">{t("attachCall")}</span>
                <span className="block text-xs text-muted-foreground">{t("attachCallHint")}</span>
              </span>
            </button>
          )}
        </PopoverContent>
      </Popover>
    </>
  );
}
