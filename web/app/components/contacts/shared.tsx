"use client";

import { timeAgo as sharedTimeAgo } from "../../lib/format";
import { useTranslations } from "../../lib/i18n";
import { CHANNEL_BRAND, CHANNEL_ICON } from "../BrandIcons";
import { channelOffered } from "../../lib/edition";

// Shared pieces of the contacts & request-mapping screen (ContactsPanel.tsx).

// Mirrors the `main th` column-label style (console.css section 7) for the
// field labels inside stacked phone rows.
export const STACK_LABEL = "font-mono text-[11px] font-medium uppercase tracking-[0.12em] text-muted-foreground";

export const CHANNEL_META: Record<string, { label: string; color: string }> = {
  whatsapp: { label: "WhatsApp", color: CHANNEL_BRAND.whatsapp.color },
  telegram: { label: "Telegram", color: CHANNEL_BRAND.telegram.color },
  instagram: { label: "Instagram", color: CHANNEL_BRAND.instagram.color },
  messenger: { label: "Messenger", color: CHANNEL_BRAND.messenger.color },
  slack: { label: "Slack", color: CHANNEL_BRAND.slack.color },
  discord: { label: "Discord", color: CHANNEL_BRAND.discord.color },
  x: { label: "X", color: CHANNEL_BRAND.x.color },
  email: { label: "Email", color: CHANNEL_BRAND.email.color },
  voice: { label: "Voice", color: CHANNEL_BRAND.voice.color },
  signal: { label: "Signal", color: CHANNEL_BRAND.signal.color },
};
// The channels the "add contact" / routing pickers offer. CHANNEL_META stays complete so an existing row for an
// unoffered channel still gets its label and colour; only the choices shrink (no Slack/Discord/X on a personal install).
export const CHANNEL_ORDER = Object.keys(CHANNEL_META).filter(channelOffered);

// How many per-session approve/reject calls run at once during a bulk action.
export const BULK_CONCURRENCY = 4;

export interface AgentOption {
  id: string;
  name: string;
}

export interface Contact {
  id: string;
  channel: string;
  identifier: string;
  displayName?: string;
  groupId?: string;
  groupName?: string;
  agentId?: string;
  agentName?: string;
  source?: string;
  sessionId?: string;
  paired?: boolean;
  lastMessageAt?: string;
  lastSeenAt?: string;
  createdAt?: string;
}
export interface ChannelRouting {
  channel: string;
  defaultAgentId?: string;
  defaultAgentName?: string;
  autoApproveNewSenders: boolean;
}

// ChannelPairingController.PendingPairingResponse - the identifier is
// server-masked (last 4 only); the admin cross-checks the pairing code.
export interface PendingPairing {
  sessionId: string;
  channel: string;
  externalIdMasked: string;
  pairingCode?: string;
  createdAt?: string;
}

export interface BulkProgress {
  kind: "approve" | "reject";
  total: number;
  done: number;
}

export function ChannelChip({ channel }: { channel: string }) {
  const key = (channel || "").toLowerCase();
  const meta = CHANNEL_META[key] || { label: channel, color: "var(--text-muted)" };
  const Icon = CHANNEL_ICON[key];
  return (
    <span className="inline-flex items-center gap-2">
      <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-muted text-foreground">
        {Icon ? <Icon className="size-3.5" style={{ color: meta.color }} /> : meta.label.slice(0, 1)}
      </span>
      <span className="inline-flex items-center gap-1.5 text-sm text-foreground">
        <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full" style={{ background: meta.color }} />
        {meta.label}
      </span>
    </span>
  );
}

/** Relative "x minutes ago" formatter bound to the contactsPage translations. */
export function useTimeAgo() {
  const t = useTranslations("contactsPage");
  return (value?: string) =>
    sharedTimeAgo(value, {
      rounding: "floor",
      maxDays: Infinity,
      labels: {
        invalid: t("justNow"),
        justNow: t("justNow"),
        minutes: (n) => t("minutesAgo", { count: String(n) }),
        hours: (n) => t("hoursAgo", { count: String(n) }),
        days: (n) => t("daysAgo", { count: String(n) }),
      },
    });
}
