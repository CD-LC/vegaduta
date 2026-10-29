"use client";

// Figma import dialog for the attach bar. The token is held in component
// state only for the duration of the request: never persisted (no
// localStorage), never put in a URL, cleared on success and on close.

import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Loader2 } from "lucide-react";
import { Figma } from "../components/lucideBrandIcons";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { appBuilder } from "./lib";
import type { DesignInput } from "./designInputs";
import { designInputFromFigma, validateFigmaUrl } from "./figmaInput";
import { useTranslations } from "../lib/i18n";

interface Props {
  projectId: string;
  disabled?: boolean;
  onImported: (input: DesignInput) => void;
  onError: (message: string) => void;
}

const FOCUSABLE = "input, button, a[href], textarea, select, [tabindex]:not([tabindex='-1'])";

export default function FigmaImport({ projectId, disabled, onImported, onError }: Props) {
    const tHC = useTranslations("hcBuildFigmaImport");
  const [open, setOpen] = useState(false);
  const [url, setUrl] = useState("");
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const uid = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const urlRef = useRef<HTMLInputElement>(null);

  const close = () => {
    setOpen(false);
    setToken("");
    setProblem(null);
  };

  // Move focus into the dialog on open; hand it back to the trigger on close.
  useEffect(() => {
    if (!open) return undefined;
    const trigger = triggerRef.current;
    urlRef.current?.focus();
    return () => trigger?.focus();
  }, [open]);

  const onDialogKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === "Escape") {
      e.stopPropagation();
      if (!busy) close();
      return;
    }
    if (e.key !== "Tab") return;
    // Keep Tab inside the modal.
    const focusable = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []).filter(
      (el) => !el.hasAttribute("disabled")
    );
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  const submit = async () => {
    if (busy) return;
    const bad = validateFigmaUrl(url);
    if (bad) return setProblem(bad);
    if (!token.trim()) return setProblem("Enter your Figma personal access token.");
    setBusy(true);
    setProblem(null);
    try {
      const res = await appBuilder.importFigma(projectId, url.trim(), token.trim());
      onImported(designInputFromFigma(res));
      setUrl("");
      close();
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Figma import failed";
      setProblem(msg);
      onError(msg);
    } finally {
      setBusy(false);
      setToken("");
    }
  };

  return (
    <>
      <Button
        ref={triggerRef}
        variant="ghost"
        size="sm"
        className="h-7 w-9 px-0"
        title={tHC("hcBuildFigmaImport.s7")}
        aria-label={tHC("hcBuildFigmaImport.s7")}
        aria-haspopup="dialog"
        disabled={disabled}
        onClick={() => setOpen(true)}
      >
        <Figma aria-hidden="true" size={14} />
      </Button>
      {open && (
        // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- backdrop click dismisses; Escape/close buttons are the keyboard path
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby={`${uid}-title`}
          aria-describedby={`${uid}-desc`}
          ref={dialogRef}
          onKeyDown={onDialogKeyDown}
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          onClick={(e) => {
            if (e.target === e.currentTarget && !busy) close();
          }}
        >
          <form
            className="w-full max-w-md space-y-3 rounded-lg border bg-background p-4 shadow-lg"
            onSubmit={(e: FormEvent) => {
              e.preventDefault();
              void submit();
            }}
          >
            <h3 id={`${uid}-title`} className="text-sm font-semibold">
              {tHC("hcBuildFigmaImport.s6")}
            </h3>
            <p id={`${uid}-desc`} className="text-xs text-muted-foreground">
              {tHC("hcBuildFigmaImport.s5")}
            </p>
            <div className="space-y-1">
              <label htmlFor={`${uid}-url`} className="text-xs font-medium text-foreground">
                {tHC("hcBuildFigmaImport.s4")}
              </label>
              <Input
                id={`${uid}-url`}
                ref={urlRef}
                placeholder="https://www.figma.com/design/…?node-id=1-2"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                autoComplete="off"
                aria-invalid={!!problem}
              />
            </div>
            <div className="space-y-1">
              <label htmlFor={`${uid}-token`} className="text-xs font-medium text-foreground">
                {tHC("hcBuildFigmaImport.s3")}
              </label>
              <Input
                id={`${uid}-token`}
                type="password"
                placeholder={tHC("hcBuildFigmaImport.s2")}
                value={token}
                onChange={(e) => setToken(e.target.value)}
                autoComplete="off"
              />
            </div>
            {problem && (
              <p role="alert" className="text-xs text-destructive">
                {problem}
              </p>
            )}
            <p className="text-xs text-muted-foreground">
              {tHC("hcBuildFigmaImport.s1")}
            </p>
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={close}>
                Cancel
              </Button>
              <Button type="submit" size="sm" disabled={busy}>
                {busy ? <Loader2 size={14} className="animate-spin" aria-label="Importing" /> : "Import"}
              </Button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
