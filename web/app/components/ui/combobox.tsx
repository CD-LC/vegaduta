"use client";

// Combobox: searchable single-choice picker, async-friendly.
//
// Usage (local filter):
//   <Combobox aria-label="Agent" options={agents} value={id} onValueChange={setId} />
// Usage (async): pass `onSearch` (called on every query change; you replace
//   `options` with server results) and `loading`; local filtering is then off.
// Keyboard: type to filter, ArrowUp/Down move, Enter selects, Escape closes.
// Roles: input[role=combobox] + listbox/option, aria-activedescendant.

import { useId, useMemo, useState } from "react";
import { cn } from "../../lib/utils";

export interface ComboboxOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface ComboboxProps {
  options: ComboboxOption[];
  value?: string;
  onValueChange?: (value: string) => void;
  onSearch?: (query: string) => void;
  loading?: boolean;
  placeholder?: string;
  emptyText?: string;
  loadingText?: string;
  disabled?: boolean;
  id?: string;
  className?: string;
  "aria-label"?: string;
  "aria-labelledby"?: string;
}

function Combobox({
  options,
  value,
  onValueChange,
  onSearch,
  loading,
  placeholder = "Search…",
  emptyText = "No results",
  loadingText = "Loading…",
  disabled,
  id,
  className,
  ...aria
}: ComboboxProps) {
  const uid = useId();
  const listId = `${uid}-list`;
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const selected = options.find((o) => o.value === value);

  const shown = useMemo(() => {
    if (onSearch) return options;
    const q = query.trim().toLowerCase();
    return q ? options.filter((o) => o.label.toLowerCase().includes(q)) : options;
  }, [options, query, onSearch]);

  const choose = (o: ComboboxOption | undefined) => {
    if (!o || o.disabled) return;
    onValueChange?.(o.value);
    setQuery("");
    setOpen(false);
  };
  const step = (dir: 1 | -1) => {
    for (let i = active + dir; i >= 0 && i < shown.length; i += dir) {
      if (!shown[i].disabled) return setActive(i);
    }
  };

  return (
    <div className={cn("relative w-full", className)} data-slot="combobox">
      <input
        id={id}
        role="combobox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-autocomplete="list"
        aria-activedescendant={open && shown[active] ? `${uid}-opt-${active}` : undefined}
        disabled={disabled}
        autoComplete="off"
        placeholder={selected && !open ? selected.label : placeholder}
        value={open ? query : (selected?.label ?? "")}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onChange={(e) => {
          setQuery(e.target.value);
          setActive(0);
          setOpen(true);
          onSearch?.(e.target.value);
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            if (!open) setOpen(true);
            else step(1);
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            step(-1);
          } else if (e.key === "Enter" && open) {
            e.preventDefault();
            choose(shown[active]);
          } else if (e.key === "Escape") {
            setOpen(false);
            setQuery("");
          }
        }}
        className="flex h-10 w-full min-w-0 rounded-md border border-control bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50"
        {...aria}
      />
      {open ? (
        <ul
          id={listId}
          role="listbox"
          className="absolute left-0 right-0 z-50 mt-1 max-h-60 overflow-auto rounded-md border border-border bg-popover p-1 text-sm text-foreground shadow-lg"
        >
          {loading ? (
            <li role="presentation" className="px-2 py-1.5 text-muted-foreground">
              {loadingText}
            </li>
          ) : shown.length === 0 ? (
            <li role="presentation" className="px-2 py-1.5 text-muted-foreground">
              {emptyText}
            </li>
          ) : (
            shown.map((o, i) => (
              <li
                key={o.value}
                id={`${uid}-opt-${i}`}
                role="option"
                aria-selected={o.value === value}
                aria-disabled={o.disabled || undefined}
                onMouseDown={(e) => {
                  e.preventDefault();
                  choose(o);
                }}
                onMouseEnter={() => !o.disabled && setActive(i)}
                className={cn(
                  "cursor-pointer truncate rounded-sm px-2 py-1.5",
                  i === active && "bg-accent text-accent-foreground",
                  o.disabled && "cursor-not-allowed opacity-50"
                )}
              >
                {o.label}
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  );
}

export { Combobox };
