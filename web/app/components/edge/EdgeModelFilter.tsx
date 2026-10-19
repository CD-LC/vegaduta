"use client";

import { useMemo, useState, type ReactNode } from "react";
import { Search } from "lucide-react";
import { Input } from "../ui/input";
import { useTranslations } from "../../lib/i18n";

/**
 * Search box + "only models that fit this device" toggle for the /edge model market.
 * `fitsNo(model)` answers true for a model the device cannot run (modelFitState === "no");
 * the toggle is off by default so nothing is hidden until the person asks.
 */
export function useEdgeModelFilter<T extends { id: string; displayName: string }>(
  models: T[],
  fitsNo: (model: T) => boolean
): { visible: T[]; controls: ReactNode } {
  const t = useTranslations("consolePolish");
  const [query, setQuery] = useState("");
  const [onlyFitting, setOnlyFitting] = useState(false);
  const needle = query.trim().toLowerCase();

  const visible = useMemo(
    () =>
      models.filter((m) => {
        if (onlyFitting && fitsNo(m)) return false;
        return !needle || m.displayName.toLowerCase().includes(needle) || m.id.toLowerCase().includes(needle);
      }),
    [models, needle, onlyFitting, fitsNo]
  );

  const controls = (
    <div className="flex flex-wrap items-center gap-3 md:col-span-2 xl:col-span-3">
      <div className="relative w-full max-w-sm">
        <Search
          size={16}
          aria-hidden="true"
          className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
        />
        <Input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t("edgeModelSearchPlaceholder")}
          aria-label={t("edgeModelSearchLabel")}
          className="pl-9"
        />
      </div>
      <label className="flex items-center gap-2 text-sm text-foreground">
        <input type="checkbox" checked={onlyFitting} onChange={(e) => setOnlyFitting(e.target.checked)} />
        {t("edgeModelOnlyFitting")}
      </label>
      {visible.length === 0 && models.length > 0 && (
        <p role="status" className="w-full text-sm text-muted-foreground">
          {t("edgeModelNoMatch")}
        </p>
      )}
    </div>
  );
  return { visible, controls };
}
