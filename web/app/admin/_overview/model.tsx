import type { ReactNode } from "react";
import { CircleCheck, CircleX, TriangleAlert } from "lucide-react";
import { api } from "../../lib/api";
import { isLocalAuth } from "../../lib/authMode";
import type { SystemStatusSummary } from "../../components/dashboard/SystemStatusPanel";

// Same t(key, values?) shape useTranslations() actually returns (see
// I18nProvider's t callback in lib/i18n.tsx) - that file predates the
// noImplicitAny ratchet and is out of this pass's scope, so its own
// `namespace: any` param stays untouched; this is only the local contract
// this file's helpers (buildSetupSteps) need to accept `t` as a typed param
// instead of an implicit any.
export type TFunction = (key: string, values?: Record<string, string | number | null | undefined>) => string;

// TS mirror of the four numeric/boolean stats this page fetches in refresh()
// below - each starts null (not yet loaded) and resolves to a real number
// (or, for channelConnected, a boolean) once its own best-effort fetch
// settles. Matches the shape of the useState initializer immediately below.
export interface AdminStats {
  users: number | null;
  approvals: number | null;
  providers: number | null;
  agents: number | null;
  // Workflows the tenant has deployed and how many are actually ACTIVE
  // (w.status === "ACTIVE", same convention WorkflowDesignerPanel's
  // activeCount uses) - the row-1 tile that replaced JVM uptime.
  workflows: number | null;
  workflowsActive: number | null;
  channelConnected: boolean;
  // TenantSettingsResponse#plan ("FREE" | "PREMIUM" | ...) - null until the
  // settings fetch settles (or if it failed). Gates the first-run card's
  // "included free" list, which is only true for a FREE tenant.
  plan: string | null;
  // "on the managed Platform LLM" - FREE (always) or a paid tenant that
  // opted into usePlatformLlm. Same derivation AgentWorkbench.tsx makes from
  // the same GET /api/tenant/settings response. null = not known yet.
  onPlatformLlm: boolean | null;
}

// admin/page.tsx needs kafka's live config/queue/dlq detail (the
// kafkaTooltip + the KAFKA/QUEUE readout below) that SystemStatusPanel's own
// SystemStatusSummary export doesn't carry (that panel only ever renders
// kafka.status) - reusing that shared contract for every other section
// (jvm/mcp/llm/database/overallStatus) and layering the extra kafka detail
// on top locally, rather than duplicating the whole shape.
export interface AdminKafkaStatus {
  status?: string | null;
  config?: {
    bootstrapServers?: string | null;
    topic?: string;
    partitions?: number;
    replicationFactor?: number;
    consumerGroup?: string;
    maxRetries?: number;
    retryBackoffMs?: number;
    dltTopic?: string;
  } | null;
  queue?: { pendingCount: number; deadLetteredCount: number } | null;
  dlq?: { messageCount?: number | null } | null;
}

export interface AdminSystemStatus extends Omit<SystemStatusSummary, "kafka"> {
  kafka?: AdminKafkaStatus | null;
}

// Real, enforced free-tier caps (TenantLimitsServiceImpl.java / signup's own
// VESSEL constant, task #270) - reused here only to show "X of N" usage,
// never to gate anything client-side.
export const TIER_SEATS = 2;
export const TIER_AGENTS = 5;

// Shared key union for PLATFORM_CHECKS/CHANNEL_LABEL_KEYS/the `platforms`
// state below - keeps p.key indexable into CHANNEL_LABEL_KEYS without an
// implicit-any element access.
export type PlatformKey = "twilio" | "meta-whatsapp" | "meta-social" | "slack" | "discord" | "google" | "x";

export interface PlatformCheck {
  key: PlatformKey;
  label: string;
  fetch: () => Promise<unknown>;
}

// Real per-channel credential GETs (api.js) - identical list to the
// Dashboard's own PLATFORM_CHECKS and /admin/maintenance's copy. Every one
// resolves to null when nothing's activated yet, so "connected" is real,
// never guessed.
export const PLATFORM_CHECKS: PlatformCheck[] = [
  { key: "twilio", label: "Twilio WhatsApp", fetch: () => api.getTwilioCredential() },
  { key: "meta-whatsapp", label: "Meta WhatsApp Cloud", fetch: () => api.getMetaWhatsAppCredential() },
  { key: "meta-social", label: "Messenger / Instagram", fetch: () => api.getMetaSocialCredential() },
  { key: "slack", label: "Slack", fetch: () => api.getSlackCredential() },
  { key: "discord", label: "Discord", fetch: () => api.getDiscordCredential() },
  { key: "google", label: "Google Workspace", fetch: () => api.getGoogleConnection() },
  { key: "x", label: "X (Twitter) DMs", fetch: () => api.getXCredential() },
];

