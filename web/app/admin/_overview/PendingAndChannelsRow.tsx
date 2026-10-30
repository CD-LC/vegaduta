"use client";

import { Clock } from "lucide-react";
import { Card } from "../../components/ui/card";
import { StatusDot } from "../../components/ui/status-dot";
import { useTranslations } from "../../lib/i18n";
import { CHANNEL_LABEL_KEYS, type PendingApproval, type PlatformKey } from "./model";
import { CardHeading, CardLink, formatTime } from "./ui";

/** Row 3: pending approvals | connected channels. */
export default function PendingAndChannelsRow({
  pendingApprovals,
  platforms,
}: {
  pendingApprovals: PendingApproval[];
  platforms: { key: PlatformKey; label: string; connected: boolean | null }[];
}) {
    const tHC = useTranslations("hcAdminOverviewPendingAndChannelsRow");
  const t = useTranslations("adminOverview");
  return (
    <div className="defer-offscreen grid min-w-0 gap-4 lg:grid-cols-[3fr_2fr]">
      <Card className="gap-4 p-5" data-testid="admin-pending-approvals">
        <CardHeading action={<CardLink href="/admin/maintenance">{tHC("s1")}</CardLink>}>
          {t("pendingApprovalsHeading")}
        </CardHeading>
        {/* Bounded to 5 rows; the max-height is a defensive cap so a
          longer list scrolls internally instead of pushing the page. */}
        <div className="flex max-h-56 flex-col gap-2 overflow-y-auto">
          {pendingApprovals.length === 0 && <p className="m-0 text-sm text-muted-foreground">{t("nothingWaiting")}</p>}
          {pendingApprovals.slice(0, 5).map((r) => (
            <div
              key={r.id}
              className="flex items-center gap-3 rounded-lg px-3 py-2.5"
              style={{ background: "var(--surface-sunken)" }}
            >
              <Clock aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
              {/* ApprovalsPanel.js's record carries a human-written
                `summary`; fall back to actionType only for a request
                that predates the summary field. */}
              <span className="min-w-0 flex-1 truncate text-sm text-foreground">{r.summary || r.actionType}</span>
              <span className="shrink-0 text-xs text-muted-foreground">
                {r.requestedBy || t("requestedByUnknown")}
                {formatTime(r.createdAt)}
              </span>
            </div>
          ))}
        </div>
      </Card>

      <Card className="gap-4 p-5" data-testid="admin-connected-channels">
        <CardHeading action={<CardLink href="/admin/channels">Manage</CardLink>}>
          {t("connectedChannelsHeading")}
        </CardHeading>
        <ul className="m-0 flex max-h-56 list-none flex-col gap-2 overflow-y-auto p-0">
          {platforms.map((p) => (
            <li key={p.key} className="flex min-h-8 items-center gap-3">
              {/* Connected = the ok hue; not connected = a muted grey dot,
                no glow - it is an unconfigured state, not an error. */}
              <StatusDot
                status={p.connected ? "up" : "unknown"}
                colors={{ up: "var(--state-success)", unknown: "var(--surface-border-strong)" }}
                glow={false}
                pulse={p.connected === null}
                size={8}
                className="shrink-0"
              />
              <span className="min-w-0 flex-1 truncate text-sm text-foreground">
                {t(CHANNEL_LABEL_KEYS[p.key as PlatformKey] || "channelTwilio")}
              </span>
              <span
                className="shrink-0 text-xs"
                style={{ color: p.connected ? "var(--tint-ok-fg)" : "var(--text-muted)" }}
              >
                {p.connected === null
                  ? t("checkingStatus")
                  : p.connected
                    ? t("connectedStatus")
                    : t("notConnectedStatus")}
              </span>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
