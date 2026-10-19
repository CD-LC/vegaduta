"use client";

import { useCallback, useEffect, useId, useRef, useState, type CSSProperties } from "react";
import { createPortal } from "react-dom";
import { ChevronDown, Check } from "lucide-react";
import { motion, AnimatePresence } from "motion/react";
import { useTranslations } from "../lib/i18n";

// Console-wide replacement for the native <select> - user feedback ("selecting
// drop down feels old style") called out the OS-rendered native picker as one
// of the console's weakest surfaces next to the ThemePicker/CredentialHelp
// popovers, which already use this same trigger-button + floating-panel
// pattern. This component intentionally mirrors that pattern (see
// ThemePicker.js) rather than pulling in a headless-UI library, so it reuses
// the exact same `popover-in` animation/tokens already proven out elsewhere.
//
// Drop-in shape: accepts either `options={[{value,label}, ...]}` or a plain
// `options={["a","b"]}` array of strings, plus the same `value`/`onChange`/
// `disabled`/`id`/`name` props a native <select> takes, so most call sites
// only need their `<select>...</select> ` swapped for `<Select ... />` with
// `<option>` children replaced by the `options` prop.

// Props deliberately kept loose (any): this is a drop-in for a native <select>
// whose value/option shapes vary widely across ~13 call sites (strings,
// numbers, {value,label} objects), and the goal of this migration pass is a
// faithful .js->.tsx conversion with no behavior change. Every prop is
// optional so existing call sites (which pass only the subset they need)
// type-check unchanged.
export interface SelectOption {
  value: any;
  label?: any;
  isCustom?: boolean;
  [key: string]: any;
}

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
  /** Accessible name for the trigger button when there's no associated
   *  visible <label htmlFor>, e.g. an unlabeled filter pill. Forwarded
   *  as-is to aria-label on the trigger; purely additive, no effect on
   *  callers that don't pass it. */
  "aria-label"?: string;
  "aria-labelledby"?: string;
}

