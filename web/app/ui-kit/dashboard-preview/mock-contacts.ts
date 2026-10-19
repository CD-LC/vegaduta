// Mutable contacts / pairing stores for the preview mock (mock-api.ts), so
// approve / reject / add / delete behave like core in the preview.

import { DAY, HOUR, MIN, agents, iso } from "./mock-sample-data";

// ---- /admin/contacts (ContactsPanel) --------------------------------------
// GET /api/channels/routing - ChannelRoutingController.RoutingResponse[] (one
// row per ChannelType, lower-cased). GET /api/channels/pairing/pending -
// ChannelPairingController.PendingPairingResponse[] (identifier is server-
// masked). GET /api/channels/contacts + /contact-groups -
// ChannelContactController.ContactResponse[] / GroupResponse[]. The stores
// are mutable so approve / reject / add / delete behave in the preview:
// approving a pending sender removes it from the queue and appends a
// "Live" contact, exactly what core does.
export const channelRouting = [
  { channel: "whatsapp", defaultAgentId: "agt_support_copilot", defaultAgentName: "Support copilot", autoApproveNewSenders: false, inboundSafetyMode: "FLAG" },
  { channel: "telegram", defaultAgentId: "agt_invoice_chaser", defaultAgentName: "Invoice chaser", autoApproveNewSenders: true, inboundSafetyMode: "FLAG" },
  { channel: "instagram", defaultAgentId: null as string | null, defaultAgentName: null as string | null, autoApproveNewSenders: false, inboundSafetyMode: "FLAG" },
  { channel: "messenger", defaultAgentId: null as string | null, defaultAgentName: null as string | null, autoApproveNewSenders: false, inboundSafetyMode: "FLAG" },
  { channel: "slack", defaultAgentId: "agt_lead_qualifier", defaultAgentName: "Lead qualifier", autoApproveNewSenders: true, inboundSafetyMode: "FLAG" },
  { channel: "discord", defaultAgentId: null as string | null, defaultAgentName: null as string | null, autoApproveNewSenders: false, inboundSafetyMode: "FLAG" },
  { channel: "x", defaultAgentId: null as string | null, defaultAgentName: null as string | null, autoApproveNewSenders: false, inboundSafetyMode: "FLAG" },
  { channel: "email", defaultAgentId: "agt_support_copilot", defaultAgentName: "Support copilot", autoApproveNewSenders: false, inboundSafetyMode: "FLAG" },
  { channel: "voice", defaultAgentId: null as string | null, defaultAgentName: null as string | null, autoApproveNewSenders: false, inboundSafetyMode: "FLAG" },
];

export const contactGroups = [
  { id: "grp_vip", name: "VIP customers", description: "Top 50 accounts by revenue.", agentId: "agt_support_copilot", agentName: "Support copilot", memberCount: 2 },
  { id: "grp_suppliers", name: "Suppliers", description: "Vendors who message about price lists.", agentId: "agt_stock_watcher", agentName: "Stock watcher", memberCount: 1 },
];

