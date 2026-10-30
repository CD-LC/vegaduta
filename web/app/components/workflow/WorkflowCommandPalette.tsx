"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { Search, CornerDownLeft } from "lucide-react";
import { motion, AnimatePresence } from "motion/react";
import { NODE_DEFS, nodeLabelKey, nodeDescriptionKey, categoryLabelKey } from "./nodeDefs";
import { workflowNodeOffered } from "../../lib/personalInstall";
import type { WorkflowNodeDef } from "./nodeDefs";
import { useNodeDefTranslations } from "./useNodeDefTranslations";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import "./workflow-chrome.css";
import { useTranslations } from "../../lib/i18n";

const RECENTS_KEY = "wf-cmd-recents";
const MAX_RECENTS = 5;

function loadRecents(): string[] {
  try {
    const raw = localStorage.getItem(RECENTS_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === "string").slice(0, MAX_RECENTS) : [];
  } catch {
    return [];
  }
}

function saveRecent(type: string) {
  try {
    const next = [type, ...loadRecents().filter((x) => x !== type)].slice(0, MAX_RECENTS);
    localStorage.setItem(RECENTS_KEY, JSON.stringify(next));
  } catch {
    // Storage blocked (private mode etc.): recents are a nicety only.
  }
}

// Lower is better, null = no match. A contiguous substring beats scattered
// letters, and an earlier hit beats a later one.
function fuzzyScore(text: string, q: string): number | null {
  const idx = text.indexOf(q);
  if (idx >= 0) return idx;
  let pos = 0;
  let gaps = 0;
  for (const ch of q) {
    const found = text.indexOf(ch, pos);
    if (found < 0) return null;
    gaps += found - pos;
    pos = found + 1;
  }
  return 100 + gaps;
}

