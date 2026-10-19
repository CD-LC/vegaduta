/* eslint-disable @typescript-eslint/no-explicit-any */
// Pure formatting helpers for the member dashboard's telemetry readouts.

type Translate = (key: string, values?: Record<string, unknown>) => string;

export function runColor(status: string | null | undefined) {
  if (!status) return "var(--ax-dim)";
  const s = status.toUpperCase();
  if (s === "RUNNING" || s === "ACTIVE") return "var(--ax-violetd)";
  if (s === "FAILED" || s === "ERROR") return "var(--ax-red)";
  return "var(--ax-green)";
}

export function runLabel(status: string | null | undefined, t: Translate) {
  if (!status) return t("runStatusUnknown");
  const s = status.toUpperCase();
  if (s === "RUNNING") return t("runStatusRunning");
  if (s === "FAILED" || s === "ERROR") return t("runStatusFail");
  if (s === "ACTIVE") return t("runStatusOk");
  return s;
}

export function fmtMs(v: number | null | undefined) {
  if (v === null || v === undefined) return "—";
  return v >= 1000 ? `${(v / 1000).toFixed(1)}k` : String(v);
}

export function fmtUsd(v: number | null | undefined) {
  if (v === null || v === undefined) return "—";
  const n = Number(v);
  return `$${n < 0.01 ? n.toFixed(4) : n.toFixed(2)}`;
}

export function fmtUptime(seconds: number | null | undefined) {
  if (!seconds && seconds !== 0) return "—";
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

// #916 - hover detail for the EVENT BUS · KAFKA tile: everything
// SystemStatusController#kafkaConfig/kafkaQueue/dlqMessageCount reports,
// in one readable block (broker address, DLT topic, retry policy).
export function kafkaTooltip(systemStatus: any, t: Translate): string {
  const k = systemStatus?.kafka;
  if (!k?.config) return t("kafkaTooltipEmpty");
  const c = k.config;
  const lines = [
    t("kafkaTooltipBroker", { value: c.bootstrapServers || t("kafkaTooltipNotConnected") }),
    t("kafkaTooltipTopic", { topic: c.topic, partitions: c.partitions, replication: c.replicationFactor }),
    t("kafkaTooltipConsumerGroup", { group: c.consumerGroup }),
    t("kafkaTooltipRetry", { max: c.maxRetries, backoff: c.retryBackoffMs }),
    t("kafkaTooltipDlt", { topic: c.dltTopic }),
  ];
  if (k.queue) lines.push(t("kafkaTooltipQueue", { pending: k.queue.pendingCount, dead: k.queue.deadLetteredCount }));
  if (k.dlq) lines.push(t("kafkaTooltipDlq", { count: k.dlq.messageCount ?? t("kafkaTooltipUnknown") }));
  return lines.join("\n");
}

function friendlyAction(actionType: string | null | undefined) {
  const s = (actionType || "").toLowerCase();
  const known = ["whatsapp", "telegram", "email", "slack", "discord", "instagram", "messenger", "refund", "workflow"];
  const hit = known.find((k) => s.includes(k));
  if (hit) return hit.charAt(0).toUpperCase() + hit.slice(1);
  const parts = (actionType || "").split(".");
  return parts[parts.length - 1] || actionType || "action";
}

/** "3 Whatsapp · 1 Refund" - pending approvals grouped by a friendly action name. */
export function approvalsBreakdown(pendingApprovals: any[]): string {
  const counts = new Map();
  pendingApprovals.forEach((a) => {
    const label = friendlyAction(a.actionType);
    counts.set(label, (counts.get(label) || 0) + 1);
  });
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([label, count]) => `${count} ${label}`)
    .join(" · ");
}

const MODEL_COLORS = ["var(--ax-cyan)", "var(--ax-violetd)", "var(--ax-amber)"];

/** Donut segments for the top three models in the last 24h split. */
export function modelArcs(llm: any): { model: string; pct: number; offset: number; color: string }[] {
  const topModels = (llm?.modelSplitLast24h || []).slice(0, 3);
  let modelCumulative = 0;
  return topModels.map((m: { model: string; percent: number }, i: number) => {
    const pct = Math.round(m.percent);
    const offset = 25 - modelCumulative;
    modelCumulative += pct;
    return { model: m.model, pct, offset, color: MODEL_COLORS[i] };
  });
}
