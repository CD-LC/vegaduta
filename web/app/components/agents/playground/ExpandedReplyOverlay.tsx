"use client";

import { useEffect, useRef } from "react";
import { X } from "lucide-react";
import { Button } from "../../ui/button";
import MediaCard from "../../MediaCard";
import type { ToolCallEntry } from "./types";

/** Full-screen popup for a long reply (live user report, 2026-08-06: "huge
 * reply from llm, there should be a control to view it in big screen
 * popup"). Plain fixed-position overlay, not a new shared Dialog primitive -
 * this repo has no generic modal component today (only alert-dialog.tsx,
 * which is confirmation-specific), and one usage doesn't justify building
 * one. Reuses MediaCard so the expanded view renders identically to the
 * inline bubble (markdown, generated-media detection) - only the size and
 * chrome differ. */
export function ExpandedReplyOverlay({
  text,
  trace,
  onClose,
  closeLabel,
}: {
  text: string;
  trace?: ToolCallEntry[];
  onClose: () => void;
  closeLabel: string;
}) {
  const panelRef = useRef<HTMLDivElement | null>(null);
  const closeButtonRef = useRef<HTMLButtonElement | null>(null);

  // Move focus into the dialog on open, instead of leaving it on whatever the
  // browser defaults to (or nowhere, for a screen reader user).
  useEffect(() => {
    closeButtonRef.current?.focus();
  }, []);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onClose();
        return;
      }
      // Minimal Tab focus trap: wrap Tab from the last focusable element back
      // to the first, and Shift+Tab from the first back to the last, so
      // keyboard focus can't leak out to the page behind the overlay.
      if (e.key === "Tab" && panelRef.current) {
        const focusable = panelRef.current.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])'
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
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 sm:p-8"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="expanded-reply-title"
        className="flex max-h-full w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-border bg-background shadow-2xl"
      >
        <div className="flex shrink-0 items-center justify-between border-b border-border px-5 py-3">
          <span id="expanded-reply-title" className="text-sm font-semibold text-foreground">
            {closeLabel}
          </span>
          <Button
            type="button"
            ref={closeButtonRef}
            variant="ghost"
            size="icon"
            onClick={onClose}
            aria-label={closeLabel}
            title={closeLabel}
          >
            <X size={16} />
          </Button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4 text-base leading-relaxed text-foreground">
          <MediaCard text={text} trace={trace} />
        </div>
      </div>
    </div>
  );
}