export type MockContact = {
  id: string;
  channel: string;
  identifier: string;
  displayName: string | null;
  groupId: string | null;
  groupName: string | null;
  agentId: string | null;
  agentName: string | null;
  source: string;
  sessionId: string | null;
  paired: boolean;
  lastMessageAt: string | null;
  lastSeenAt: string | null;
  createdAt: string;
};
export const contactsStore: MockContact[] = [
  { id: "ct_1", channel: "whatsapp", identifier: "+91 98xxx xx210", displayName: "Meera Iyer", groupId: "grp_vip", groupName: "VIP customers", agentId: null, agentName: null, source: "APPROVED", sessionId: "cs_meera", paired: true, lastMessageAt: iso(35 * MIN), lastSeenAt: iso(35 * MIN), createdAt: iso(12 * DAY) },
  { id: "ct_2", channel: "whatsapp", identifier: "+91 99xxx xx884", displayName: "Rohan Desai", groupId: "grp_vip", groupName: "VIP customers", agentId: "agt_lead_qualifier", agentName: "Lead qualifier", source: "APPROVED", sessionId: "cs_rohan", paired: true, lastMessageAt: iso(3 * HOUR), lastSeenAt: iso(3 * HOUR), createdAt: iso(9 * DAY) },
  { id: "ct_3", channel: "telegram", identifier: "@ananya_k", displayName: "Ananya K", groupId: null, groupName: null, agentId: null, agentName: null, source: "AUTO", sessionId: "cs_ananya", paired: true, lastMessageAt: iso(1 * DAY), lastSeenAt: iso(1 * DAY), createdAt: iso(6 * DAY) },
  { id: "ct_4", channel: "email", identifier: "orders@northline-supply.example", displayName: "Northline Supply", groupId: "grp_suppliers", groupName: "Suppliers", agentId: null, agentName: null, source: "IMPORT", sessionId: null, paired: false, lastMessageAt: null, lastSeenAt: null, createdAt: iso(4 * DAY) },
  { id: "ct_5", channel: "whatsapp", identifier: "+91 97xxx xx302", displayName: null, groupId: null, groupName: null, agentId: null, agentName: null, source: "IMPORT", sessionId: null, paired: false, lastMessageAt: null, lastSeenAt: null, createdAt: iso(4 * DAY) },
  { id: "ct_6", channel: "slack", identifier: "U04AB12CD", displayName: "Dev Patel", groupId: null, groupName: null, agentId: "agt_invoice_chaser", agentName: "Invoice chaser", source: "MANUAL", sessionId: "cs_dev", paired: true, lastMessageAt: iso(2 * DAY), lastSeenAt: iso(2 * DAY), createdAt: iso(3 * DAY) },
  { id: "ct_7", channel: "instagram", identifier: "@thefabricloft", displayName: "The Fabric Loft", groupId: null, groupName: null, agentId: null, agentName: null, source: "MANUAL", sessionId: null, paired: false, lastMessageAt: null, lastSeenAt: null, createdAt: iso(1 * DAY) },
];

type MockPending = { sessionId: string; channel: string; externalIdMasked: string; pairingCode: string | null; createdAt: string };
export const pendingPairings: MockPending[] = [
  { sessionId: "cs_p1", channel: "whatsapp", externalIdMasked: "+91 9•••••••417", pairingCode: "K7P2-QX", createdAt: iso(4 * MIN) },
  { sessionId: "cs_p2", channel: "whatsapp", externalIdMasked: "+44 7•••••••905", pairingCode: "M3RD-8A", createdAt: iso(26 * MIN) },
  { sessionId: "cs_p3", channel: "telegram", externalIdMasked: "•••••_ravi", pairingCode: "ZT4Q-1N", createdAt: iso(2 * HOUR) },
  { sessionId: "cs_p4", channel: "email", externalIdMasked: "s•••••@example.com", pairingCode: null, createdAt: iso(6 * HOUR) },
  { sessionId: "cs_p5", channel: "instagram", externalIdMasked: "•••••loft_studio", pairingCode: "HB9L-2C", createdAt: iso(1 * DAY) },
];

let contactSeq = 100;
/** Next numeric suffix for a mock contact or group id (100, 101, ...). */
export function nextContactSeq(): number {
  return contactSeq++;
}
export function parseJsonBody(init?: RequestInit): Record<string, unknown> {
  try {
    return typeof init?.body === "string" ? (JSON.parse(init.body) as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}
export function agentNameFor(id: unknown): string | null {
  return agents.find((a) => a.id === id)?.name ?? null;
}
export function groupNameFor(id: unknown): string | null {
  return contactGroups.find((g) => g.id === id)?.name ?? null;
}
export function approvePending(sessionId: string): MockContact | null {
  const idx = pendingPairings.findIndex((p) => p.sessionId === sessionId);
  if (idx < 0) return null;
  const [row] = pendingPairings.splice(idx, 1);
  const contact: MockContact = {
    id: `ct_${nextContactSeq()}`,
    channel: row.channel,
    identifier: row.externalIdMasked,
    displayName: null,
    groupId: null,
    groupName: null,
    agentId: null,
    agentName: null,
    source: "APPROVED",
    sessionId: row.sessionId,
    paired: true,
    lastMessageAt: row.createdAt,
    lastSeenAt: row.createdAt,
    createdAt: new Date().toISOString(),
  };
  contactsStore.unshift(contact);
  return contact;
}
