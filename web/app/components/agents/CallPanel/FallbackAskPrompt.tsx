"use client";

import type { MutableRefObject } from "react";
import { Button } from "../../ui/button";


interface FallbackAskPromptProps {
  pendingFallbackAsk: string | null;
  fallbackAskResolverRef: MutableRefObject<((confirmed: boolean) => void) | null>;
}

export default function FallbackAskPrompt({ pendingFallbackAsk, fallbackAskResolverRef }: FallbackAskPromptProps) {
  return (
    <>
      {/* "ask" fallback policy: the inline confirm/cancel prompt
          resolveAutomaticFallback awaits (via askToFallback) before an
          automatic-fallback point proceeds. ASK_FALLBACK_TIMEOUT_MS resolves
          this to Cancel on its own if nobody answers. */}
      {pendingFallbackAsk && (
        <div className="rounded-lg border border-border bg-secondary/40 px-2.5 py-1.5 text-xs text-muted-foreground space-y-1.5">
          <div>{pendingFallbackAsk}</div>
          <div className="flex gap-2">
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => fallbackAskResolverRef.current?.(true)}
            >
              Send to server
            </Button>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => fallbackAskResolverRef.current?.(false)}
            >
              Cancel
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
