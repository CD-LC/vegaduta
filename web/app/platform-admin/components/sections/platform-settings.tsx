"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Sliders, Search } from "lucide-react";
import { api } from "../../lib/api";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { toast } from "../../lib/toastBus";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Alert, AlertDescription } from "../ui/alert";
import { Badge } from "../ui/badge";
import { SectionCard } from "./common";
import { useTranslations } from "../../../lib/i18n";

// Generic platform_setting editor - the same row editor that used to live on
// the Abuse & Rate Control page, extracted so it can serve two audiences:
//   - /settings (Platform Settings) renders EVERY row with search - the
//     single "every knob is configurable" surface.
//   - /abuse keeps a filtered view (rate-limit/abuse keys only) so the knobs
//     that page is about stay one click from its detector panels.
// Every write goes through PUT /api/platform/settings/{key} and applies on the
// very next read - no restart, no redeploy.
export function PlatformSettingsSection({
  title = "Platform settings",
  subtitle = "Every admin-editable platform_setting row - changes apply on the very next use, no restart",
  keyFilter,
  delay,
}: {
  title?: string;
  subtitle?: string;
  keyFilter?: (key: string) => boolean;
  delay?: number;
}) {
  const tHC = useTranslations("hcPlatformadminComponentsSectionsPlatformsettings");
  const [settings, setSettings] = useState<any[]>([]);
  const [settingsError, setSettingsError] = useState<any>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [savingKey, setSavingKey] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  const refresh = useCallback(async () => {
    try {
      const list = await api.getPlatformSettings();
      setSettings(Array.isArray(list) ? list : []);
      setSettingsError(null);
      setDrafts((prev) => {
        const next = { ...prev };
        for (const row of list || []) if (next[row.key] === undefined) next[row.key] = row.value;
        return next;
      });
    } catch (err: any) {
      setSettings([]);
      setSettingsError(err.message);
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Platform settings load failed", e));
  }, [refresh]);

  const visible = useMemo(() => {
    let rows = settings;
    if (keyFilter) rows = rows.filter((r) => keyFilter(r.key));
    const q = search.trim().toLowerCase();
    if (q)
      rows = rows.filter((r) => r.key.toLowerCase().includes(q) || (r.description || "").toLowerCase().includes(q));
    return rows;
  }, [settings, keyFilter, search]);

  const pg = usePagination(visible, 25);

  async function handleSave(key: string) {
    const value = (drafts[key] ?? "").trim();
    if (!value) {
      toast.error("Value can't be blank.");
      return;
    }
    setSavingKey(key);
    try {
      const updated = await api.updatePlatformSetting(key, value);
      setSettings((prev) => prev.map((row) => (row.key === key ? updated : row)));
      setDrafts((prev) => ({ ...prev, [key]: updated.value }));
      toast.success(`${key} updated - takes effect on the very next use, no restart.`);
    } catch (err: any) {
      toast.error(err.message || `Could not update ${key}.`);
    } finally {
      setSavingKey(null);
    }
  }

  return (
    <SectionCard title={title} subtitle={subtitle} icon={Sliders} delay={delay}>
      {settingsError && (
        <Alert variant="destructive">
          <AlertDescription>{settingsError}</AlertDescription>
        </Alert>
      )}
      {settings.length > 0 && (
        <div className="relative mb-1 max-w-sm">
          <Search
            size={14}
            className="pointer-events-none absolute start-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={tHC("s2")} className="ps-8" />
        </div>
      )}
      {visible.length === 0 && !settingsError && (
        <p className="text-sm text-muted-foreground">
          {settings.length === 0 ? "No operational settings defined yet." : "No settings match this view."}
        </p>
      )}
      <AnimatePresence initial={false}>
        {pg.pageItems.map((row) => (
          <motion.div
            key={row.key}
            className="rounded-lg border border-border p-4"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.25 }}
          >
            <div className="flex flex-wrap items-center gap-2">
              <div className="font-mono text-sm font-semibold text-foreground">{row.key}</div>
              {keyFilter && (
                <Badge variant="secondary" className="text-[0.62rem] uppercase tracking-wide">
                  {row.key.split(".")[0]}
                </Badge>
              )}
            </div>
            {row.description && <p className="mt-0.5 text-xs text-muted-foreground">{row.description}</p>}
            <div className="mt-2 flex flex-wrap items-end gap-3">
              <div className="w-full max-w-56 space-y-1.5">
                <Label htmlFor={`setting-value-${row.key}`} className="text-xs text-muted-foreground">
                  Value
                </Label>
                <Input
                  id={`setting-value-${row.key}`}
                  value={drafts[row.key] ?? row.value}
                  onChange={(e) => setDrafts((prev) => ({ ...prev, [row.key]: e.target.value }))}
                />
              </div>
              <Button
                size="sm"
                disabled={savingKey === row.key || (drafts[row.key] ?? row.value) === row.value}
                onClick={() => handleSave(row.key)}
              >
                {savingKey === row.key ? "Saving…" : "Save"}
              </Button>
            </div>
            {row.updatedAt && (
              <div className="mt-2 font-mono text-xs text-muted-foreground">
                {tHC("s1")} {new Date(row.updatedAt).toLocaleString()}
                {row.updatedBy ? ` by ${row.updatedBy}` : ""}
              </div>
            )}
          </motion.div>
        ))}
      </AnimatePresence>
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="settings"
      />
    </SectionCard>
  );
}

// The subset of platform_setting keys that are rate/abuse knobs - used by the
// Abuse & Rate Control page so its section stays on-topic while /settings
// carries the full unfiltered list.
export const RATE_LIMIT_KEY_PATTERN = /rate-limit|throttle|burst|flood|velocity|abuse\.|signup\./i;
