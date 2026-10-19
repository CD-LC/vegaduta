"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { X } from "lucide-react";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";

/**
 * A centred sheet over the Assistant for the camera and the call: dimmed backdrop, Escape and a click
 * outside close it, focus moves in and returns to what opened it. Small on purpose: the console has no
 * dialog primitive of its own, and these two surfaces need nothing beyond this.
 */
export default function AssistantOverlay({
  title,
  closeLabel,
  onClose,
  children,
  className,
}: {
  title: string;
  closeLabel: string;
  onClose: () => void;
  children: ReactNode;
  className?: string;
}) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCloseRef.current();
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      opener?.focus?.();
    };
  }, []);

  return (
    <div
      role="presentation"
      // z tier must stay BELOW the shared floating-panel tier (z-1000):
      // Select/popover/tooltip menus portal to document.body at z-1000, so a
      // modal at 1010+ renders every dropdown inside it behind the sheet -
      // the call panel's voice/pick menus in this overlay were invisible at
      // z-[1010]. z-[900] is still above all page layers (dock z-600,
      // canvas z-40/50); a popover opened before the sheet appears closes
      // itself on the next mousedown anyway.
      className="fixed inset-0 z-[900] flex items-end justify-center bg-black/60 p-0 backdrop-blur-sm sm:items-center sm:p-6"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={cn(
          "flex max-h-[100dvh] w-full flex-col overflow-hidden bg-background shadow-2xl sm:max-w-md sm:rounded-3xl sm:border sm:border-border",
          "rounded-t-3xl border-t border-border",
          className
        )}
      >
        <div className="flex items-center gap-2 px-4 pb-1 pt-3">
          <h2 className="flex-1 truncate text-sm font-semibold text-foreground">{title}</h2>
          <Button ref={closeRef} type="button" size="icon" variant="ghost" className="size-8" aria-label={closeLabel} onClick={onClose}>
            <X size={16} aria-hidden="true" />
          </Button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
      </div>
    </div>
  );
}
