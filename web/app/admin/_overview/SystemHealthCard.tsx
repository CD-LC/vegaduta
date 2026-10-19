"use client";

import { Database, Layers, MemoryStick, Plug } from "lucide-react";
import { Card } from "../../components/ui/card";
import { useTranslations } from "../../lib/i18n";
import { healthChip, type AdminSystemStatus } from "./model";
import { CardHeading, Eyebrow, StatusChip } from "./ui";

/** Row-2 system readouts: database pool, MCP registry, JVM heap and (after first run) the Kafka queue. */
export default function SystemHealthCard({
  systemStatus,
  firstRun,
}: {
  systemStatus: AdminSystemStatus | null;
  firstRun: boolean;
}) {
  const t = useTranslations("adminOverview");
  const dbChip = healthChip(systemStatus?.database?.status, t);
  const kafkaChip = healthChip(systemStatus?.kafka?.status, t);
  const heapPct = systemStatus?.jvm?.heapMaxMb
    ? Math.round((systemStatus.jvm.heapUsedMb / systemStatus.jvm.heapMaxMb) * 100)
    : null;
  // #916 - hover detail for the KAFKA / QUEUE readout: everything
  // SystemStatusController#kafkaConfig/kafkaQueue/dlqMessageCount reports,
  // same computation as the tenant Dashboard's EVENT BUS · KAFKA tile.
  const kafkaTooltip = (() => {
    const k = systemStatus?.kafka;
    if (!k?.config) return t("kafkaStatusNotLoaded");
    const c = k.config;
    const lines = [
      t("kafkaTooltipBroker", { broker: c.bootstrapServers || t("brokerNotConnected") }),
      t("kafkaTooltipTopic", { topic: c.topic, partitions: c.partitions, replication: c.replicationFactor }),
      t("kafkaTooltipConsumerGroup", { group: c.consumerGroup }),
      t("kafkaTooltipRetryCap", { maxRetries: c.maxRetries, backoff: c.retryBackoffMs }),
      t("kafkaTooltipDltTopic", { dltTopic: c.dltTopic }),
    ];
    if (k.queue)
      lines.push(t("kafkaTooltipQueue", { pending: k.queue.pendingCount, deadLettered: k.queue.deadLetteredCount }));
    if (k.dlq) lines.push(t("kafkaTooltipDlqMessages", { count: k.dlq.messageCount ?? t("kafkaUnknown") }));
    return lines.join("\n");
  })();
  return (
    <Card className="gap-4 p-5" data-testid="admin-system-health">
      <CardHeading>{t("heroSystemStatusLabel")}</CardHeading>
      <dl className="m-0 flex flex-col divide-y divide-border">
        <div className="flex flex-col gap-1.5 py-3 first:pt-0 last:pb-0">
          <dt>
            <Eyebrow icon={<Database aria-hidden="true" />}>{t("databasePoolLabel")}</Eyebrow>
          </dt>
          <dd className="m-0 flex items-center justify-between gap-3">
            <span className="font-mono text-xl font-semibold tabular-nums text-foreground">
              {systemStatus?.database?.activeConnections != null
                ? `${systemStatus.database.activeConnections}/${systemStatus.database.maxPoolSize ?? "—"}`
                : "—"}
              <span className="ml-1.5 font-sans text-xs font-normal text-muted-foreground">{t("connsLabel")}</span>
            </span>
            {systemStatus?.database && (
              <StatusChip tone={dbChip.tone} icon={dbChip.icon}>
                {dbChip.label}
              </StatusChip>
            )}
          </dd>
        </div>
        <div className="flex flex-col gap-1.5 py-3 first:pt-0 last:pb-0">
          <dt>
            <Eyebrow icon={<Plug aria-hidden="true" />}>{t("mcpRegistryLabel")}</Eyebrow>
          </dt>
          <dd className="m-0 font-mono text-xl font-semibold tabular-nums text-foreground">
            {systemStatus?.mcp ? `${systemStatus.mcp.enabledServers}/${systemStatus.mcp.totalServers}` : "—"}
            <span className="ml-1.5 font-sans text-xs font-normal text-muted-foreground">{t("enabledLabel")}</span>
          </dd>
        </div>
        <div className="flex flex-col gap-1.5 py-3 first:pt-0 last:pb-0">
          <dt>
            <Eyebrow icon={<MemoryStick aria-hidden="true" />}>{t("jvmHeapLabel")}</Eyebrow>
          </dt>
          <dd className="m-0 flex flex-col gap-2">
            <span className="font-mono text-xl font-semibold tabular-nums text-foreground">
              {heapPct != null ? `${heapPct}%` : "—"}
            </span>
            {heapPct != null && (
              <div
                className="h-1.5 overflow-hidden rounded-full"
                style={{ background: "var(--surface-sunken)" }}
                role="progressbar"
                aria-label={t("jvmHeapLabel")}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={heapPct}
              >
                <div
                  className="h-full rounded-full"
                  style={{ width: `${heapPct}%`, background: "var(--accent-500)" }}
                />
              </div>
            )}
          </dd>
        </div>
        {/* Same first-run rule as the workflows-live tile above: the
            Kafka queue readout only appears once the workspace has an
            agent. #912 - SystemStatusController#kafkaStatus returns a
            real, live-probed status: NOT_CONFIGURED, UP / DOWN, or
            UNKNOWN only if that whole check itself threw. A deployment that
            runs no Kafka at all (NOT_CONFIGURED, e.g. personal) gets no row. */}
        {!firstRun && systemStatus?.kafka?.status !== "NOT_CONFIGURED" && (
          <div className="flex flex-col gap-1.5 py-3 first:pt-0 last:pb-0" title={kafkaTooltip}>
            <dt>
              <Eyebrow icon={<Layers aria-hidden="true" />}>{t("kafkaQueueLabel")}</Eyebrow>
            </dt>
            <dd className="m-0 flex flex-col gap-1.5">
              <div>
                <StatusChip tone={kafkaChip.tone} icon={kafkaChip.icon}>
                  {kafkaChip.label}
                </StatusChip>
              </div>
              {/* #916 - queue depth (this tenant's own workflow_event_outbox
                  rows) + real DLT topic message count. */}
              {systemStatus?.kafka?.queue && (
                <div className="text-xs text-muted-foreground">
                  {t("kafkaQueueDetail", {
                    pending: systemStatus.kafka.queue.pendingCount,
                    deadLettered: systemStatus.kafka.queue.deadLetteredCount,
                    dlq: systemStatus.kafka.dlq?.messageCount ?? "—",
                  })}
                </div>
              )}
              {/* Broker/topic/partitions/DLT config lines - previously
                  only reachable via this div's own `title` tooltip
                  (unreachable on touch and by keyboard). A native
                  <details> is keyboard- and touch-operable for free;
                  `title` stays as a supplementary mouse-hover hint. */}
              <details className="text-xs text-muted-foreground">
                <summary className="cursor-pointer select-none">{t("kafkaConfigDetailsLabel")}</summary>
                <div className="mt-1 whitespace-pre-line">{kafkaTooltip}</div>
              </details>
            </dd>
          </div>
        )}
      </dl>
    </Card>
  );
}
