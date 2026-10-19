"use client";

import { useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { api } from "../../lib/api";
import type { McpToolPinMode, McpToolPinStatus } from "../../lib/api/mcpToolPins";
import { useTranslations } from "../../lib/i18n";
import { Badge } from "../ui/badge";
import { Skeleton } from "../ui/skeleton";

export type PinState = "off" | "pinned" | "changed" | "awaiting" | "unpinned";

/** One of five visible states for a server's tool-definition pin. */
export function pinState(s: Pick<McpToolPinStatus, "mode" | "pinned" | "changed">): PinState {
  if (s.mode === "off") return "off";
  if (s.changed) return s.mode === "enforce" ? "awaiting" : "changed";
  return s.pinned ? "pinned" : "unpinned";
}

const VARIANT: Record<PinState, "secondary" | "outline" | "destructive"> = {
  off: "outline",
  pinned: "secondary",
  changed: "destructive",
  awaiting: "destructive",
  unpinned: "outline",
};

/**
 * Reads GET /api/mcp/tool-pins/{id} for each id. `failed` ids are those whose call errored (shown as an
 * unknown state, never as "pinned"). Re-reads when the id list changes.
 */
export function useToolPinStatuses(ids: string[], reloadKey: unknown = 0) {
  const [statuses, setStatuses] = useState<Record<string, McpToolPinStatus>>({});
  const [failed, setFailed] = useState<Record<string, true>>({});
  const [loading, setLoading] = useState(false);
  const key = ids.join(",");

  useEffect(() => {
    if (ids.length === 0) return undefined;
    let cancelled = false;
    setLoading(true);
    void Promise.allSettled(ids.map((id) => api.getMcpToolPin(id))).then((results) => {
      if (cancelled) return;
      const ok: Record<string, McpToolPinStatus> = {};
      const bad: Record<string, true> = {};
      results.forEach((r, i) => {
        if (r.status === "fulfilled" && r.value) ok[ids[i]] = r.value;
        else bad[ids[i]] = true;
      });
      setStatuses(ok);
      setFailed(bad);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, reloadKey]);

  return { statuses, failed, loading };
}

/** Pinned / Changed / Awaiting approval badge for one server. */
export function ToolPinBadge({
  status,
  loading,
  failed,
}: {
  status?: McpToolPinStatus;
  loading?: boolean;
  failed?: boolean;
}) {
  const t = useTranslations("channelsUsageMcp");
  if (!status) {
    if (loading) return <Skeleton className="h-5 w-20 rounded-full" />;
    if (failed) return <Badge variant="outline">{t("pinUnknown")}</Badge>;
    return null;
  }
  const state = pinState(status);
  return (
    <Badge variant={VARIANT[state]} data-testid={`mcp-pin-badge-${state}`} title={t(`pinHint_${state}`)}>
      {t(`pinState_${state}`)}
    </Badge>
  );
}

/** Self-fetching badge for a single registration id (used in the inspector). */
export function ToolPinServerBadge({ serverId }: { serverId: string }) {
  const { statuses, failed, loading } = useToolPinStatuses([serverId]);
  return <ToolPinBadge status={statuses[serverId]} failed={failed[serverId]} loading={loading} />;
}

const MODE_KEY: Record<McpToolPinMode, string> = {
  off: "pinModeOff",
  warn: "pinModeWarn",
  enforce: "pinModeEnforce",
};

/**
 * Persistent mode strip: the active tool-pinning mode and a one-line consequence, plus "all pins current" when
 * no listed server changed. Renders nothing until at least one status has loaded.
 */
export function ToolPinSummary({ statuses }: { statuses: Record<string, McpToolPinStatus> }) {
  const t = useTranslations("channelsUsageMcp");
  const list = Object.values(statuses);
  if (list.length === 0) return null;
  const mode = list[0].mode;
  const changed = list.filter((s) => s.changed).length;
  return (
    <div
      className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-muted/40 px-3 py-2 text-xs"
      data-testid="mcp-pin-summary"
    >
      <ShieldCheck size={14} className="text-muted-foreground" aria-hidden="true" />
      <span className="font-semibold">{t("pinSummaryTitle")}</span>
      <Badge variant="outline" data-testid="mcp-pin-mode">
        {t(MODE_KEY[mode] ?? "pinModeWarn")}
      </Badge>
      <span className="text-muted-foreground">
        {mode === "off"
          ? t("pinSummaryOff")
          : changed === 0
            ? t("pinSummaryCurrent")
            : t("pinSummaryChanged", { count: String(changed) })}
      </span>
    </div>
  );
}
