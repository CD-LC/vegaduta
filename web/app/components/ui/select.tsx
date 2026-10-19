"use client";

// Select: accessible single-choice listbox that replaces native <select>
// (forbidden in console code by eslint; see native-controls-allowlist.txt).
//
// Usage:
//   <Select aria-label="Channel" value={v} onValueChange={setV}
//           options={[{ value: "a", label: "Alpha" }, { value: "b", label: "Beta", disabled: true }]}
//           placeholder="Pick one" />
// Keyboard: Enter/Space/ArrowDown opens; Arrow keys, Home/End move; type-ahead
// jumps; Enter selects; Escape/Tab closes. Roles: button[aria-haspopup=listbox]
// + listbox/option with aria-selected and aria-activedescendant.

import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { Check, ChevronDown } from "lucide-react";
import { cn } from "../../lib/utils";

export interface SelectOptionItem {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectProps {
  options: SelectOptionItem[];
  value?: string;
  onValueChange?: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  id?: string;
  name?: string;
  className?: string;
  "aria-label"?: string;
  "aria-labelledby"?: string;
}

function Select({
  options,
  value,
  onValueChange,
  placeholder = "Select…",
  disabled,
  id,
  name,
  className,
  ...aria
}: SelectProps) {
  const uid = useId();
  const listId = `${uid}-list`;
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const rootRef = useRef<HTMLDivElement>(null);
  const typed = useRef({ text: "", at: 0 });
  const selected = options.find((o) => o.value === value);

  useEffect(() => {
    if (!open) return undefined;
    const onDown = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  const enabled = (i: number) => i >= 0 && i < options.length && !options[i].disabled;
  const step = (from: number, dir: 1 | -1) => {
    for (let i = from + dir; i >= 0 && i < options.length; i += dir) if (enabled(i)) return i;
    return from;
  };
  const edge = (dir: 1 | -1) => step(dir === 1 ? -1 : options.length, dir);

  const openList = () => {
    const idx = options.findIndex((o) => o.value === value && !o.disabled);
    setActive(idx >= 0 ? idx : edge(1));
    setOpen(true);
  };
  const choose = (i: number) => {
    if (!enabled(i)) return;
    onValueChange?.(options[i].value);
    setOpen(false);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;
    const k = e.key;
    if (!open) {
      if (k === "Enter" || k === " " || k === "ArrowDown" || k === "ArrowUp") {
        e.preventDefault();
        openList();
      }
      return;
    }
    if (k === "ArrowDown") { e.preventDefault(); setActive((a) => step(a, 1)); }
    else if (k === "ArrowUp") { e.preventDefault(); setActive((a) => step(a, -1)); }
    else if (k === "Home") { e.preventDefault(); setActive(edge(1)); }
    else if (k === "End") { e.preventDefault(); setActive(edge(-1)); }
    else if (k === "Enter" || k === " ") { e.preventDefault(); choose(active); }
    else if (k === "Escape") { e.preventDefault(); setOpen(false); }
    else if (k === "Tab") setOpen(false);
    else if (k.length === 1 && !e.ctrlKey && !e.metaKey) {
      const now = Date.now();
      typed.current.text = (now - typed.current.at > 600 ? "" : typed.current.text) + k.toLowerCase();
      typed.current.at = now;
      const hit = options.findIndex((o) => !o.disabled && o.label.toLowerCase().startsWith(typed.current.text));
      if (hit >= 0) setActive(hit);
    }
  };

  return (
    <div ref={rootRef} className={cn("relative w-full", className)} data-slot="select">
      <button
        type="button"
        id={id}
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-activedescendant={open && active >= 0 ? `${uid}-opt-${active}` : undefined}
        disabled={disabled}
        onClick={() => (open ? setOpen(false) : openList())}
        onKeyDown={onKeyDown}
        className={cn(
          "flex h-10 w-full items-center justify-between gap-2 rounded-md border border-control bg-secondary/40 px-3 py-2 text-left text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow]",
          "focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50"
        )}
        {...aria}
      >
        <span className={cn("truncate", !selected && "text-muted-foreground")}>{selected ? selected.label : placeholder}</span>
        <ChevronDown aria-hidden="true" className="size-4 shrink-0 opacity-60" />
      </button>
      {name ? <input type="hidden" name={name} value={value ?? ""} /> : null}
      {open ? (
        <ul
          id={listId}
          role="listbox"
          className="absolute left-0 right-0 z-50 mt-1 max-h-60 overflow-auto rounded-md border border-border bg-card p-1 text-sm text-foreground shadow-lg animate-[popover-in_0.12s_ease-out_both]"
        >
          {options.map((o, i) => (
            // Keyboard operation lives on the combobox trigger (aria-activedescendant).
            // eslint-disable-next-line jsx-a11y/click-events-have-key-events
            <li
              key={o.value}
              id={`${uid}-opt-${i}`}
              role="option"
              aria-selected={o.value === value}
              aria-disabled={o.disabled || undefined}
              data-active={i === active || undefined}
              onMouseEnter={() => enabled(i) && setActive(i)}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => choose(i)}
              className={cn(
                "flex cursor-pointer items-center justify-between gap-2 rounded-sm px-2 py-1.5",
                i === active && "bg-accent text-accent-foreground",
                o.disabled && "cursor-not-allowed opacity-50"
              )}
            >
              <span className="truncate">{o.label}</span>
              {o.value === value ? <Check aria-hidden="true" className="size-4" /> : null}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

export { Select };