// i18n key per PLATFORM_CHECKS.key - PLATFORM_CHECKS.label above stays as the
// internal/fallback identifier threaded through refresh()'s platformResults
// state; the actual rendered channel name in the "Connected channels" card
// below always goes through this map + t(), never the raw .label field.
export const CHANNEL_LABEL_KEYS: Record<PlatformKey, string> = {
  twilio: "channelTwilio",
  "meta-whatsapp": "channelMetaWhatsapp",
  "meta-social": "channelMetaSocial",
  slack: "channelSlack",
  discord: "channelDiscord",
  google: "channelGoogle",
  x: "channelX",
};

// First-time setup checklist (task #193) - a tenant-admin lands here right
// after signup, but this page previously only surfaced Users/Approvals
// stats, so the "create an agent, connect a channel" setup path (already the
// very first thing /help tells people to do) had no presence on the actual
// landing page. Each step's `done` check reuses data this page (or one extra
// best-effort fetch) already has - no new backend endpoint needed.
//
// Order and the provider step (funnel audit, 2026-09): step 1 used to be
// "Activate an LLM provider" for everyone. A FREE tenant is always routed
// through the platform-hosted pool and cannot activate a BYOK key at all
// (ProvidersPanel hides the cards), so for the very tenant this checklist
// exists for, step 1 pointed at a page that can't complete it and
// `done: providers > 0` never flipped - the checklist could never finish.
// The provider step is now only listed when we positively know the tenant
// is NOT on the platform pool (onPlatformLlm === false); while unknown it is
// omitted, because a stray "activate a provider" is exactly the wrong hint
// for the common (FREE) case and only a missing hint for a BYOK tenant.
export function buildSetupSteps(stats: AdminStats, t: TFunction) {
  const steps = [
    {
      key: "agent",
      href: "/agents",
      label: t("setupStepAgentLabel"),
      hint: t("setupStepAgentHint"),
      done: stats.agents > 0,
    },
    {
      key: "channel",
      href: "/admin/channels",
      label: t("setupStepChannelLabel"),
      hint: t("setupStepChannelHint"),
      done: stats.channelConnected,
    },
  ];
  // A personal install (local auth) is one person by design: "invite a teammate" is not a setup
  // step there, and leaving it in kept the checklist from ever finishing.
  if (!isLocalAuth()) {
    steps.push({
      key: "user",
      href: "/admin/maintenance",
      label: t("setupStepUserLabel"),
      hint: t("setupStepUserHint"),
      done: stats.users > 1,
    });
  }
  if (stats.onPlatformLlm === false) {
    steps.unshift({
      key: "provider",
      href: "/providers",
      label: t("setupStepProviderLabel"),
      hint: t("setupStepProviderHint"),
      done: stats.providers > 0,
    });
  }
  return steps;
}

// Status tone -> the tier1 --tint-* trio. "neutral" is deliberately the
// tone for down / failed / rejected / not-connected: those are states to
// read, not destructive actions, so they never paint red.
export type ChipTone = "ok" | "warn" | "neutral" | "accent";

// One mapping for every health-style string this page reads
// (SystemStatusController's UP / DOWN / NOT_CONFIGURED / UNKNOWN per
// component, NOMINAL / OK / DEGRADED / CRITICAL overall).
export function healthChip(
  status: string | null | undefined,
  t: TFunction
): { tone: ChipTone; icon: ReactNode; label: string } {
  const s = (status || "").toUpperCase();
  if (s === "UP" || s === "OK" || s === "NOMINAL")
    return {
      tone: "ok",
      icon: <CircleCheck aria-hidden="true" />,
      label: s === "UP" ? "Up" : s === "OK" ? "OK" : "Nominal",
    };
  if (s === "DEGRADED") return { tone: "warn", icon: <TriangleAlert aria-hidden="true" />, label: "Degraded" };
  if (s === "DOWN" || s === "CRITICAL")
    return { tone: "neutral", icon: <CircleX aria-hidden="true" />, label: s === "DOWN" ? "Down" : "Critical" };
  if (s === "NOT_CONFIGURED") return { tone: "neutral", icon: null, label: t("kafkaNotEnabled") };
  return { tone: "neutral", icon: null, label: s ? t("kafkaUnknown") : "—" };
}

/** A real pending approval row (ApprovalsPanel's record shape). */
export interface PendingApproval {
  id: string;
  actionType?: string;
  summary?: string;
  requestedBy?: string;
  createdAt: string;
}