// ⌘K / "+ Add node" command palette — the modern, delightful way to drop a
// node onto the canvas without hunting the left rail (the pattern React Flow's
// own workflow template and every serious node editor — n8n, Zapier, ComfyUI —
// converge on). Purely additive: it just calls back onPick(type), which the
// canvas turns into its existing addNodeByClick(). Type to fuzzy-filter,
// ↑/↓ to move, ↵ to add, Esc to close.
export default function WorkflowCommandPalette({
  open,
  onClose,
  onPick,
}: {
  open: boolean;
  onClose: () => void;
  onPick: (type: string) => void;
}) {
    const tHC = useTranslations("hcComponentsWorkflowWorkflowCommandPalette");
  const t = useNodeDefTranslations();
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const [recents, setRecents] = useState<string[]>([]);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const activeRef = useRef<HTMLButtonElement | null>(null);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  // Tab trap, Escape from anywhere in the palette, focus back to the opener on close (FE-041).
  // Initial focus stays with the input effect below (the input is also the first focusable).
  useDialogFocus(dialogRef, inputRef, onClose, { enabled: open });

  const { items, recentCount } = useMemo(() => {
    const all = NODE_DEFS.filter((d) => d.supported && workflowNodeOffered(d.type));
    const q = query.trim().toLowerCase();
    if (!q) {
      const recentDefs = recents
        .map((type) => all.find((d) => d.type === type))
        .filter((d): d is WorkflowNodeDef => Boolean(d));
      return { items: [...recentDefs, ...all.filter((d) => !recentDefs.includes(d))], recentCount: recentDefs.length };
    }
    const scored: Array<{ d: WorkflowNodeDef; score: number }> = [];
    for (const d of all) {
      const label = fuzzyScore(t(nodeLabelKey(d.type)).toLowerCase(), q);
      const other = Math.min(
        fuzzyScore(t(nodeDescriptionKey(d.type)).toLowerCase(), q) ?? Infinity,
        fuzzyScore(t(categoryLabelKey(d.category)).toLowerCase(), q) ?? Infinity
      );
      // Label hits outrank description/category hits.
      const score = label !== null ? label : other === Infinity ? null : 1000 + other;
      if (score !== null) scored.push({ d, score });
    }
    return { items: scored.sort((x, y) => x.score - y.score).map((x) => x.d), recentCount: 0 };
  }, [query, t, recents]);

  useEffect(() => {
    setActive(0);
  }, [query]);

  useEffect(() => {
    if (!open) return undefined;
    setQuery("");
    setActive(0);
    setRecents(loadRecents());
    const id = setTimeout(() => inputRef.current?.focus(), 20);
    return () => {
      clearTimeout(id);
    };
  }, [open]);

  // Esc closes the palette wherever focus is. The dialog's own handler below
  // only fires while focus is inside it; if focus was taken elsewhere the
  // palette stayed open and the next stray click added a node (bug 6).
  useEffect(() => {
    if (!open) return undefined;
    function onWindowKeyDown(e: globalThis.KeyboardEvent) {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopPropagation();
      onClose();
    }
    window.addEventListener("keydown", onWindowKeyDown, true);
    return () => window.removeEventListener("keydown", onWindowKeyDown, true);
  }, [open, onClose]);

  // Keep the keyboard-selected row scrolled into view.
  useEffect(() => {
    activeRef.current?.scrollIntoView({ block: "nearest" });
  }, [active]);

  function commit(def: WorkflowNodeDef | undefined) {
    if (!def) return;
    saveRecent(def.type);
    onPick(def.type);
    onClose();
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((i) => Math.min(i + 1, items.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      commit(items[active]);
    }
  }

  const showGroups = !query.trim() && recentCount > 0;

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 z-[70] flex items-start justify-center bg-black/50 p-4 pt-[12vh] backdrop-blur-sm"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.14 }}
          onClick={onClose}
        >
          <motion.div
            ref={dialogRef}
            role="dialog"
            aria-modal="true"
            aria-label={tHC("s5")}
            className="w-full max-w-lg overflow-hidden rounded-2xl border border-border bg-popover shadow-2xl"
            initial={{ opacity: 0, scale: 0.97, y: -8 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.97, y: -8 }}
            transition={{ duration: 0.16, ease: [0.16, 1, 0.3, 1] }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2.5 border-b border-border px-4">
              <Search size={16} className="shrink-0 text-muted-foreground" aria-hidden="true" />
              <input
                ref={inputRef}
                role="combobox"
                aria-expanded="true"
                aria-controls="wf-cmd-list"
                aria-autocomplete="list"
                aria-label={tHC("s4")}
                aria-activedescendant={items[active] ? `wf-cmd-opt-${items[active].type}` : undefined}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={onKeyDown}
                placeholder={tHC("s3")}
                className="h-12 w-full bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
              />
              <kbd className="hidden rounded border border-border px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground sm:inline">
                ESC
              </kbd>
            </div>
            <div id="wf-cmd-list" role="listbox" aria-label="Nodes" className="max-h-[52vh] overflow-y-auto p-1.5">
              {items.length === 0 && (
                <div role="status" className="px-3 py-8 text-center text-sm text-muted-foreground">
                  {tHC("s2")}{query}&rdquo;.
                </div>
              )}
              {items.map((def, idx) => {
                const Icon = def.icon;
                const isActive = idx === active;
                return (
                  <div key={def.type} role="presentation">
                    {showGroups && idx === 0 && (
                      <div className="wf-cmd-group" aria-hidden="true">
                        Recent
                      </div>
                    )}
                    {showGroups && idx === recentCount && (
                      <div className="wf-cmd-group" aria-hidden="true">
                        {tHC("s1")}
                      </div>
                    )}
                    <button
                      id={`wf-cmd-opt-${def.type}`}
                      role="option"
                      aria-selected={isActive}
                      tabIndex={-1}
                      ref={isActive ? activeRef : null}
                      type="button"
                      onMouseEnter={() => setActive(idx)}
                      onClick={() => commit(def)}
                      className={`flex w-full items-center gap-3 rounded-lg border-0 p-0 px-2.5 py-2 text-start shadow-none transition-colors ${
                        isActive ? "bg-accent" : "bg-transparent hover:bg-accent/60"
                      }`}
                    >
                      <span
                        className="flex size-8 shrink-0 items-center justify-center rounded-md shadow-sm ring-1 ring-black/5"
                        style={{ background: def.color }}
                      >
                        <Icon size={15} className="text-white" strokeWidth={2.2} aria-hidden="true" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium text-foreground">
                          {t(nodeLabelKey(def.type))}
                        </span>
                        <span className="block truncate text-xs text-muted-foreground">
                          {t(nodeDescriptionKey(def.type))}
                        </span>
                      </span>
                      <span className="shrink-0 rounded border border-border px-2 py-0.5 font-mono text-[9px] uppercase tracking-wide text-muted-foreground">
                        {t(categoryLabelKey(def.category))}
                      </span>
                      {isActive && (
                        <CornerDownLeft size={13} className="shrink-0 text-muted-foreground" aria-hidden="true" />
                      )}
                    </button>
                  </div>
                );
              })}
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
