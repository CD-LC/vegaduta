"use client";

import { useTranslations } from "../lib/i18n";
import { Button } from "./ui/button";

// The quiet "reconnecting..." line shown while a chat stream is being resumed
// (api/chatResume.ts) or a reply left in flight by a reloaded page is being
// restored. It replaces the request status line for the duration so a dropped
// connection never flashes a "Failed" state at someone whose reply is in fact
// still being written. Same plain row the on-device status line uses; Stop ends
// the turn (and, for a detached turn, tells the server to stop generating).

export interface StreamReconnectingState {
  attempt: number;
  max: number;
  /** A reloaded page restoring a reply, as opposed to a live stream reconnecting. */
  restoring?: boolean;
  cancel: () => void;
}

export default function StreamReconnectingNotice({ state }: { state: StreamReconnectingState }) {
  const t = useTranslations("chatResume");
  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="stream-reconnecting"
      className="mb-2 flex items-center gap-1.5 rounded-md border border-border bg-secondary/30 px-2.5 py-1.5 text-xs text-muted-foreground"
    >
      <span className="size-1.5 shrink-0 animate-pulse rounded-full bg-primary" aria-hidden="true" />
      <span className="min-w-0 truncate">
        {state.restoring ? t("restoring") : t("reconnecting", { attempt: state.attempt, max: state.max })}
      </span>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="ms-auto h-6 shrink-0 px-2 text-xs"
        onClick={state.cancel}
      >
        {t("reconnectingStop")}
      </Button>
    </div>
  );
}
