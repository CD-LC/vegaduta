"use client";

// "Copy as JSON" / "Import JSON" for the blueprint screen: share a blueprint,
// keep a copy, or paste one back in. Import is validated (blueprintJson.ts);
// bad input is rejected with a message and nothing changes.

import { useId, useState } from "react";
import { ClipboardCopy, FileJson, Upload } from "lucide-react";
import { Button } from "../components/ui/button";
import { Textarea } from "../components/ui/textarea";
import type { Blueprint } from "../lib/build/blueprint";
import { parseBlueprintJson, serializeBlueprint } from "./blueprintJson";

export default function BlueprintJsonTools({ bp, onImport }: { bp: Blueprint; onImport: (next: Blueprint) => void }) {
  const uid = useId();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  const copy = async () => {
    setError(null);
    try {
      await navigator.clipboard.writeText(serializeBlueprint(bp));
      setStatus("Blueprint copied as JSON.");
    } catch {
      // Clipboard blocked: surface the JSON in the import box so it can be copied by hand.
      setText(serializeBlueprint(bp));
      setOpen(true);
      setStatus("Could not reach the clipboard - the JSON is in the box below, select and copy it.");
    }
    window.setTimeout(() => setStatus(null), 4000);
  };

  const load = () => {
    const r = parseBlueprintJson(text);
    if (!r.ok || !r.blueprint) {
      setError(r.error ?? "That is not a valid blueprint.");
      return;
    }
    setError(null);
    setStatus(null);
    setText("");
    setOpen(false);
    onImport(r.blueprint);
  };

  return (
    <section className="rounded-xl border border-border bg-card p-3" aria-label="Blueprint JSON">
      <div className="flex flex-wrap items-center gap-2">
        <FileJson className="size-4 text-muted-foreground" aria-hidden />
        <span className="text-xs font-medium text-foreground">Blueprint as JSON</span>
        <div className="ms-auto flex items-center gap-1.5">
          <Button type="button" size="sm" variant="outline" onClick={() => void copy()}>
            <ClipboardCopy aria-hidden="true" className="size-3.5" /> Copy as JSON
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            aria-expanded={open}
            aria-controls={`${uid}-import`}
            onClick={() => setOpen((o) => !o)}
          >
            <Upload aria-hidden="true" className="size-3.5" /> Import JSON
          </Button>
        </div>
      </div>
      <p role="status" aria-live="polite" className="mt-1 min-h-4 text-xs text-muted-foreground">
        {status}
      </p>
      {open && (
        <div id={`${uid}-import`} className="mt-2 space-y-2">
          <label htmlFor={`${uid}-text`} className="text-xs font-medium text-foreground">
            Paste a blueprint JSON (this replaces the blueprint on screen)
          </label>
          <Textarea
            id={`${uid}-text`}
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              setError(null);
            }}
            spellCheck={false}
            className="min-h-32 font-mono text-xs"
            aria-invalid={!!error}
            aria-describedby={error ? `${uid}-err` : undefined}
          />
          {error && (
            <p id={`${uid}-err`} role="alert" className="text-xs text-destructive">
              {error}
            </p>
          )}
          <div className="flex gap-2">
            <Button type="button" size="sm" onClick={load} disabled={!text.trim()}>
              Load blueprint
            </Button>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => {
                setOpen(false);
                setError(null);
              }}
            >
              Cancel
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
