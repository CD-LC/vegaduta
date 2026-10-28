"use client";

// One self-contained drop-in: a topbar toggle plus a floating, draggable
// on-screen keyboard (portal over everything) that types into whichever
// input has focus. The board's script follows the selected locale — pick a
// language in the switcher and the matching keyboard opens itself.
//
// Loosely coupled by design: pages and forms never touch layout data. The
// whole feature is this component, the lib/keyboard data layer, and the one
// mount site next to <LanguageSwitcher />.

import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import {
  ArrowBigUp,
  ArrowLeft,
  CornerDownLeft,
  GripHorizontal,
  Keyboard as KeyboardIcon,
  Loader2,
  X,
} from "lucide-react";
import { useLocale, useTranslations } from "../../lib/i18n";
import { SUPPORTED_LOCALES } from "../../lib/i18n-config";
import { keyboardLayoutFor, loadKeyboardLayout, type KeyboardLayoutData } from "../../lib/keyboard/layouts";
import { backspaceAtCaret, dispatchKey, insertAtCaret, keyboardTarget } from "../../lib/keyboard/insert";

const SPECIAL = new Set(["bksp", "tab", "lock", "enter", "shift", "space"]);
const PANEL_W = 640;
const PANEL_H = 340;
const GREET_KEY = (locale: string) => `vd.onscreenKeyboard.greeted.${locale}`;

function keyClasses(extra = ""): string {
  return `flex h-10 shrink-0 items-center justify-center rounded-md border border-[var(--surface-border)] text-sm text-[var(--text-primary)] shadow-sm transition-colors select-none ${extra}`;
}

function LayoutRows({
  rows,
  shifted,
  onToken,
}: {
  rows: string[] | undefined;
  shifted: boolean;
  onToken: (token: string) => void;
}) {
  if (!rows) return null;
  return (
    <div className="flex flex-col gap-1.5 overflow-x-auto pb-0.5">
      {rows.map((row, i) => (
        <div key={i} className="flex w-max gap-1.5">
          {row.split(" ").map((key, j) => {
            const token = key.replace(/^\{(.+)\}$/, "$1");
            if (token === key) {
              // Literal key (possibly multi-char like ".com").
              return (
                <button
                  key={j}
                  type="button"
                  tabIndex={-1}
                  onPointerDown={(e) => e.preventDefault()}
                  onClick={() => onToken(key)}
                  className={keyClasses(
                    `${key.length > 1 ? "w-12 text-xs" : "w-10"} bg-[var(--surface-raised)] hover:bg-[var(--surface-hover)] active:bg-primary/25`
                  )}
                >
                  {key}
                </button>
              );
            }
            if (!SPECIAL.has(token)) return null;
            const activeShift = shifted && (token === "shift" || token === "lock");
            const size =
              token === "space"
                ? "w-72"
                : token === "enter" || token === "bksp"
                  ? "w-16"
                  : token === "tab" || token === "lock"
                    ? "w-14"
                    : "w-14";
            return (
              <button
                key={j}
                type="button"
                tabIndex={-1}
                aria-label={token}
                onPointerDown={(e) => e.preventDefault()}
                onClick={() => onToken(`{${token}}`)}
                className={keyClasses(
                  `${size} ${activeShift ? "bg-primary/25 text-primary" : "bg-[var(--surface-card-alt,var(--surface-raised))]"} hover:bg-[var(--surface-hover)] active:bg-primary/25`
                )}
              >
                {token === "bksp" && <ArrowLeft className="size-4" aria-hidden="true" />}
                {token === "enter" && <CornerDownLeft className="size-4" aria-hidden="true" />}
                {(token === "shift" || token === "lock") && <ArrowBigUp className="size-4" aria-hidden="true" />}
                {token === "space" && <span className="text-xs text-[var(--text-muted)]">space</span>}
                {token === "tab" && <span className="text-xs">⇥</span>}
              </button>
            );
          })}
        </div>
      ))}
    </div>
  );
}

