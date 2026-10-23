// The things a person typically wants their agent connected to, with the existing page that does
// each one. Pure data and one pure function: no React, no network. Copy lives in en.json under
// "connectHub" (items.<id>.*).
import { channelOffered } from "../lib/edition";

export type ConnectKind = "channel" | "google" | "page";

export interface ConnectItem {
  readonly id: "telegram" | "whatsapp" | "email" | "google" | "slack" | "sms" | "knowledge";
  readonly kind: ConnectKind;
  readonly href: string;
  /** Channel items: the key in /api/channels/status. */
  readonly channelKey?: string;
  /** No public URL or business account needed: the quickest first win. */
  readonly easy?: boolean;
  /** Inbound is webhook-only: this needs the install reachable on a public URL (a personal/localhost install cannot receive). Shown as a badge so the card is honest rather than removed. */
  readonly needsPublicUrl?: boolean;
}

export const CONNECT_ITEMS: readonly ConnectItem[] = [
  { id: "telegram", kind: "channel", channelKey: "telegram", href: "/admin/channels?channel=telegram", easy: true },
  // whatsappqr: pairing your own number by QR scan - no public webhook needed, the
  // personal-install route. Meta's official API stays under Channels for hosted setups.
  { id: "whatsapp", kind: "channel", channelKey: "whatsappqr", href: "/admin/channels?channel=whatsappqr", easy: true },
  { id: "email", kind: "channel", channelKey: "email", href: "/admin/channels?channel=email" },
  { id: "google", kind: "google", href: "/admin/integrations" },
  { id: "slack", kind: "channel", channelKey: "slack", href: "/admin/channels?channel=slack", needsPublicUrl: true },
  { id: "sms", kind: "channel", channelKey: "sms", href: "/admin/channels?channel=sms", needsPublicUrl: true },
  { id: "knowledge", kind: "page", href: "/knowledge" },
];

/** True when this install offers the item: a channel item whose channel the edition does not run (Slack on a personal install) is not. */
export function connectItemOffered(item: ConnectItem): boolean {
  return !item.channelKey || channelOffered(item.channelKey);
}

/** CONNECT_ITEMS minus what this edition does not offer; every surface that lists or advertises connections reads this. */
export function offeredConnectItems(): readonly ConnectItem[] {
  return CONNECT_ITEMS.filter(connectItemOffered);
}

export type ConnectState = "connected" | "not-connected" | "unknown";

export interface StatusInput {
  channels: { key: string; state: string }[] | null;
  googleConnected: boolean | null;
}

export function connectState(item: ConnectItem, input: StatusInput): ConnectState {
  if (item.kind === "page") return "unknown";
  if (item.kind === "google") {
    return input.googleConnected === null ? "unknown" : input.googleConnected ? "connected" : "not-connected";
  }
  if (!input.channels) return "unknown";
  const row = input.channels.find((c) => c.key === item.channelKey);
  if (!row) return "unknown";
  return row.state === "CONNECTED" ? "connected" : "not-connected";
}
