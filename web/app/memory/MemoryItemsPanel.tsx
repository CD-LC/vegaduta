"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Brain, Download, RefreshCw } from "lucide-react";
import { api } from "../lib/api";
import { triggerBrowserDownload } from "../lib/api/core";
import Select from "../components/Select";
import EmptyState from "../components/EmptyState";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { useTranslations } from "../lib/i18n";
import MemoryItemRow from "./MemoryItemRow";
import { ITEM_BUCKETS, ITEM_KINDS, groupMemoryItems, labelKey } from "./memoryItems";
import type { MemoryItem } from "./memoryItemTypes";
import type { MemoryAgent } from "./memoryTypes";

type LoadState = "loading" | "ready" | "failed" | "off";

/**
 * The unified-memory review surface (ADR-0028 WP6): the agent's memory_items
 * grouped into needs-review / active / tombstoned. Per-agent because the API
 * and the flag gate are agent-scoped; a 404 from the list means unified memory
 * is off for that agent (platform, tenant or agent level) and the panel says
 * so instead of showing an error. VIEWERs get ACTIVE rows only from the
 * server, so the review and tombstone groups simply stay empty for them.
 */
export default function MemoryItemsPanel({ agents }: { agents: MemoryAgent[] | null }) {
  const t = useTranslations("memory");
  const [agentId, setAgentId] = useState("");
  const [kind, setKind] = useState("");
  const [items, setItems] = useState<MemoryItem[]>([]);
  const [state, setState] = useState<LoadState>("loading");
  // Set once the export endpoint answers 404: the platform flag is off.
  const [exportGone, setExportGone] = useState(false);
  const [exporting, setExporting] = useState(false);

  const selected = agentId || agents?.[0]?.id || "";
  const canEdit = useMemo(
    () => (agents || []).find((a) => a.id === selected)?.accessLevel === "EDITOR",
    [agents, selected]
  );

  const refresh = useCallback(() => {
    if (!selected) return;
    setState("loading");
    api
      .listMemoryItems(selected, kind ? { kind } : undefined)
      .then((list) => {
        if (list == null) {
          setItems([]);
          setState("off");
          return;
        }
        setItems(list);
        setState("ready");
      })
      .catch(() => setState("failed"));
  }, [selected, kind]);

  useEffect(refresh, [refresh]);

  const groups = useMemo(() => groupMemoryItems(items), [items]);

  const doExport = async () => {
    setExporting(true);
    try {
      const file = await api.exportMemoryItems();
      if (!file) {
        setExportGone(true);
        return;
      }
      triggerBrowserDownload(file.blob, file.filename);
    } catch {
      /* surfaced by toJson */
    } finally {
      setExporting(false);
    }
  };

  if (agents && agents.length === 0) {
    return <EmptyState icon={Brain} title={t("itemsNoAgentsTitle")} hint={t("itemsNoAgentsHint")} />;
  }

  return (
    <div className="space-y-4" data-testid="memory-items-panel">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="min-w-[11rem]">
            <Select
              aria-label={t("itemsAgentLabel")}
              value={selected}
              onChange={(e) => setAgentId(e.target.value)}
              options={(agents || []).map((a) => ({ value: a.id, label: a.name }))}
              disabled={!agents || agents.length === 0}
            />
          </div>
          <div className="min-w-[9.5rem]">
            <Select
              aria-label={t("itemsKindLabel")}
              value={kind}
              onChange={(e) => setKind(e.target.value)}
              options={[
                { value: "", label: t("itemsKindAll") },
                ...ITEM_KINDS.map((k) => ({ value: k, label: t(labelKey("kind", k)) })),
              ]}
            />
          </div>
          <Button variant="ghost" size="sm" aria-label={t("retry")} title={t("retry")} onClick={refresh}>
            <RefreshCw size={14} aria-hidden="true" />
          </Button>
        </div>
        {!exportGone && (
          <Button variant="outline" size="sm" disabled={exporting} onClick={() => void doExport()}>
            <Download size={14} aria-hidden="true" /> {exporting ? t("exporting") : t("exportButton")}
          </Button>
        )}
      </div>

      {state === "loading" && <p className="text-sm text-muted-foreground">{t("itemsLoading")}</p>}

      {state === "failed" && (
        <Alert variant="destructive">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("itemsLoadFailedTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("itemsLoadFailedBody")}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={refresh}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {state === "off" && <EmptyState icon={Brain} title={t("itemsOffTitle")} hint={t("itemsOffHint")} />}

      {state === "ready" && items.length === 0 && (
        <EmptyState icon={Brain} title={t("itemsEmptyTitle")} hint={t("itemsEmptyHint")} />
      )}

      {state === "ready" &&
        items.length > 0 &&
        ITEM_BUCKETS.map((bucket) =>
          groups[bucket].length === 0 ? null : (
            <section key={bucket} data-testid={`memory-items-${bucket}`}>
              <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
                {t(`group_${bucket}`)}
                <Badge variant="secondary" className="px-1.5 py-0 text-[11px] font-medium">
                  {groups[bucket].length}
                </Badge>
              </h3>
              <p className="text-xs text-muted-foreground">{t(`group_${bucket}_hint`)}</p>
              <ul className="mt-2 space-y-2">
                {groups[bucket].map((item) => (
                  <MemoryItemRow key={item.id} item={item} agentId={selected} canEdit={canEdit} onChanged={refresh} />
                ))}
              </ul>
            </section>
          )
        )}
    </div>
  );
}
