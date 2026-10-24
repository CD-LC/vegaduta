"use client";

import { useMemo } from "react";
import { useLocale, useTranslations } from "../../lib/i18n";
import type { SettingCatalogEntry, SettingUnit } from "../../lib/api/settings";
import {
  bestDurationUnit,
  catalogDurationUnit,
  convertDuration,
  formatNumber,
  humanize,
  numericValue,
  type DurationUnit,
} from "./settingsModel";

/**
 * Text lookups for the settings components. Two namespaces:
 *  - "settings": the per-setting copy the catalog points at (labelKey/descriptionKey/helpKey), which owners of a
 *    setting add next to the setting itself. A missing translation falls back to a humanized key, never a raw path.
 *  - "settingsUi": the shared UI strings (buttons, errors, badges, units) these components own.
 */
export interface SettingsText {
  /** Shared UI string from messages "settingsUi". */
  ui: (key: string, values?: Record<string, string | number>) => string;
  /** Like ui(), but returns the fallback when the key has no translation. */
  uiOr: (key: string, fallback: string) => string;
  label: (entry: SettingCatalogEntry) => string;
  description: (entry: SettingCatalogEntry) => string;
  /** The longer explanation (helpKey); empty string when the setting has none. */
  help: (entry: SettingCatalogEntry) => string;
  groupLabel: (group: string) => string;
  optionLabel: (entry: SettingCatalogEntry, value: string) => string;
  unitLabel: (unit: SettingUnit | DurationUnit | undefined) => string;
  /** A setting value the way people should read it ("1.5 min", "On", "25 steps"). */
  formatValue: (entry: SettingCatalogEntry, value: unknown) => string;
  /** For a security key, which direction is stricter ("Higher is stricter..."); empty for every other setting. */
  strictnessHint: (entry: SettingCatalogEntry) => string;
}

export function useSettingsText(): SettingsText {
  const tc = useTranslations("settings");
  const tu = useTranslations("settingsUi");
  const locale = useLocale();
  return useMemo<SettingsText>(() => {
    const ui = (key: string, values?: Record<string, string | number>) => tu(key, values);
    const uiOr = (key: string, fallback: string) => {
      const out = tu(key);
      return !out || out === `settingsUi.${key}` ? fallback : out;
    };
    const catalog = (fullKey: string | undefined, fallback: string) => {
      if (!fullKey) return fallback;
      const rest = fullKey.startsWith("settings.") ? fullKey.slice("settings.".length) : fullKey;
      const out = tc(rest);
      return !out || out === `settings.${rest}` ? fallback : out;
    };
    const unitLabel = (unit: SettingUnit | DurationUnit | undefined) => {
      if (!unit || unit === "count") return "";
      const key = ["ms", "s", "min", "h"].includes(unit) ? `durationUnits.${unit}` : `units.${unit}`;
      return uiOr(key, unit);
    };
    const optionLabel = (entry: SettingCatalogEntry, value: string) =>
      catalog(`settings.${entry.key}.options.${value}`, value);
    const formatValue = (entry: SettingCatalogEntry, value: unknown): string => {
      if (value == null) return ui("value.unset");
      switch (entry.type) {
        case "bool":
          return value === true || value === "true" ? ui("bool.on") : ui("bool.off");
        case "enum":
          return optionLabel(entry, String(value));
        case "string":
        case "text": {
          const s = String(value);
          if (s === "") return ui("value.empty");
          return s.length > 80 ? `${s.slice(0, 80)}…` : s;
        }
        case "duration": {
          const n = numericValue(entry, value);
          if (n == null) return String(value);
          const base = catalogDurationUnit(entry);
          const u = bestDurationUnit(entry, value);
          return `${formatNumber(convertDuration(n, base, u), locale)} ${unitLabel(u)}`;
        }
        case "percent": {
          const n = numericValue(entry, value);
          return n == null ? String(value) : `${formatNumber(n, locale)}%`;
        }
        default: {
          const n = numericValue(entry, value);
          if (n == null) return String(value);
          const unit = unitLabel(entry.unit);
          return unit ? `${formatNumber(n, locale)} ${unit}` : formatNumber(n, locale);
        }
      }
    };
    const strictnessHint = (entry: SettingCatalogEntry): string => {
      switch (entry.strictness) {
        case "higher_is_stricter":
          return ui("strictness.higher");
        case "lower_is_stricter":
          return ui("strictness.lower");
        case "enum_order":
          return ui("strictness.enumOrder", { options: (entry.enumValues ?? []).map((v) => optionLabel(entry, v)).join(" → ") });
        default:
          return "";
      }
    };
    return {
      ui,
      uiOr,
      label: (e) => catalog(e.labelKey, humanize(e.key)),
      description: (e) => catalog(e.descriptionKey, ""),
      help: (e) => catalog(e.helpKey, ""),
      // The catalog's own group name wins (settings.groups.<group>.label); the shared UI names and a humanised id are fallbacks.
      groupLabel: (g) => catalog(`settings.groups.${g}.label`, uiOr(`groups.${g}`, humanize(g))),
      optionLabel,
      unitLabel,
      formatValue,
      strictnessHint,
    };
  }, [tc, tu, locale]);
}
