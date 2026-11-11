"use client";

import { Badge } from "../ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "../ui/card";
import { CHANNEL_ICON } from "../BrandIcons";
import { CHANNEL_META, offeredChannelOrder, StatusDot } from "./ChannelInspectorCards";
import { SMS_ICON, SMS_META } from "./SmsInspector";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";

/* eslint-disable @typescript-eslint/no-explicit-any */
function stateLabel(entry: any, t: any) {
  if (!entry) return t("checkingStatus");
  if (entry.state === "CONNECTED") return entry.detail;
  if (entry.state === "PENDING") return entry.detail;
  if (entry.state === "NEEDS_ATTENTION") return entry.detail;
  return entry.detail || t("connectPrompt");
}

/** The CONNECTED CHANNELS grid: one status card per channel; clicking selects its inspector. */
export function ChannelGrid({
  cardsByKey,
  selected,
  selectChannel,
  statusLoaded,
}: {
  cardsByKey: Map<string, any>;
  selected: string;
  selectChannel: (key: string) => void;
  statusLoaded: boolean;
}) {
  const t = useTranslations("channelsPanel");
  const tSms = useTranslations("channelsSms");
  // Tiles for the channels this install runs; the rest are hidden here, not removed from CHANNEL_META.
  const [first, ...rest] = offeredChannelOrder();
  const tiles = [first, "sms", ...rest];
  return (
    <Card>
      <CardHeader className="pb-0">
        <CardTitle className="font-mono text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
          {t("connectedChannelsSectionLabel")}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {tiles.map((key) => {
            const meta = CHANNEL_META[key] ?? SMS_META;
            const entry = cardsByKey.get(key);
            const state = entry?.state || "NOT_CONNECTED";
            const isSel = selected === key;
            const Icon = CHANNEL_ICON[key] ?? SMS_ICON;
            return (
              <button
                key={key}
                type="button"
                data-testid="channel-card"
                data-channel={key}
                data-tour={`channel-${key}`}
                aria-pressed={isSel}
                className={cn(
                  "group flex flex-col gap-3 rounded-xl border bg-card p-4 text-start transition-all duration-150 hover:-translate-y-0.5 hover:shadow-md",
                  isSel ? "border-primary ring-2 ring-primary/40" : "border-border hover:border-primary/40",
                  state === "NOT_CONNECTED" && !isSel && "border-dashed"
                )}
                onClick={() => selectChannel(key)}
              >
                <div className="flex items-center justify-between">
                  <span
                    className={cn(
                      "flex size-10 items-center justify-center rounded-lg shadow-sm ring-1 ring-black/5",
                      state === "NOT_CONNECTED" && !isSel && "opacity-80"
                    )}
                    style={{ background: meta.color, color: meta.textColor }}
                  >
                    <Icon className="size-5" />
                  </span>
                  <StatusDot state={state} />
                </div>
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold text-foreground">
                    {key === "sms" ? tSms("label") : t(meta.labelKey)}
                  </div>
                  <div className="truncate font-mono text-[11px] text-muted-foreground">
                    {statusLoaded ? stateLabel(entry, t) : t("checkingStatus")}
                  </div>
                </div>
                {entry && entry.sessionCount > 0 && (
                  <Badge variant="secondary" className="w-fit font-mono text-[10px]">
                    {t("sessionsCount", { count: entry.sessionCount })}
                  </Badge>
                )}
              </button>
            );
          })}
        </div>
      </CardContent>
    </Card>
  );
}
