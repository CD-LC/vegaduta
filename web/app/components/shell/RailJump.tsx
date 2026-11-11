"use client";

// The "Jump to..." entry that opens the command palette. Lifted out of AppShell.tsx unchanged.

import { Search } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

export default function RailJump({
  hubMode,
  shortcutLabel,
  railRowCollapse,
  railLabelCls,
  onOpen,
}: {
  hubMode: boolean;
  shortcutLabel: string;
  railRowCollapse: string;
  railLabelCls: string;
  onOpen: () => void;
}) {
  const tNav = useTranslations("nav");
  return hubMode ? (
    // HUB RAIL: the glyph column below centres 44px tiles, so the
    // search entry becomes one of those tiles too - the left-padded
    // text-row layout put its icon a few px off the section glyphs.
    <div className="flex justify-center pt-1">
      <button
        type="button"
        onClick={() => onOpen()}
        aria-label={tNav("railJump")}
        title={`${tNav("railJump")} (${shortcutLabel})`}
        data-tour="nav-jump"
        className="group relative flex size-11 items-center justify-center rounded-xl text-[var(--text-secondary)] transition-colors hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)]"
      >
        <Search
          aria-hidden="true"
          strokeWidth={2}
          className="size-5 transition-transform duration-200 group-hover:-rotate-12 group-hover:scale-110"
        />
      </button>
    </div>
  ) : (
    <button
      type="button"
      onClick={() => onOpen()}
      data-tour="nav-jump"
      className={`group relative mb-1.5 flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors hover:bg-[var(--surface-raised)] ${railRowCollapse}`}
      style={{ color: "var(--text-faint)" }}
    >
      {/* A little "looking around" wiggle on hover - the one icon in
                  the rail that's explicitly an invitation to search, so it
                  gets the most personality. */}
      <Search
        aria-hidden="true"
        strokeWidth={2.1}
        className="size-5 shrink-0 transition-transform duration-200 group-hover:-rotate-12 group-hover:scale-110"
      />
      <span className={`truncate ${railLabelCls}`}>{tNav("railJump")}</span>
      <kbd
        className={`ms-auto shrink-0 rounded border border-[var(--surface-border-strong)] px-1.5 py-0.5 font-mono text-[10px] ${railLabelCls}`}
        style={{ background: "var(--surface-hover)" }}
      >
        {shortcutLabel}
      </kbd>
    </button>
  );
}
