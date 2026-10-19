import { HardDrive, Wrench } from "lucide-react";
import { CATALOG_ICONS } from "../McpPanel/constants";
import { type McpCard, reviewStatusOf } from "../McpPanel/helpers";

type Translate = (key: string, values?: Record<string, any>) => string;

export function cardIcon(card: any) {
  if (card.kind === "builtin") return card.data.icon;
  if (card.kind === "google" || card.kind === "google-connect") return card.data?.icon || HardDrive;
  if (card.kind === "catalog") return CATALOG_ICONS[card.data.iconKey] || Wrench;
  return CATALOG_ICONS[card.catalogEntry?.iconKey] || Wrench;
}

// Marketplace-grid accent (2026-10-02 redesign): one --cat/* value per
// card kind, passed to Card's `cat` prop, so the icon chip (.cat-chip),
// the card-interactive hover hairline and any footer pill all read the
// SAME category hue from a single custom property - per the design-system
// contract. Built-ins carry a per-tool cat in constants.ts (data vs
// comms); Google's brand blue (--ax-google-blue) is a real fixed brand
// identity like the per-channel colors elsewhere in this app; every MCP
// server tile reads as "automation", and a disabled one drops to the
// neutral idle hue.
export function cardCat(card: McpCard): string {
  if (card.kind === "builtin") return card.data.cat;
  if (card.kind === "google" || card.kind === "google-connect") return "var(--ax-google-blue)";
  if (card.kind === "catalog") return "var(--cat-automation)";
  return card.data.enabled ? "var(--cat-automation)" : "var(--state-idle)";
}

// The single status dot a live (dynamic) registration gets - the same
// triage mapping the old ad-hoc dot used, now expressed on the shared
// --state-* set so hue + motion both come from the design system. A
// PENDING/REJECTED review overrides the raw connectionStatus because
// connect/test is refused regardless of what connectionStatus last said
// (MCP-STDIO-SANDBOXING). Static (YAML) registrations render no dot:
// their only meaningful state is the enabled/disabled switch on the card.
export function cardState(card: McpCard): string | null {
  if (card.kind !== "mcp" || !card.data.dynamic) return null;
  const review = reviewStatusOf(card.data);
  if (review === "PENDING") return "var(--info-text)";
  if (review === "REJECTED") return "var(--state-error)";
  const cs = card.data.connectionStatus;
  if (cs === "CONNECTED") return "var(--state-success)";
  if (cs === "ERROR") return "var(--state-error)";
  if (cs === "CONNECTING") return "var(--state-running)";
  return "var(--state-idle)";
}

export function cardTitle(card: any, t: Translate) {
  if (card.kind === "google-connect") return t("googleWorkspace");
  if (card.kind === "builtin" || card.kind === "google") return t(card.data.nameKey);
  return card.data.name;
}

export function cardMeta(card: any, t: Translate) {
  if (card.kind === "builtin") return t("builtInMeta");
  if (card.kind === "google-connect") return t("notConnectedMeta");
  if (card.kind === "google") return t("googleWorkspace");
  if (card.kind === "catalog") return card.data.ready ? t("notAddedNoKeyMeta") : t("notAddedNeedsKeyMeta");
  const base = `${card.data.type}${card.catalogEntry ? t("catalogSuffixLabel") : t("customSuffixLabel")}`;
  // #424 - dynamic (per-tenant, live) registrations carry a real
  // connectionStatus (CONNECTING/CONNECTED/ERROR/DISABLED); static ones
  // (referencing application-mcp.yml) don't have a meaningful one to show
  // here beyond the enabled/disabled toggle already visible on the card.
  const withStatus = card.data.dynamic ? `${base} · ${card.data.connectionStatus}` : base;
  // MCP-STDIO-SANDBOXING - a PENDING/REJECTED review blocks connect/test
  // regardless of connectionStatus, so surface it here too (grid-level,
  // not only the inspector) rather than leaving a confusing "DISABLED"/
  // stale status with no visible reason.
  const review = reviewStatusOf(card.data);
  if (review === "PENDING") return `${withStatus} · awaiting review`;
  if (review === "REJECTED") return `${withStatus} · review rejected`;
  return withStatus;
}

export function cardDesc(card: any, t: Translate) {
  if (card.kind === "google-connect") return t("googleConnectDescription");
  if (card.kind === "builtin" || card.kind === "google") return t(card.data.descriptionKey);
  return card.data.description || card.catalogEntry?.description || t("noDescription");
}