export default function LanguageKeyboardButton() {
  const t = useTranslations("keyboard");
  const locale = useLocale();
  const [open, setOpen] = useState(false);
  const [layout, setLayout] = useState<KeyboardLayoutData | null>(null);
  const [loading, setLoading] = useState(false);
  const [shifted, setShifted] = useState(false);
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);
  const available = keyboardLayoutFor(locale) !== null;
  const localeLabel = SUPPORTED_LOCALES.find((l) => l.code === locale)?.label;
  const requestRef = useRef(0);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<{ dx: number; dy: number } | null>(null);

  const openBoard = useCallback(() => {
    setPos((p) => {
      if (p !== null) return p; // keep where the person last put it
      const w = typeof window === "undefined" ? 1280 : window.innerWidth;
      const h = typeof window === "undefined" ? 800 : window.innerHeight;
      return { x: Math.max(8, Math.round(w / 2 - PANEL_W / 2)), y: Math.max(8, h - PANEL_H - 24) };
    });
    setOpen(true);
  }, []);

  // Out of the box: picking a language opens its keyboard — once on first
  // sight of a layout locale (per browser), and again on every later
  // language switch, since "I selected a language" is the ask.
  const prevLocale = useRef<string | null>(null);
  useEffect(() => {
    if (!available) {
      setOpen(false);
      prevLocale.current = locale;
      return;
    }
    if (prevLocale.current === null) {
      let seen = true;
      try {
        seen = localStorage.getItem(GREET_KEY(locale)) === "1";
        localStorage.setItem(GREET_KEY(locale), "1");
      } catch {
        // Storage unavailable (private mode) — treat as seen so the board
        // doesn't pop on every navigation.
      }
      if (!seen) openBoard();
    } else if (prevLocale.current !== locale) {
      openBoard();
    }
    prevLocale.current = locale;
  }, [available, locale, openBoard]);

  // Load (cached) whenever the board opens or the locale changes while open.
  useEffect(() => {
    if (!open) return;
    const ticket = ++requestRef.current;
    setLoading(true);
    void loadKeyboardLayout(locale).then((data) => {
      if (requestRef.current === ticket) {
        setLayout(data);
        setLoading(false);
        setShifted(false);
      }
    });
  }, [open, locale]);

  // Escape closes the board.
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  // Drag the board by its header; clamped inside the viewport.
  const onDragStart = (e: React.PointerEvent<HTMLDivElement>) => {
    const panel = panelRef.current;
    if (!panel) return;
    const rect = panel.getBoundingClientRect();
    dragRef.current = { dx: e.clientX - rect.left, dy: e.clientY - rect.top };
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onDragMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    const panel = panelRef.current;
    if (!drag || !panel) return;
    const rect = panel.getBoundingClientRect();
    setPos({
      x: Math.min(Math.max(8, e.clientX - drag.dx), Math.max(8, window.innerWidth - rect.width - 8)),
      y: Math.min(Math.max(8, e.clientY - drag.dy), Math.max(8, window.innerHeight - rect.height - 8)),
    });
  };
  const onDragEnd = () => {
    dragRef.current = null;
  };

  const press = (token: string) => {
    const el = keyboardTarget();
    if (token === "{shift}" || token === "{lock}") {
      setShifted((s) => !s);
      return;
    }
    if (!el) return;
    if (token === "{bksp}") backspaceAtCaret(el);
    else if (token === "{space}") insertAtCaret(el, " ");
    else if (token === "{tab}") insertAtCaret(el, "\t");
    // Enter defers to the input's own keydown semantics (send-on-Enter
    // composers preventDefault it); an unhandled textarea gets a newline.
    else if (token === "{enter}") {
      if (!dispatchKey(el, "Enter") && el instanceof HTMLTextAreaElement) insertAtCaret(el, "\n");
    } else {
      insertAtCaret(el, token);
      if (shifted) setShifted(false); // one-shot shift, like a physical board
    }
  };

  return (
    <>
      <button
        type="button"
        onClick={() => (open ? setOpen(false) : openBoard())}
        disabled={!available}
        title={t(available ? "toggle" : "unavailable")}
        aria-label={t(available ? "toggle" : "unavailable")}
        aria-pressed={open}
        className={`flex size-7 items-center justify-center rounded border-0 p-0 shadow-none transition-colors ${
          !available
            ? "cursor-not-allowed opacity-40 text-[var(--text-muted)]"
            : open
              ? "bg-primary/20 text-primary"
              : "bg-transparent text-[var(--text-muted)] hover:bg-transparent hover:text-[var(--text-primary)]"
        }`}
      >
        <KeyboardIcon className="size-4" aria-hidden="true" />
      </button>

      {open &&
        pos &&
        createPortal(
          <div
            ref={panelRef}
            role="dialog"
            aria-label={t("title")}
            style={{ left: pos.x, top: pos.y }}
            className="fixed z-[80] flex w-max max-w-[96vw] flex-col gap-2 rounded-xl border border-[var(--surface-border)] bg-[var(--surface-card-alt,var(--surface-raised))] p-2.5 shadow-2xl"
          >
            {/* Drag handle — pointer-captured so a fast drag can't drop off. */}
            <div
              onPointerDown={onDragStart}
              onPointerMove={onDragMove}
              onPointerUp={onDragEnd}
              onPointerCancel={onDragEnd}
              className="flex cursor-move touch-none items-center justify-between gap-3 rounded-lg bg-[var(--surface-raised)] px-2 py-1.5 select-none"
            >
              <span className="flex items-center gap-2 text-xs font-medium text-[var(--text-muted)]">
                <GripHorizontal className="size-3.5" aria-hidden="true" />
                {localeLabel ? `${t("title")} — ${localeLabel}` : t("title")}
              </span>
              <button
                type="button"
                onClick={() => setOpen(false)}
                aria-label={t("close")}
                className="flex size-6 items-center justify-center rounded-md border-0 bg-transparent p-0 text-[var(--text-muted)] shadow-none transition-colors hover:bg-[var(--surface-hover)] hover:text-[var(--text-primary)]"
              >
                <X className="size-3.5" aria-hidden="true" />
              </button>
            </div>
            {loading ? (
              <div className="flex h-48 w-[560px] items-center justify-center text-[var(--text-muted)]">
                <Loader2 className="size-5 animate-spin" aria-hidden="true" />
              </div>
            ) : (
              <LayoutRows
                rows={shifted ? (layout?.layout.shift ?? layout?.layout.default) : layout?.layout.default}
                shifted={shifted}
                onToken={press}
              />
            )}
          </div>,
          document.body
        )}
    </>
  );
}
