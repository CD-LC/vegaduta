"use client";

import { useEffect, useRef, useState } from "react";
import { Palette, Sun, Moon, Monitor, Check, Sparkles, Square, Zap, ZapOff, Contrast, CircleDot } from "lucide-react";
import { motion, AnimatePresence } from "motion/react";
import { useTheme } from "./ThemeProvider";
import { useTranslations } from "../lib/i18n";

// Topbar "Palette" button -> popover with six accent swatches + a
// light/dark/system three-way toggle. Every screen in the console re-skins
// live as soon as a swatch is clicked (globals.css reads the CSS variables
// ThemeProvider sets on <html>) - no reload needed.
export default function ThemePicker() {
  const t = useTranslations("themePicker");
  const { accent, mode, setAccent, setMode, accents, prefs, setPref } = useTheme();
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  // The trigger now reads the CURRENT appearance mode instead of a static
  // Palette: Sun (light), Moon (dark), Monitor (system) - matching the icons
  // used inside the appearance toggle below, so the button itself communicates
  // the active theme at a glance. A small Palette hint stays in the corner to
  // keep the accent-swatch affordance discoverable.
  const ModeIcon = mode === "light" ? Sun : mode === "dark" ? Moon : Monitor;

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, []);

  // Escape closes the popover (a11y audit follow-up - every sibling popover
  // in this shell, e.g. LanguageSwitcher/CommandPalette, already does this).
  useEffect(() => {
    if (!open) return undefined;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open]);

  return (
    <div className="row" style={{ position: "relative", overflow: "visible" }} ref={ref}>
      <button
        type="button"
        className="theme-picker-trigger relative rounded-lg"
        onClick={() => setOpen((v) => !v)}
        aria-label={t("customizeTheme")}
        title={t("customizeTheme")}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <ModeIcon className="size-4" aria-hidden="true" />
        {/* Small accent-palette hint so the swatch affordance stays
         * discoverable while the primary icon communicates the mode. */}
        {accents.length > 1 && (
          <Palette className="absolute bottom-0.5 right-0.5 size-2.5 opacity-60" strokeWidth={2.5} aria-hidden="true" />
        )}
      </button>

      {/* Same Motion open/close upgrade as Select.js's popover - real exit
       * animation instead of an instant disappearance. */}
      <AnimatePresence>
        {open && (
          <motion.div
            className="theme-picker-popover"
            role="dialog"
            aria-label={t("customizeTheme")}
            initial={{ opacity: 0, scale: 0.96, y: -4 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.96, y: -4 }}
            transition={{ duration: 0.12, ease: [0.16, 1, 0.3, 1] }}
          >
            {accents.length > 1 && (<>
            <div className="theme-picker-heading">{t("accentColor")}</div>
            <div className="theme-swatch-grid">
              {accents.map((a: { key: string; label: string; swatch: string }) => (
                <button
                  key={a.key}
                  type="button"
                  className={`theme-swatch${accent === a.key ? " theme-swatch-selected" : ""}`}
                  style={{ backgroundColor: a.swatch, boxShadow: "none" }}
                  onClick={() => setAccent(a.key)}
                  aria-label={a.label}
                  aria-pressed={accent === a.key}
                  title={a.label}
                >
                  {accent === a.key && <Check className="size-3.5" color="white" strokeWidth={3} aria-hidden="true" />}
                </button>
              ))}
            </div>
            </>)}

            <div className="theme-picker-heading">{t("appearance")}</div>
            <div className="theme-mode-row">
              <button
                type="button"
                className={`theme-mode-btn${mode === "light" ? " theme-mode-btn-active" : ""}`}
                onClick={() => setMode("light")}
                aria-pressed={mode === "light"}
              >
                <Sun className="size-4" aria-hidden="true" /> {t("light")}
              </button>
              <button
                type="button"
                className={`theme-mode-btn${mode === "dark" ? " theme-mode-btn-active" : ""}`}
                onClick={() => setMode("dark")}
                aria-pressed={mode === "dark"}
              >
                <Moon className="size-4" aria-hidden="true" /> {t("dark")}
              </button>
              <button
                type="button"
                className={`theme-mode-btn${mode === "system" ? " theme-mode-btn-active" : ""}`}
                onClick={() => setMode("system")}
                aria-pressed={mode === "system"}
              >
                <Monitor className="size-4" aria-hidden="true" /> {t("auto")}
              </button>
            </div>

            {/* Modern display options (2026-09-24). Inline English on purpose:
                messages/en.json is fragile (see web-i18n-rules); localise once
                the option set settles. */}
            <div className="theme-picker-heading">Background</div>
            <div className="theme-mode-row">
              <button type="button" className={`theme-mode-btn${prefs.bg === "mesh" ? " theme-mode-btn-active" : ""}`} onClick={() => setPref("bg", "mesh")} aria-pressed={prefs.bg === "mesh"}>
                <Sparkles className="size-4" aria-hidden="true" /> Mesh
              </button>
              <button type="button" className={`theme-mode-btn${prefs.bg === "plain" ? " theme-mode-btn-active" : ""}`} onClick={() => setPref("bg", "plain")} aria-pressed={prefs.bg === "plain"}>
                <Square className="size-4" aria-hidden="true" /> Plain
              </button>
            </div>

            <div className="theme-picker-heading">Motion</div>
            <div className="theme-mode-row">
              <button type="button" className={`theme-mode-btn${prefs.motion === "full" ? " theme-mode-btn-active" : ""}`} onClick={() => setPref("motion", "full")} aria-pressed={prefs.motion === "full"}>
                <Zap className="size-4" aria-hidden="true" /> Full
              </button>
              <button type="button" className={`theme-mode-btn${prefs.motion === "reduced" ? " theme-mode-btn-active" : ""}`} onClick={() => setPref("motion", "reduced")} aria-pressed={prefs.motion === "reduced"}>
                <ZapOff className="size-4" aria-hidden="true" /> Reduced
              </button>
            </div>

            <div className="theme-picker-heading">Contrast</div>
            <div className="theme-mode-row">
              <button type="button" className={`theme-mode-btn${prefs.contrast === "normal" ? " theme-mode-btn-active" : ""}`} onClick={() => setPref("contrast", "normal")} aria-pressed={prefs.contrast === "normal"}>
                <CircleDot className="size-4" aria-hidden="true" /> Normal
              </button>
              <button type="button" className={`theme-mode-btn${prefs.contrast === "high" ? " theme-mode-btn-active" : ""}`} onClick={() => setPref("contrast", "high")} aria-pressed={prefs.contrast === "high"}>
                <Contrast className="size-4" aria-hidden="true" /> High
              </button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
