"use client";

import { useId, useMemo, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { Input } from "../../components/ui/input";

export interface PickerItem {
  id: string;
  name: string;
}

/**
 * Checkbox multi-select for the grant forms. Shows a filter box once the list is long enough to need one
 * (more than 8 items), so a tenant with hundreds of agents or tools can still find the one they want.
 * Selection state is owned by the caller; filtering only hides rows, it never changes what is selected.
 */
export default function CheckboxPicker({
  items,
  selected,
  onToggle,
  emptyLabel,
}: {
  items: PickerItem[];
  selected: Set<string>;
  onToggle: (id: string) => void;
  emptyLabel: string;
}) {
  const t = useTranslations("channelsUsageMcp");
  const [filter, setFilter] = useState("");
  const filterId = useId();
  const needle = filter.trim().toLowerCase();
  const visible = useMemo(
    () => (needle ? items.filter((i) => i.name.toLowerCase().includes(needle)) : items),
    [items, needle],
  );

  if (!items.length) {
    return <p className="m-0 text-xs text-muted-foreground">{emptyLabel}</p>;
  }
  return (
    <div className="space-y-2">
      {items.length > 8 && (
        <div className="flex items-center gap-2">
          <Input
            id={filterId}
            type="search"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={t("pickerFilterPlaceholder")}
            aria-label={t("pickerFilterPlaceholder")}
            className="h-8 max-w-xs text-xs"
          />
          <span className="text-xs text-muted-foreground" aria-live="polite">
            {t("pickerSelectedCount", { selected: String(selected.size), total: String(items.length) })}
          </span>
        </div>
      )}
      <div className="max-h-52 overflow-y-auto rounded-lg border border-border px-3 py-1.5">
        {visible.length === 0 ? (
          <p className="m-0 py-1 text-xs text-muted-foreground">{t("pickerNoMatch")}</p>
        ) : (
          visible.map((item) => (
            <label key={item.id} className="flex cursor-pointer items-center gap-2 py-1 text-sm">
              <input type="checkbox" checked={selected.has(item.id)} onChange={() => onToggle(item.id)} />
              {item.name}
            </label>
          ))
        )}
      </div>
    </div>
  );
}
