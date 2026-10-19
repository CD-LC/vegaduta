"use client";

// "Generate image" control for the Design panel's asset strip. Renders a
// trigger button plus a lightweight modal. On success the image is already
// committed under assets/ (app-builder does that); we refresh the strip and
// offer "Use in app", which hands a chat instruction to the parent.

import { useEffect, useId, useRef, useState } from "react";
import { Loader2, Sparkles } from "lucide-react";
import { Button } from "../components/ui/button";
import { Textarea } from "../components/ui/textarea";
import { Select } from "../components/ui/select";
import { appBuilder } from "./lib";
import {
  IMAGE_KINDS,
  IMAGE_SIZES,
  KIND_LABELS,
  defaultSizeFor,
  assetUsageInstruction,
  validateImageRequest,
  type ImageKind,
} from "./imagePrompt";

interface Props {
  disabled?: boolean;
  projectId: string;
  onGenerated?: () => void;
  onUseInApp?: (instruction: string) => void;
}

export default function GenerateImageDialog({ projectId, onGenerated, onUseInApp, disabled }: Props) {
  const [open, setOpen] = useState(false);
  const titleId = useId();
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const promptRef = useRef<HTMLTextAreaElement | null>(null);
  // A drag that starts inside the dialog (selecting text) and ends on the
  // backdrop must not count as a backdrop click.
  const downOnBackdrop = useRef(false);
  // "Use in app" hands focus to the chat composer; don't pull it back here.
  const skipRestore = useRef(false);
  const [prompt, setPrompt] = useState("");
  const [kind, setKind] = useState<ImageKind>("logo");
  const [size, setSize] = useState<string>(defaultSizeFor("logo"));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{ path: string; kind: ImageKind } | null>(null);

  const close = () => {
    if (busy) return;
    setOpen(false);
    setError(null);
    setResult(null);
  };

  // Real-modal behaviour: focus moves in on open and returns to the trigger on
  // close; Escape closes (unless a request is running); Tab stays inside.
  useEffect(() => {
    if (!open) return undefined;
    const trigger = triggerRef.current;
    promptRef.current?.focus();
    return () => {
      if (!skipRestore.current) trigger?.focus();
      skipRestore.current = false;
    };
  }, [open]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") {
      e.stopPropagation();
      close();
      return;
    }
    if (e.key !== "Tab") return;
    const nodes = dialogRef.current?.querySelectorAll<HTMLElement>(
      "button:not([disabled]), textarea:not([disabled]), select:not([disabled]), [href], input:not([disabled])"
    );
    if (!nodes || nodes.length === 0) return;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || !dialogRef.current?.contains(active))) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && (active === last || !dialogRef.current?.contains(active))) {
      e.preventDefault();
      first.focus();
    }
  };

  const generate = async () => {
    const problem = validateImageRequest(prompt, kind);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const r = await appBuilder.generateImage(projectId, { prompt: prompt.trim(), kind, size });
      if (!r?.path) throw new Error("The image service returned no file. Try again.");
      setResult({ path: r.path, kind });
      onGenerated?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Image generation failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Button
        ref={triggerRef}
        variant="outline"
        size="sm"
        className="h-7 gap-1.5 text-xs"
        disabled={disabled}
        onClick={() => setOpen(true)}
      >
        <Sparkles aria-hidden="true" size={12} /> Generate image
      </Button>
      {open && (
        // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- backdrop click dismisses; the dialog has its own close button
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          onMouseDown={(e) => {
            downOnBackdrop.current = e.target === e.currentTarget;
          }}
          onClick={(e) => {
            if (e.target === e.currentTarget && downOnBackdrop.current) close();
            downOnBackdrop.current = false;
          }}
        >
          {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- inner dialog only stops click propagation to the backdrop */}
          <div
            ref={dialogRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby={titleId}
            onKeyDown={onKeyDown}
            className="max-h-[90dvh] w-full max-w-md overflow-auto rounded-xl border border-border bg-background p-4 shadow-lg"
          >
            <h3 id={titleId} className="mb-3 text-sm font-semibold">Generate an image</h3>
            <div className="mb-3 flex flex-wrap gap-2">
              {IMAGE_KINDS.map((k) => (
                <button
                  key={k}
                  type="button"
                  aria-pressed={kind === k}
                  disabled={busy}
                  onClick={() => {
                    setKind(k);
                    setSize(defaultSizeFor(k));
                  }}
                  className={`rounded-lg border px-2 py-1 text-xs ${
                    kind === k ? "border-primary bg-primary/10 text-foreground" : "border-border text-muted-foreground"
                  }`}
                >
                  {KIND_LABELS[k]}
                </button>
              ))}
            </div>
            <Textarea
              ref={promptRef}
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="e.g. a friendly fox for a bakery called Crumb"
              rows={3}
              disabled={busy}
              aria-label="Image description"
            />
            <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
              <label htmlFor="gen-image-size">Size</label>
              <Select
                id="gen-image-size"
                className="w-32"
                value={size}
                disabled={busy}
                onValueChange={setSize}
                options={IMAGE_SIZES.map((x) => ({ value: x, label: x }))}
              />
            </div>
            {error && (
              <p role="alert" className="mt-2 text-xs text-destructive">
                {error}
              </p>
            )}
            {result && (
              <p className="mt-2 text-xs text-foreground">
                Saved as <code>{result.path}</code>.
              </p>
            )}
            <div className="mt-3 flex justify-end gap-2">
              <Button variant="ghost" size="sm" onClick={close} disabled={busy}>
                {result ? "Done" : "Cancel"}
              </Button>
              {result && onUseInApp && (
                <Button
                  size="sm"
                  onClick={() => {
                    onUseInApp(assetUsageInstruction(result.kind, result.path));
                    skipRestore.current = true;
                    close();
                  }}
                >
                  Use in app
                </Button>
              )}
              <Button size="sm" onClick={() => void generate()} disabled={busy}>
                {busy ? (
                  <>
                    <Loader2 aria-hidden="true" size={12} className="mr-1 animate-spin" /> Generating…
                  </>
                ) : result ? (
                  "Generate another"
                ) : (
                  "Generate"
                )}
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
