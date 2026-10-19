"use client";

// ⌘K / Ctrl+K command palette, the IDE way to reach everything on the page without the mouse:
// start a thread, switch how the box sends, jump to tuning, workflows or the app builder, or
// drop a ready-made prompt into the box. The page supplies the items; this owns filtering and
// keyboard navigation (arrows, Enter, Esc).

import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Search } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";

export interface PaletteItem {
  id: string;
  group: string;
  label: string;
  hint?: string;
  icon?: ReactNode;
  run: () => void;
}

export default function AssistantPalette({ items, onClose }: { items: PaletteItem[]; onClose: () => void }) {
  const t = useTranslations("assistant");
  const [query, setQuery] = useState("");
  const [cursor, setCursor] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? items.filter((i) => `${i.label} ${i.hint ?? ""} ${i.group}`.toLowerCase().includes(q)) : items;
  }, [items, query]);

  useEffect(() => inputRef.current?.focus(), []);
  useEffect(() => setCursor(0), [query]);

  function choose(item: PaletteItem | undefined) {
    if (!item) return;
    onClose();
    item.run();
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === "Escape") {
      e.preventDefault();
      onClose();
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      setCursor((c) => Math.min(c + 1, shown.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setCursor((c) => Math.max(c - 1, 0));
    } else if (e.key === "Enter" && !e.isComposing) {
      e.preventDefault();
      choose(shown[cursor]);
    }
  }

  const keyRef = useRef(onKeyDown);
  keyRef.current = onKeyDown;
  useEffect(() => {
    const h = (e: KeyboardEvent) => keyRef.current(e);
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);

  let lastGroup = "";
  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center px-4 pt-[12vh]"
      data-testid="assistant-palette"
    >
      <button
        type="button"
        tabIndex={-1}
        aria-label={t("paletteTitle")}
        onClick={onClose}
        className="absolute inset-0 cursor-default rounded-none bg-background/60 backdrop-blur-sm"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={t("paletteTitle")}
        className="relative w-full max-w-lg overflow-hidden rounded-xl border border-border bg-card shadow-2xl"
      >
        <div className="flex items-center gap-2 border-b border-border px-3">
          <Search size={15} className="text-muted-foreground" aria-hidden="true" />
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("palettePlaceholder")}
            aria-label={t("palettePlaceholder")}
            className="h-11 flex-1 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
          />
          <kbd className="rounded border border-border bg-secondary/60 px-1 font-mono text-[10px] text-muted-foreground">esc</kbd>
        </div>
        <ul role="listbox" className="max-h-80 overflow-y-auto p-1">
          {shown.length === 0 && <li className="px-3 py-6 text-center text-sm text-muted-foreground">{t("paletteEmpty")}</li>}
          {shown.map((item, i) => {
            const header = item.group !== lastGroup;
            lastGroup = item.group;
            return (
              <li key={item.id} role="presentation">
                {header && (
                  <p className="px-2 pb-1 pt-2 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">{item.group}</p>
                )}
                <button
                  type="button"
                  role="option"
                  aria-selected={i === cursor}
                  onMouseEnter={() => setCursor(i)}
                  onClick={() => choose(item)}
                  className={cn(
                    "flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left text-sm",
                    i === cursor ? "bg-secondary text-foreground" : "text-foreground/90"
                  )}
                >
                  <span className="grid size-5 shrink-0 place-items-center text-muted-foreground">{item.icon}</span>
                  <span className="flex-1 truncate">{item.label}</span>
                  {item.hint && <span className="shrink-0 truncate text-xs text-muted-foreground">{item.hint}</span>}
                </button>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
