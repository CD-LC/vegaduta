// @ts-nocheck
"use client";

// Split out of ChatPane.tsx (behaviour unchanged): the design inputs queued for
// the next message - dropped images, an SVG/Figma export, the sketch pad's
// wireframe - as removable chips.
import { ImagePlus, LayoutTemplate, Palette, X } from "lucide-react";
import { BUILD_STRINGS as S } from "./buildStrings";
import { useTranslations } from "../lib/i18n";

export function DesignInputChips({ designInputs, sketchBlockCount, onOpenSketch, onRemove }) {
    const tHC = useTranslations("hcBuildDesignInputChips");
  return (
    <div className="mb-2 flex shrink-0 flex-wrap items-center gap-1.5">
      {sketchBlockCount > 0 && (
        <button
          type="button"
          onClick={onOpenSketch}
          className="inline-flex items-center gap-1 rounded-full border border-border bg-muted/40 px-2 py-0.5 text-xs text-foreground hover:bg-accent/60"
        >
          <LayoutTemplate aria-hidden="true" size={11} /> {tHC("hcBuildDesignInputChips.s1")}{sketchBlockCount} blocks)
        </button>
      )}
      {designInputs.map((d) => (
        <span
          key={d.id}
          className="inline-flex items-center gap-1 rounded-full border border-border bg-muted/40 px-2 py-0.5 text-xs text-foreground"
        >
          {d.previewUrl && (
            <img src={d.previewUrl} alt="" className="size-4 rounded object-cover" />
          )}
          {d.kind === "svg" ? <Palette aria-hidden="true" size={11} /> : <ImagePlus aria-hidden="true" size={11} />}
          {d.label}
          <button
            type="button"
            onClick={() => onRemove(d)}
            className="border-0 bg-transparent p-0 text-muted-foreground shadow-none hover:text-destructive"
            aria-label={S.removeDesignLabel(d.label)}
          >
            <X size={12} aria-hidden="true" />
          </button>
        </span>
      ))}
    </div>
  );
}
