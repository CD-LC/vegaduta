"use client";

import { useEffect, useState } from "react";
import { Radio, Square } from "lucide-react";
import { Button } from "../ui/button";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { useTranslations } from "../../lib/i18n";
import { edgeRelayApi, type RelayChannel, type RelayStatus } from "../../lib/api/edgeRelay";
import {
  getEdgeRelayState,
  startEdgeRelay,
  stopEdgeRelay,
  subscribeEdgeRelayState,
  type RelayState,
} from "../../lib/edge/relay";

/**
 * Edge relay (2026-10-14): the /edge card that turns this browser into the
 * model behind the tenant's SMS and WhatsApp numbers. The loop lives in
 * lib/edge/relay.ts; this card only starts/stops it, shows its state, and
 * lists the other devices currently answering (GET .../status, polled while
 * the card is mounted). Mounted from app/edge/page.tsx, not EdgeConsole, so
 * the console file stays under its size baseline.
 */
const CHANNEL_OPTIONS: { value: RelayChannel; labelKey: string }[] = [
  { value: "SMS", labelKey: "relay.channelSms" },
  { value: "WHATSAPP", labelKey: "relay.channelWhatsapp" },
];

export default function EdgeRelaySection() {
  const t = useTranslations("edge");
  const tp = useTranslations("consolePolish");
  const [state, setState] = useState<RelayState>(getEdgeRelayState);
  const [channels, setChannels] = useState<RelayChannel[]>(["SMS", "WHATSAPP"]);
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [others, setOthers] = useState<RelayStatus[]>([]);

  const othersPager = usePagination(others, 10);

  useEffect(() => subscribeEdgeRelayState(setState), []);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      edgeRelayApi
        .getEdgeRelayStatus()
        .then((s) => {
          if (cancelled) return;
          setEnabled(s.enabled);
          setOthers(s.relays.filter((r) => r.relayId !== getEdgeRelayState().relayId));
        })
        .catch(() => {
          if (!cancelled) setEnabled((prev) => prev ?? null);
        });
    void load();
    const timer = setInterval(() => {
      void load();
    }, 15_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [state.relayId]);

  // Leaving the page stops answering; the server's heartbeat TTL covers a closed tab.
  useEffect(() => () => void stopEdgeRelay(), []);

  const running = state.status === "starting" || state.status === "online";
  const count = (key: string, n: number) => t(key).replace("{count}", String(n));
  const statusLabel =
    state.status === "online"
      ? t("relay.statusOnline")
      : state.status === "starting"
        ? t("relay.statusStarting")
        : state.status === "error"
          ? t("relay.statusError")
          : state.status === "stopped"
            ? t("relay.statusStopped")
            : t("relay.statusOff");

  function toggleChannel(value: RelayChannel) {
    setChannels((prev) => (prev.includes(value) ? prev.filter((c) => c !== value) : [...prev, value]));
  }

  return (
    <section className="rounded-xl border border-border bg-card p-5" data-testid="edge-relay">
      <div className="flex items-start gap-3">
        <Radio size={18} className="mt-0.5 shrink-0" aria-hidden="true" />
        <div className="min-w-0 flex-1 space-y-3">
          <div>
            <h2 className="text-sm font-semibold">{t("relay.title")}</h2>
            <p className="mt-1 text-xs text-muted-foreground">{t("relay.lead")}</p>
          </div>

          {enabled === false && <p className="text-xs text-muted-foreground">{t("relay.disabled")}</p>}

          <fieldset className="space-y-1.5" disabled={running}>
            <legend className="text-xs font-medium">{t("relay.channelsLabel")}</legend>
            <div className="flex flex-wrap gap-4">
              {CHANNEL_OPTIONS.map((opt) => (
                <label key={opt.value} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={channels.includes(opt.value)}
                    onChange={() => toggleChannel(opt.value)}
                    aria-label={t(opt.labelKey)}
                  />
                  {t(opt.labelKey)}
                </label>
              ))}
            </div>
          </fieldset>

          <div className="flex flex-wrap items-center gap-3">
            {running ? (
              <Button type="button" variant="outline" size="sm" onClick={() => void stopEdgeRelay()}>
                <Square size={13} aria-hidden="true" /> {t("relay.stop")}
              </Button>
            ) : (
              <Button
                type="button"
                size="sm"
                disabled={channels.length === 0 || enabled === false}
                onClick={() => startEdgeRelay(channels, undefined, { deviceLabel: navigator.userAgent.slice(0, 80) })}
              >
                <Radio size={13} aria-hidden="true" /> {t("relay.start")}
              </Button>
            )}
            <span className="text-xs" role="status" aria-live="polite">
              {statusLabel}
            </span>
          </div>

          <div className="flex flex-wrap gap-4 text-xs text-muted-foreground">
            <span>{count("relay.answered", state.answered)}</span>
            <span>{count("relay.failed", state.failed)}</span>
          </div>
          {state.lastError === "no-local-model" ? (
            <p className="text-xs" style={{ color: "var(--state-warning, inherit)" }}>
              {t("relay.needsModel")}
            </p>
          ) : (
            <p className="text-xs text-muted-foreground">{running ? t("relay.keepOpen") : t("relay.needsModel")}</p>
          )}
          {state.lastError && state.lastError !== "no-local-model" && (
            <p className="text-xs text-muted-foreground">{state.lastError}</p>
          )}

          <div>
            <div className="text-xs font-medium">{t("relay.others")}</div>
            {others.length === 0 ? (
              <p className="text-xs text-muted-foreground">{t("relay.noOthers")}</p>
            ) : (
              <ul className="mt-1 space-y-0.5 text-xs text-muted-foreground">
                {othersPager.pageItems.map((r) => (
                  <li key={r.relayId}>
                    {r.subject} · {r.channels.join(", ")}
                    {r.modelLabel ? ` · ${r.modelLabel}` : ""} · {count("relay.answered", r.answered)}
                  </li>
                ))}
              </ul>
            )}
            {others.length > 10 && (
              <PaginationControls
                page={othersPager.page}
                totalPages={othersPager.totalPages}
                totalItems={othersPager.totalItems}
                pageSize={othersPager.pageSize}
                onPageChange={othersPager.setPage}
                onPageSizeChange={othersPager.setPageSize}
                itemLabel={tp("edgeDevicesLabel")}
              />
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
