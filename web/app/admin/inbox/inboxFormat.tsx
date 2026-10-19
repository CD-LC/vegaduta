// Channel / view option lists and the small formatters shared by the operator
// inbox panel (InboxPanel.tsx) and its thread and details panes.

import { MessageSquare } from "lucide-react";
import { type StatusDotStatus } from "../../components/ui/status-dot";
import { CHANNEL_ICON } from "../../components/BrandIcons";
import { type ConversationSummary, type ConversationView, type HandoffChannel, type HandoffTranscriptMessage } from "./inboxApi";

export const CHANNELS: { value: HandoffChannel; label: string }[] = [
  { value: "WHATSAPP", label: "WhatsApp" },
  { value: "TELEGRAM", label: "Telegram" },
  { value: "INSTAGRAM", label: "Instagram" },
  { value: "MESSENGER", label: "Messenger" },
  { value: "SLACK", label: "Slack" },
  { value: "DISCORD", label: "Discord" },
  { value: "X", label: "X" },
  { value: "TEAMS", label: "Teams" },
  { value: "EMAIL", label: "Email" },
  { value: "VOICE", label: "Voice" },
  { value: "SMS", label: "SMS" },
  { value: "SIGNAL", label: "Signal" },
];

export const VIEW_TABS: { value: ConversationView; label: string }[] = [
  { value: "all", label: "All" },
  { value: "waiting", label: "Waiting" },
  { value: "held", label: "Held" },
  { value: "agent", label: "Agent-handled" },
  { value: "pending", label: "Pending pairing" },
];

export function relTime(iso: string | null): string {
  if (!iso) return "—";
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) return "just now";
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

export function fullTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : "—";
}

export function dotStatus(s: ConversationSummary): StatusDotStatus {
  if (!s.paired) return "unknown";
  if (s.handoffState === "AWAITING_HUMAN") return "pending";
  if (s.handoffState === "HUMAN") return "up";
  return "down";
}

export function stateLabel(s: ConversationSummary): string {
  if (!s.paired) return "Pending pairing";
  if (s.handoffState === "AWAITING_HUMAN") return "Waiting for a human";
  if (s.handoffState === "HUMAN") return "Human-held";
  return "Agent-handled";
}

/** Bubble caption: who wrote this OUTBOUND row - agent auto-reply ("agent"), a named operator, or a console test send. */
export function authorLabel(m: HandoffTranscriptMessage): string {
  if (m.direction === "INBOUND") return "Customer";
  if (m.author === "agent") return "Agent";
  return m.author ? `Operator · ${m.author}` : "Operator";
}

export function ChannelBadge({ channel }: { channel: HandoffChannel }) {
  const Icon = CHANNEL_ICON[channel.toLowerCase()] ?? MessageSquare;
  const label = CHANNELS.find((c) => c.value === channel)?.label ?? channel;
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-foreground">
      <Icon className="h-3.5 w-3.5" /> {label}
    </span>
  );
}