export default function Select({
  value,
  onChange,
  options,
  placeholder,
  disabled = false,
  id,
  name,
  "data-testid": testId,
  className = "",
  // Direct feedback ("select from a modern dropdown by searching") - opt-in
  // rather than a behavior change for this component's ~13 existing call
  // sites, most of which have short enough option lists that a search box
  // would be pure noise. Set searchable on any call site with a long or
  // grouped option list (first user: the approval policy's channel picker).
  searchable = false,
  // allowCustom lets a search term with no exact match still commit as a
  // free-text value (via a synthetic "Use <query>" row) - preserves the
  // "type any action type, e.g. mcp_tool.xyz" escape hatch the plain <input>
  // this replaces used to have, so switching to a curated list of real
  // channel action types never blocks a value the backend would otherwise
  // accept.
  allowCustom = false,
  "aria-label": ariaLabel,
  "aria-labelledby": ariaLabelledBy,
}: SelectProps) {
  const t = useTranslations("selectComponent");
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [query, setQuery] = useState("");
  const ref = useRef<HTMLDivElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const searchRef = useRef<HTMLInputElement | null>(null);
  // Stable id root for the popover's option ids - aria-activedescendant on
  // the trigger needs to name the active option even though DOM focus never
  // actually leaves the trigger/search box (a11y audit follow-up).
  const listboxId = useId();

  // Portal positioning (2026-09-25, direct feedback: "menus are transparent,
  // drop downs are going background and getting cutoff ... selecting the
  // models and providers"). The popover used to be position:absolute inside
  // .select-root, which meant ANY ancestor with overflow:hidden/auto clipped
  // it (NodeConfigDrawer's scrollable body is exactly that) and any stacking
  // context above z-30 (workflow canvas overlays at z-40/50, the dock at
  // z-600) buried it - the "goes background" read. Now the panel renders in
  // document.body at the shared popover tier (z-1000, below toasts' 1200),
  // positioned from the trigger's bounding rect, flipping up when there is
  // more room above, and re-anchored on every scroll/resize - the capture
  // phase on scroll catches inner scrollers like the drawer itself.
  const [popStyle, setPopStyle] = useState<CSSProperties | null>(null);
  const [flipUp, setFlipUp] = useState(false);

  const updatePosition = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const trigger = (el.querySelector(".select-trigger") ?? el) as HTMLElement;
    const r = trigger.getBoundingClientRect();
    const gap = 6;
    const margin = 8;
    // .select-popover's CSS cap (max-h-64 = 16rem), as a number for the
    // flip math; the inline maxHeight still wins wherever space is tighter.
    const maxH = Math.min(256, window.innerHeight - margin * 2);
    const below = window.innerHeight - r.bottom - gap - margin;
    const above = r.top - gap - margin;
    const up = below < Math.min(maxH, 180) && above > below;
    const width = Math.max(r.width, 160);
    const left = Math.max(margin, Math.min(r.left, window.innerWidth - width - margin));
    setFlipUp(up);
    setPopStyle(
      up
        ? { position: "fixed", left, width, top: "auto", bottom: window.innerHeight - r.top + gap,
            maxHeight: Math.max(120, Math.min(maxH, above)) }
        : { position: "fixed", left, width, bottom: "auto", top: r.bottom + gap,
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
      // The popover is portaled to document.body, so its DOM is NOT inside
      // ref.current - without the listRef exemption, the mousedown that
      // starts an option click would close the popover before click fires.
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
      // Focus the search box a tick after the popover's mount animation
      // starts, same "let the open animation begin, then hand off focus"
      // timing every other popover in this console uses.
      const t = setTimeout(() => searchRef.current?.focus(), 10);
      return () => clearTimeout(t);
    }
    return undefined;
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    setActiveIndex(0);
  }, [query]);

  // Keep the highlighted row in view when Arrow keys move activeIndex past
  // what's currently scrolled into the popover's viewport (a11y/controls
  // audit follow-up - this previously only repainted the background).
  useEffect(() => {
    if (!open || activeIndex < 0) return;
    listRef.current
      ?.querySelector(`#${CSS.escape(listboxId)}-option-${activeIndex}`)
      ?.scrollIntoView({ block: "nearest" });
  }, [open, activeIndex, listboxId]);

  function commit(option: SelectOption | null | undefined) {
    if (!option) return;
    onChange?.({ target: { value: option.value, name } });
    setOpen(false);
  }

  function onKeyDown(e: React.KeyboardEvent) {
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
        className={`rounded-lg select-trigger${disabled ? " select-trigger-disabled" : ""}`}
        onClick={() => !disabled && setOpen((v) => !v)}
        onKeyDown={onKeyDown}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listboxId : undefined}
        aria-activedescendant={open && activeIndex >= 0 ? `${listboxId}-option-${activeIndex}` : undefined}
        aria-label={ariaLabel}
        aria-labelledby={ariaLabelledBy}
      >
        <span className={`select-trigger-value${selected ? "" : " select-trigger-placeholder"}`}>
          {selected ? selected.label : (placeholder || t("placeholder"))}
        </span>
        <ChevronDown size={15} className={`select-chevron${open ? " select-chevron-open" : ""}`} aria-hidden="true" />
      </button>

      {/* Motion-driven open/close, portaled to body (see popStyle comment
       * above) - escapes ancestor overflow/stacking entirely. The .select-*
       * CSS still carries the visuals; popStyle's fixed top/bottom/left/
       * width override the old absolute anchoring, and transformOrigin
       * flips with the open direction so the scale-in grows toward the
       * trigger either way. */}
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
                placeholder={t("search")}
                aria-label={t("search")}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={onKeyDown}
              />
            )}
            {visibleOptions.length === 0 && <div className="select-empty">{t("noOptions")}</div>}
            {visibleOptions.map((o, idx) => (
              <motion.button
                type="button"
                key={o.value}
                id={`${listboxId}-option-${idx}`}
                role="option"
                aria-selected={String(o.value) === String(value)}
                className={`rounded-lg select-option${String(o.value) === String(value) ? " select-option-selected" : ""}${idx === activeIndex ? " select-option-active" : ""}${o.isCustom ? " select-option-custom" : ""}`}
                onMouseEnter={() => setActiveIndex(idx)}
                onClick={() => commit(o)}
                initial={searchable ? { opacity: 0, y: -2 } : false}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.12 }}
              >
                <span>{o.isCustom ? t("useCustom", { label: o.label }) : o.label}</span>
                {String(o.value) === String(value) && <Check size={14} strokeWidth={3} aria-hidden="true" />}
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
