"use client";

import { Paperclip } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import type { PlaygroundTurn } from "./types";

/** One user message: attachment chip, camera/screen frame thumbnails, the bubble and the "You" / watch caption. */
export function UserTurn({ turn }: { turn: PlaygroundTurn }) {
  const t = useTranslations("agentPlayground");
  return (
        <div className="flex flex-col items-end gap-1">
          {turn.attachment && (
            <div className="flex items-center gap-1.5 rounded-full border border-border bg-secondary/50 px-2.5 py-1 text-xs text-muted-foreground">
              <Paperclip size={11} />
              {turn.attachment.originalFilename || turn.attachment.category}
            </div>
          )}
          {/* Camera/screen frames that rode this turn (live vision) - the
            JPEG data URLs kept by framesForHistory, thumbnail-sized. */}
          {turn.frames?.length > 0 && (
            <div className="flex max-w-[85%] flex-wrap justify-end gap-1.5">
              {turn.frames.map((frame, fi) => (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  key={fi}
                  src={frame.dataUrl}
                  alt={frame.fileName}
                  className="max-h-24 rounded-lg border border-border object-cover"
                />
              ))}
            </div>
          )}
          <div className="max-w-[85%] whitespace-pre-wrap rounded-xl bg-primary px-4 py-2.5 text-sm leading-relaxed text-primary-foreground">
            {turn.content}
          </div>
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
            {turn.watch && (
              <span
                className="rounded-full border border-border px-1.5 py-px text-[11px] uppercase tracking-wide"
                title={t("watchBadgeTitle")}
              >
                {t("watchBadge")}
              </span>
            )}
            {t("youPlayground")}
          </div>
        </div>
  );
}
