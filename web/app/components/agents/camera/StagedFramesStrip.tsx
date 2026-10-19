"use client";

import { X } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import type { StagedFrame } from "./captureCore";

/** Thumbnails of the frames staged for the next send, with per-frame removal. */
export default function StagedFramesStrip({
  stagedFrames,
  onRemove,
}: {
  stagedFrames: StagedFrame[];
  onRemove: (frameId: string) => void;
}) {
  const t = useTranslations("cameraPanel");
  return (
    <>
      {stagedFrames.length > 0 && (
        <div className="flex flex-wrap items-center gap-2" aria-label={t("stagedFramesLabel")}>
          {stagedFrames.map((frame) => (
            <span key={frame.id} className="relative">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={frame.dataUrl}
                alt={frame.fileName}
                className="h-14 rounded-md border border-border object-cover"
              />
              <button
                type="button"
                onClick={() => onRemove(frame.id)}
                aria-label={t("removeFrame", { name: frame.fileName })}
                // p-0/border/bg overrides: globals.css's bare-button rule would
                // otherwise inflate this into a chunky box (see the icon-button note).
                className="absolute -right-1.5 -top-1.5 flex size-5 items-center justify-center rounded-full border border-border bg-card p-0 text-muted-foreground shadow-none hover:text-foreground"
              >
                <X size={10} />
              </button>
            </span>
          ))}
          <span className="text-xs text-muted-foreground">{t("stagedHint")}</span>
        </div>
      )}
    </>
  );
}
