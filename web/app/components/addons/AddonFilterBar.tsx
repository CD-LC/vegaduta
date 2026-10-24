"use client";

import { Search } from "lucide-react";
import type { AddonCategory } from "../../lib/addonsApi";
import { NO_FILTERS, type AddonFilters, type StatusFilter } from "../../lib/addonsModel";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Select } from "../ui/select";
import type { AddonText } from "./addonText";

const STATUS_FILTERS: readonly StatusFilter[] = ["all", "on", "off", "attention", "planned"];

export interface AddonFilterBarProps {
  filters: AddonFilters;
  onChange: (next: AddonFilters) => void;
  /** Categories present in the catalog, in display order, with how many add-ons each holds. */
  categories: { category: AddonCategory; count: number }[];
  total: number;
  text: AddonText;
}

export default function AddonFilterBar({ filters, onChange, categories, total, text }: AddonFilterBarProps) {
  const { t } = text;
  const active = filters.query !== "" || filters.category !== "all" || filters.status !== "all";
  return (
    <div className="space-y-3" role="search" aria-label={t("filter.label")}>
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <div className="relative min-w-0 flex-1">
          <Search
            className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            type="search"
            value={filters.query}
            onChange={(e) => onChange({ ...filters, query: e.target.value })}
            placeholder={t("filter.searchPlaceholder")}
            aria-label={t("filter.searchLabel")}
            className="pl-9"
          />
        </div>
        <Select
          className="sm:w-56"
          aria-label={t("filter.statusLabel")}
          value={filters.status}
          onValueChange={(v) => onChange({ ...filters, status: v as StatusFilter })}
          options={STATUS_FILTERS.map((s) => ({ value: s, label: t(`filter.status.${s}`) }))}
        />
        {active && (
          <Button type="button" variant="ghost" size="sm" onClick={() => onChange(NO_FILTERS)}>
            {t("filter.clear")}
          </Button>
        )}
      </div>
      <div className="flex flex-wrap gap-1.5" role="group" aria-label={t("filter.categoryLabel")}>
        {[{ category: "all" as const, count: total }, ...categories].map(({ category, count }) => {
          const on = filters.category === category;
          return (
            <button
              key={category}
              type="button"
              aria-pressed={on}
              onClick={() => onChange({ ...filters, category })}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium outline-none transition-colors",
                "focus-visible:ring-[3px] focus-visible:ring-ring",
                on
                  ? "border-primary/40 bg-accent text-accent-foreground"
                  : "border-border text-muted-foreground hover:bg-accent hover:text-accent-foreground"
              )}
            >
              {category === "all" ? t("filter.all") : text.category(category)}{" "}
              <span className="tabular-nums opacity-70">{count}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
