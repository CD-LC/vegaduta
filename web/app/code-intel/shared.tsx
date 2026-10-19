// @ts-nocheck
"use client";

import {
  cloneElement,
  isValidElement,
  useId,
} from "react";

import { Label } from "../components/ui/label";

// Shared class strings (token-driven, theme-reactive) — one place so every
// code block / hit / info panel reads identically across the six tabs.
export const CODE_CLS =
  "m-0 overflow-auto whitespace-pre-wrap break-words rounded-lg border border-border bg-muted/50 p-3 font-mono text-xs leading-relaxed text-foreground";
export const HIT_CLS = "mb-3 rounded-xl border border-border bg-card p-4 shadow-sm";
export const INFO_CLS =
  "mb-4 rounded-lg border border-border bg-muted/40 px-4 py-3 text-xs leading-relaxed text-muted-foreground";
export const HINT_CLS =
  "mt-2.5 rounded-md border border-[color-mix(in_srgb,var(--state-warning)_32%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_12%,transparent)] px-3 py-2 text-xs leading-relaxed text-[var(--state-warning)]";

// Shadcn-style switch: a token-driven track + knob. `on` uses the platform
// primary (matches the rest of the app) instead of the old inline green.
export function Toggle({ label, on, disabled, onChange }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      onClick={() => onChange(!on)}
      disabled={disabled}
      className="inline-flex items-center gap-2.5 text-sm text-muted-foreground outline-none transition-opacity disabled:opacity-50"
    >
      <span
        className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full border transition-colors ${
          on ? "border-primary/40 bg-primary" : "border-border bg-secondary"
        }`}
      >
        <span
          className={`inline-block size-3.5 rounded-full bg-background shadow-sm transition-transform ${
            on ? "translate-x-4" : "translate-x-0.5"
          }`}
        />
      </span>
      <span className={on ? "font-medium text-foreground" : ""}>{label}</span>
    </button>
  );
}

export function Field({ label, children }) {
  const generatedId = useId();
  const fieldId = (isValidElement(children) && children.props.id) || generatedId;
  const child = isValidElement(children) ? cloneElement(children, { id: fieldId }) : children;
  return (
    <div className="mb-4 space-y-1.5">
      <Label htmlFor={fieldId} className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {label}
      </Label>
      {child}
    </div>
  );
}
