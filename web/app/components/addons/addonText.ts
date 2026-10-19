import { useMemo } from "react";
import { useTranslations } from "../../lib/i18n";
import type { AddonView } from "../../lib/addonsApi";
import { errorKeyFor } from "../../lib/addonsModel";

// Key-first text lookups for the add-ons page. Names and descriptions are English data text in the
// catalog (core/providers/src/main/resources/addons/catalog.json). A translation is looked up by add-on
// id (`addons.items.<id>.name`); when none exists the API text is shown. English deliberately has no
// per-id keys in en.json, so a catalog edit shows up without a second place to keep in step.

export type AddonT = (key: string, values?: Record<string, string | number>) => string;

/** The value for `key`, or `fallback` when the catalog has none (the i18n layer answers a miss with the dotted path). */
export function pick(t: AddonT, key: string, fallback: string): string {
  const v = t(key);
  return v === `addons.${key}` ? fallback : v;
}

export interface AddonText {
  t: AddonT;
  name: (a: AddonView) => string;
  description: (a: AddonView) => string;
  /** Display name for an id; falls back to the id itself when the add-on is not in the list. */
  nameOf: (id: string, byId: ReadonlyMap<string, AddonView>) => string;
  category: (c: string) => string;
  unlock: (key: string) => string;
  status: (s: string) => string;
  /** The sentence for a failed call. */
  error: (status: number | undefined, code: string | null | undefined, fallback: string) => string;
}

export function useAddonText(): AddonText {
  const t = useTranslations("addons") as AddonT;
  return useMemo(() => {
    const name = (a: AddonView) => pick(t, `items.${a.id}.name`, a.name);
    return {
      t,
      name,
      description: (a) => pick(t, `items.${a.id}.description`, a.description),
      nameOf: (id, byId) => {
        const a = byId.get(id);
        return a ? name(a) : id;
      },
      category: (c) => pick(t, `category.${c}`, c),
      unlock: (key) => pick(t, `unlock.${key}`, key.replace(/_/g, " ")),
      status: (s) => pick(t, `status.${s}`, s),
      error: (status, code, fallback) => {
        const key = errorKeyFor(status, code);
        // The generic key is the last resort: prefer core's own English sentence over it.
        return key === "error.generic" && fallback ? fallback : t(key);
      },
    };
  }, [t]);
}
