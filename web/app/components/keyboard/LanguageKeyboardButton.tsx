"use client";

// One self-contained drop-in: renders a topbar toggle plus a floating
// on-screen keyboard (via portal) that types into whichever input has
// focus. The board's script follows the selected locale — pick a language
// in the switcher and a keyboard in that script is one click away.
//
// Loosely coupled by design: pages and forms never touch layout data. The
// whole feature is this component, the lib/keyboard data layer, and the one
// mount site next to <LanguageSwitcher />.

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ArrowBigUp, ArrowLeft, CornerDownLeft, Keyboard as KeyboardIcon, Loader2, X } from "lucide-react";
import { useLocale, useTranslations } from "../../lib/i18n";
import { keyboardLayoutFor, loadKeyboardLayout, type KeyboardLayoutData } from "../../lib/keyboard/layouts";
import { backspaceAtCaret, dispatchKey, insertAtCaret, keyboardTarget } from "../../lib/keyboard/insert";

const SPECIAL = new Set(["bksp", "tab", "lock", "enter", "shift", "space"]);
const RTL_LOCALES = new Set(["ar", "ur", "ks", "sd"]);

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
    <>
      {rows.map((row, i) => (
        <div key={i} className="flex justify-center gap-1">
          {row.split(" ").map((key, j) => {
            const token = key.replace(/^\{(.+)\}$/, "$1");
            if (token === key) {
              // Literal key (maybe multi-char like ".com").
              return (
                <button
                  key={j}
                  type="button"
                  tabIndex={-1}
                  onPointerDown={(e) => e.preventDefault()}
                  onClick={() => onToken(key)}
                  className={`flex h-10 min-w-0 flex-1 items-center justify-center rounded-md border border-[var(--surface-border)] bg-[var(--surface-raised)] text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:bg-[var(--surface-hover)] ${
                    key.length > 1 ? "px-1 text-xs" : ""
                  }`}
                >
                  {key}
                </button>
              );
            }
            if (!SPECIAL.has(token)) return null;
            const wide = token === "space" || token === "enter" || token === "bksp";
            return (
              <button
                key={j}
                type="button"
                tabIndex={-1}
                aria-label={token}
                onPointerDown={(e) => e.preventDefault()}
                onClick={() => onToken(`{${token}}`)}
                className={`flex h-10 items-center justify-center rounded-md border border-[var(--surface-border)] text-sm text-[var(--text-primary)] shadow-sm transition-colors ${
                  token === "space"
                    ? "flex-[8] bg-[var(--surface-raised)] hover:bg-[var(--surface-hover)]"
                    : `flex-[1.5] ${shifted && (token === "shift" || token === "lock") ? "bg-primary/20 text-primary" : "bg-[var(--surface-sunken,var(--surface-raised))]"} hover:bg-[var(--surface-hover)]`
                } ${wide ? "px-2" : ""}`}
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
    </>
  );
}

export default function LanguageKeyboardButton() {
  const t = useTranslations("keyboard");
  const locale = useLocale();
  const [open, setOpen] = useState(false);
  const [layout, setLayout] = useState<KeyboardLayoutData | null>(null);
  const [loading, setLoading] = useState(false);
  const [shifted, setShifted] = useState(false);
  const available = keyboardLayoutFor(locale) !== null;
  const dir = RTL_LOCALES.has(locale) ? "rtl" : "ltr";
  const requestRef = useRef(0);

  // Load (cached) whenever the board opens or the locale changes while open.
  useEffect(() => {
    if (!open) return;
    const ticket = ++requestRef.current;
    setLoading(true);
    void loadKeyboardLayout(locale).then((data) => {
      if (requestRef.current === ticket) {
        setLayout(data);
        setLoading(false);
      }
    });
  }, [open, locale]);

  // A layout-less locale closes the board rather than showing the wrong one.
  useEffect(() => {
    if (!available) setOpen(false);
  }, [available]);

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
    else if (token === "{enter}") dispatchKey(el, "Enter");
    else {
      insertAtCaret(el, token);
      if (shifted) setShifted(false); // one-shot shift, like a physical board
    }
  };

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
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
        createPortal(
          <div
            role="dialog"
            aria-label={t("title")}
            dir={dir}
            className="fixed inset-x-2 bottom-2 z-50 mx-auto flex max-w-xl flex-col gap-1.5 rounded-xl border border-[var(--surface-border)] bg-[var(--surface-base,var(--surface-raised))] p-3 shadow-2xl backdrop-blur-xl sm:inset-x-4"
          >
            <div className="flex items-center justify-between px-1 pb-0.5">
              <span className="text-xs font-medium text-[var(--text-muted)]">{t("title")}</span>
              <button
                type="button"
                onClick={() => setOpen(false)}
                aria-label={t("close")}
                className="flex size-6 items-center justify-center rounded-md border-0 bg-transparent p-0 text-[var(--text-muted)] shadow-none transition-colors hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)]"
              >
                <X className="size-3.5" aria-hidden="true" />
              </button>
            </div>
            {loading ? (
              <div className="flex h-40 items-center justify-center text-[var(--text-muted)]">
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
