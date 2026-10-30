"use client";

import { useCallback, useEffect, useId, useRef, useState, type CSSProperties, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import { ChevronDown, Check } from "lucide-react";
import { motion, AnimatePresence } from "motion/react";
import { useTranslations } from "../../lib/i18n";

// Straight copy of web/app/components/Select.js (self-contained - only
// depends on motion/lucide-react and the .select-* CSS classes already in
// globals.css, both present here). See that file's own comment for the full
// design rationale.
//
// Props deliberately kept loose (any), mirroring web/app/components/Select.tsx:
// every prop is optional so existing call sites (which pass only the subset
// they need) type-check unchanged - a faithful .js -> .tsx conversion with no
// behavior change.
export interface SelectProps {
  value?: any;
  onChange?: (e: any) => void;
  options?: any[];
  placeholder?: any;
  disabled?: boolean;
  id?: any;
  name?: any;
  "data-testid"?: any;
  className?: string;
  searchable?: boolean;
  allowCustom?: boolean;
}

export default function Select({
  value,
  onChange,
  options,
  placeholder = "Select…",
  disabled = false,
  id,
  name,
  "data-testid": testId,
  className = "",
  searchable = false,
  allowCustom = false,
}: SelectProps) {
    const tHC = useTranslations("hcPlatformadminComponentsSelect");
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [query, setQuery] = useState("");
  const ref = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const searchRef = useRef<HTMLInputElement | null>(null);

  // Same portal fix as web/app/components/Select.tsx (2026-09-25): the
  // absolute-in-root popover got clipped by scrollable ancestors and buried
  // under higher stacking contexts. Rendered in document.body at the shared
  // popover tier, re-anchored on scroll (capture - inner scrollers)/resize,
  // flipping up when there's more room above.
  const [popStyle, setPopStyle] = useState<CSSProperties | null>(null);
  const [flipUp, setFlipUp] = useState(false);
  // Standard combobox/listbox wiring (independent of the caller's own `id`,
  // which labels the trigger via htmlFor) - lets the trigger announce which
  // popover it owns and which option is currently highlighted, and lets
  // assistive tech resolve each option to a stable id.
  const listboxId = useId();
  const optionId = (idx: number) => `${listboxId}-option-${idx}`;

  const updatePosition = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const trigger = (el.querySelector(".select-trigger") ?? el) as HTMLElement;
    const r = trigger.getBoundingClientRect();
    const gap = 6;
    const margin = 8;
    const maxH = Math.min(256, window.innerHeight - margin * 2);
    const below = window.innerHeight - r.bottom - gap - margin;
    const above = r.top - gap - margin;
    const up = below < Math.min(maxH, 180) && above > below;
    const width = Math.max(r.width, 160);
    const left = Math.max(margin, Math.min(r.left, window.innerWidth - width - margin));
    setFlipUp(up);
    setPopStyle(
      up
        ? { position: "fixed", left, width, bottom: window.innerHeight - r.top + gap,
            maxHeight: Math.max(120, Math.min(maxH, above)) }
        : { position: "fixed", left, width, top: r.bottom + gap,
            maxHeight: Math.max(120, Math.min(maxH, below)) }
    );
  }, []);

  useEffect(() => {
    if (!open) return undefined;
    updatePosition();
    window.addEventListener("resize", updatePosition);
    window.addEventListener("scroll", updatePosition, true);
    return () => {
      window.removeEventListener("resize", updatePosition);
      window.removeEventListener("scroll", updatePosition, true);
    };
  }, [open, updatePosition]);

  const normalized = (options || []).map((o) =>
    typeof o === "object" && o !== null ? o : { value: o, label: String(o) }
  );
  const selected = normalized.find((o) => String(o.value) === String(value));

  const filtered = searchable && query.trim()
    ? normalized.filter(
        (o) =>
          o.label.toLowerCase().includes(query.trim().toLowerCase()) ||
          String(o.value).toLowerCase().includes(query.trim().toLowerCase())
      )
    : normalized;
  const exactMatch = filtered.some((o) => String(o.value).toLowerCase() === query.trim().toLowerCase());
  const customOption =
    allowCustom && query.trim() && !exactMatch ? { value: query.trim(), label: query.trim(), isCustom: true } : null;
  const visibleOptions = customOption ? [...filtered, customOption] : filtered;

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      // The popover is portaled to document.body - exempt listRef or the
      // mousedown that starts an option click closes the panel first.
      const t = e.target as Node;
      if (ref.current?.contains(t) || listRef.current?.contains(t)) return;
      setOpen(false);
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, []);

  useEffect(() => {
    if (!open) return undefined;
    setQuery("");
    const idx = normalized.findIndex((o) => String(o.value) === String(value));
    setActiveIndex(idx >= 0 ? idx : 0);
    if (searchable) {
      const t = setTimeout(() => searchRef.current?.focus(), 10);
      return () => clearTimeout(t);
    }
    return undefined;
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    setActiveIndex(0);
  }, [query]);

  function commit(option: { value: any; isCustom?: boolean } | null | undefined) {
    if (!option) return;
    onChange?.({ target: { value: option.value, name } });
    setOpen(false);
  }

  function onKeyDown(e: KeyboardEvent) {
    if (disabled) return;
    if (!open) {
      if (["Enter", " ", "ArrowDown", "ArrowUp"].includes(e.key)) {
        e.preventDefault();
        setOpen(true);
      }
      return;
    }
    if (e.key === "Escape") {
      e.preventDefault();
      setOpen(false);
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      setActiveIndex((i) => Math.min(i + 1, visibleOptions.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      commit(visibleOptions[activeIndex]);
    } else if (e.key === " " && !searchable) {
      e.preventDefault();
      commit(visibleOptions[activeIndex]);
    }
  }

  return (
    <div className={`select-root${className ? ` ${className}` : ""}`} ref={ref}>
      <button
        type="button"
        id={id}
        data-testid={testId}
        className={`select-trigger${disabled ? " select-trigger-disabled" : ""}`}
        onClick={() => !disabled && setOpen((v) => !v)}
        onKeyDown={onKeyDown}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listboxId}
        aria-activedescendant={open && activeIndex >= 0 && visibleOptions[activeIndex] ? optionId(activeIndex) : undefined}
      >
        <span className={`select-trigger-value${selected ? "" : " select-trigger-placeholder"}`}>
          {selected ? selected.label : placeholder}
        </span>
        <ChevronDown size={15} className={`select-chevron${open ? " select-chevron-open" : ""}`} />
      </button>

      {typeof document !== "undefined" &&
        createPortal(
          <AnimatePresence>
            {open && popStyle && (
              <motion.div
                className="select-popover"
                role="listbox"
                id={listboxId}
                ref={listRef}
                style={{ ...popStyle, transformOrigin: flipUp ? "bottom" : "top" }}
                initial={{ opacity: 0, scale: 0.96, y: flipUp ? 4 : -4 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.96, y: flipUp ? 4 : -4 }}
                transition={{ duration: 0.12, ease: [0.16, 1, 0.3, 1] }}
              >
            {searchable && (
              <input
                ref={searchRef}
                type="text"
                className="select-search"
                placeholder="Search…"
                aria-label={tHC("s2")}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={onKeyDown}
              />
            )}
            {visibleOptions.length === 0 && <div className="select-empty">{tHC("s1")}</div>}
            {visibleOptions.map((o, idx) => (
              <motion.button
                type="button"
                key={o.value}
                id={optionId(idx)}
                role="option"
                aria-selected={String(o.value) === String(value)}
                className={`select-option${String(o.value) === String(value) ? " select-option-selected" : ""}${idx === activeIndex ? " select-option-active" : ""}${o.isCustom ? " select-option-custom" : ""}`}
                onMouseEnter={() => setActiveIndex(idx)}
                onClick={() => commit(o)}
                initial={searchable ? { opacity: 0, y: -2 } : false}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.12 }}
              >
                <span>{o.isCustom ? `Use "${o.label}"` : o.label}</span>
                {String(o.value) === String(value) && <Check size={14} strokeWidth={3} />}
              </motion.button>
            ))}
              </motion.div>
            )}
          </AnimatePresence>,
          document.body
        )}
    </div>
  );
}
