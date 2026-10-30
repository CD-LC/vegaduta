"use client";

import { useId, useRef } from "react";
import { cn } from "../lib/utils";
import { useTranslations } from "../lib/i18n";

// Deliberately NOT a CodeMirror/Monaco/Prism dependency. This console's
// "no dependency until it earns its keep" rule (see app/lib/api.ts's header
// comment for the same posture on HTTP clients) plus this repo's hard
// "don't run npm install in a scoped session" concurrency rule mean a real
// editor library isn't on the table for a single form field. What a person
// writing a 30-line tool actually needs from an editor is: a monospace
// grid, line numbers to match a compiler error like "Tool.java:7", and a
// Tab key that indents instead of tabbing away to the next form control.
// That is all this is.
//
// Two details that are load-bearing rather than cosmetic:
//   * wrap="off" - soft-wrapped lines would silently desynchronise the
//     gutter (one logical line rendering as three visual rows), so the line
//     numbers would point at the wrong code, which is worse than no gutter
//     at all. Horizontal scroll instead.
//   * The gutter and the textarea MUST share a font-size/line-height pair
//     (text-xs / leading-5 below). If those drift apart the numbers slide
//     out of alignment a little further with every line.
const INDENT = "  ";

export default function CodeEditorTextarea({
  value,
  onChange,
  disabled = false,
  rows = 18,
  id,
  placeholder,
  "aria-label": ariaLabel,
  className,
}: {
  value: string;
  onChange: (next: string) => void;
  disabled?: boolean;
  rows?: number;
  id?: string;
  placeholder?: string;
  "aria-label"?: string;
  className?: string;
}) {
    const tHC = useTranslations("hcComponentsCodeEditorTextarea");
  const gutterRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const hintId = useId();

  const lineCount = Math.max(1, value.split("\n").length);

  // Move focus to whatever's next in the DOM's focus order, rather than
  // blurring to <body> and restarting keyboard navigation from the top of
  // the page.
  function focusNextElement(current: HTMLElement) {
    const focusableSelector =
      'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';
    const focusable = Array.from(document.querySelectorAll<HTMLElement>(focusableSelector));
    const idx = focusable.indexOf(current);
    if (idx !== -1 && idx + 1 < focusable.length) {
      focusable[idx + 1].focus();
    } else {
      current.blur();
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    // Tab inside a <textarea> normally moves focus to the next control,
    // which makes writing indented code by hand impossible. Escape hatch
    // for keyboard users who genuinely want to leave the field: Escape
    // first, then Tab (below moves focus to whatever's next instead of
    // blurring to <body>).
    if (e.key === "Escape") {
      focusNextElement(e.currentTarget);
      return;
    }
    if (e.key !== "Tab" || e.shiftKey) return;
    e.preventDefault();
    const el = e.currentTarget;
    const { selectionStart, selectionEnd } = el;
    const next = value.slice(0, selectionStart) + INDENT + value.slice(selectionEnd);
    onChange(next);
    // Restore the caret AFTER React has re-rendered with the new value,
    // otherwise the controlled-value write resets it to the end of the text.
    requestAnimationFrame(() => {
      const target = textareaRef.current;
      if (!target) return;
      target.selectionStart = selectionStart + INDENT.length;
      target.selectionEnd = selectionStart + INDENT.length;
    });
  }

  return (
    <div>
      <div
        className={cn(
          "flex overflow-hidden rounded-md border border-border bg-secondary/40 shadow-sm",
          "focus-within:border-ring focus-within:ring-[3px] focus-within:ring-ring/40",
          disabled && "opacity-50",
          className
        )}
      >
        <div
          ref={gutterRef}
          aria-hidden="true"
          className="shrink-0 select-none overflow-hidden border-e border-border bg-secondary/60 py-2 text-end font-mono text-xs leading-5 text-muted-foreground"
          style={{ width: `${String(lineCount).length + 2}ch` }}
        >
          {Array.from({ length: lineCount }, (_, i) => (
            <div key={i} className="px-1.5">
              {i + 1}
            </div>
          ))}
        </div>
        <textarea
          ref={textareaRef}
          id={id}
          rows={rows}
          wrap="off"
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          autoComplete="off"
          disabled={disabled}
          placeholder={placeholder}
          aria-label={ariaLabel}
          aria-describedby={hintId}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          onScroll={(e) => {
            if (gutterRef.current) gutterRef.current.scrollTop = e.currentTarget.scrollTop;
          }}
          className={cn(
            "w-full flex-1 resize-none overflow-auto whitespace-pre bg-transparent px-3 py-2",
            "font-mono text-xs leading-5 text-foreground outline-none",
            "placeholder:text-muted-foreground disabled:cursor-not-allowed"
          )}
        />
      </div>
      <span id={hintId} className="text-xs text-muted-foreground">
        {tHC("s1")}
      </span>
    </div>
  );
}
