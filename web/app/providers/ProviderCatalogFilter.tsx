"use client";

import { useMemo, useState, type ReactNode } from "react";
import { Search } from "lucide-react";
import { Input } from "../components/ui/input";
import { useTranslations } from "../lib/i18n";

export type ProviderStatusFilter = "all" | "active" | "inactive";

/**
 * Search + status filter for the provider catalog grid. `statusOf` reports whether
 * a provider already has an ACTIVE credential. Returns the visible names plus the
 * control to render above the grid and a "nothing matched" notice.
 */
export function useProviderCatalogFilter<T extends string>(
  names: readonly T[],
  labelOf: (name: T) => string,
  isActive: (name: T) => boolean
): { visible: T[]; controls: ReactNode } {
  const t = useTranslations("consolePolish");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<ProviderStatusFilter>("all");
  const needle = query.trim().toLowerCase();

  const visible = useMemo(
    () =>
      names.filter((n) => {
        if (status === "active" && !isActive(n)) return false;
        if (status === "inactive" && isActive(n)) return false;
        return !needle || labelOf(n).toLowerCase().includes(needle) || n.toLowerCase().includes(needle);
      }),
    [names, needle, status, labelOf, isActive]
  );

  const options: ProviderStatusFilter[] = ["all", "active", "inactive"];
  const controls = (
    <div className="flex flex-wrap items-center gap-3">
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
          placeholder={t("providersSearchPlaceholder")}
          aria-label={t("providersSearchLabel")}
          className="pl-9"
        />
      </div>
      <div role="group" aria-label={t("providersStatusFilter")} className="flex flex-wrap gap-2">
        {options.map((o) => (
          <button
            key={o}
            type="button"
            aria-pressed={status === o}
            onClick={() => setStatus(o)}
            className={
              "rounded-full border px-3 py-1 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 " +
              (status === o
                ? "border-primary bg-primary text-primary-foreground"
                : "border-border bg-card text-muted-foreground hover:bg-accent/50 hover:text-foreground")
            }
          >
            {t(o === "all" ? "providersFilterAll" : o === "active" ? "providersFilterActive" : "providersFilterNotConnected")}
          </button>
        ))}
      </div>
      {visible.length === 0 && (
        <p role="status" className="w-full text-sm text-muted-foreground">
          {t("providersNoMatch")}
        </p>
      )}
    </div>
  );
  return { visible, controls };
}
