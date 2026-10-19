import { failedDisplayText, isFailedMessage } from "./failedTurn";

// Chat branching - pure helpers. No React, no network: the conversation-tree rules the chat
// panel relies on, kept here so they can be tested on their own.
//
// The server (core/agents ChatBranchService, behind app.chat.branching.enabled) owns the tree and
// hands the browser the ACTIVE path: the messages currently shown and replayed to the model, each
// carrying its position among its sibling versions. A "version" is an alternative to a message -
// an edited user message, or a regenerated assistant reply. Children of one parent are siblings.

export type BranchRole = "user" | "assistant";

/** One message of the active path, as GET .../messages?branch=active returns it. */
export interface BranchMessage {
  id: string;
  parentId: string | null;
  role: BranchRole;
  content: string;
  createdAt?: string;
  /** 0-based position among the versions of this message. */
  siblingIndex: number;
  /** How many versions exist, this one included. The switcher only shows above 1. */
  siblingCount: number;
  /** A failed reply recorded as an assistant message (see failedTurn.ts for every accepted marker). */
  status?: string;
  error?: boolean;
}

export interface BranchPath {
  sessionId: string;
  activeLeafId: string | null;
  messages: BranchMessage[];
}

export interface BranchCapabilities {
  enabled: boolean;
}

export interface BranchSiblings {
  messageId: string;
  ids: string[];
  index: number;
  count: number;
}

/** A node of a whole tree, for the map-based helpers. `order` is creation order among siblings. */
export interface TreeNode {
  id: string;
  parentId: string | null;
  role: BranchRole;
  content: string;
  order: number;
  createdAt?: string | number;
}

export type MessageMap = Record<string, TreeNode>;

/** Defensive bound so a corrupt parent cycle can never loop forever. */
const MAX_DEPTH = 10000;

export function toMessageMap(nodes: TreeNode[]): MessageMap {
  const map: MessageMap = {};
  for (const n of nodes) map[n.id] = n;
  return map;
}

function compareSiblings(a: TreeNode, b: TreeNode): number {
  if (a.order !== b.order) return a.order - b.order;
  const ta = a.createdAt == null ? 0 : new Date(a.createdAt).getTime();
  const tb = b.createdAt == null ? 0 : new Date(b.createdAt).getTime();
  if (ta !== tb) return ta - tb;
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

/** The path from the root down to `leafId`, oldest first. Empty for a null or unknown leaf; stops on a missing parent or a cycle. */
export function activePathFromMap(map: MessageMap, leafId: string | null | undefined): TreeNode[] {
  if (!leafId) return [];
  const reversed: TreeNode[] = [];
  const seen = new Set<string>();
  let cursor: string | null = leafId;
  while (cursor && reversed.length < MAX_DEPTH) {
    const node: TreeNode | undefined = map[cursor];
    if (!node || seen.has(cursor)) break;
    seen.add(cursor);
    reversed.push(node);
    cursor = node.parentId;
  }
  return reversed.reverse();
}

/** The children of `parentId` (null = the roots), in version order. */
export function childrenOf(map: MessageMap, parentId: string | null): TreeNode[] {
  return Object.values(map)
    .filter((n) => n.parentId === parentId)
    .sort(compareSiblings);
}

/** Every version of `id` (itself included) in order, and where `id` sits. Null for an unknown message. */
export function siblingsOf(map: MessageMap, id: string): { ids: string[]; index: number; count: number } | null {
  const self = map[id];
  if (!self) return null;
  const ids = childrenOf(map, self.parentId).map((n) => n.id);
  return { ids, index: ids.indexOf(id), count: ids.length };
}

/** The version `delta` steps away (-1 previous, +1 next), or null at either end: navigation is clamped, it does not wrap. */
export function navigateSibling(map: MessageMap, id: string, delta: number): string | null {
  const info = siblingsOf(map, id);
  if (!info) return null;
  const target = info.index + delta;
  if (target < 0 || target >= info.count) return null;
  return info.ids[target];
}

/**
 * Where a switch to `id` lands: keep descending into the newest child (highest `order`) until there
 * is none. Mirrors the server's choice, which uses creation time, so the browser can predict the
 * path it will get back.
 */
export function nextLeaf(map: MessageMap, id: string | null | undefined): string | null {
  if (!id || !map[id]) return null;
  let cursor = id;
  for (let depth = 0; depth < MAX_DEPTH; depth++) {
    const kids = childrenOf(map, cursor);
    if (kids.length === 0) return cursor;
    cursor = kids[kids.length - 1].id;
  }
  return cursor;
}

/** "2/3": the 1-based position the switcher shows. */
export function siblingLabel(index: number, count: number): string {
  return `${index + 1}/${count}`;
}

// ---- the chat panel's local turns ------------------------------------------------------------

/** The shape ChatPanel keeps per turn; only the fields branching reads or writes are named. */
export interface BranchTurn {
  role: string;
  content: string;
  messageId?: string;
  siblingIndex?: number;
  siblingCount?: number;
  edgeTier?: number | null;
  [extra: string]: any;
}

/**
 * Branching only works on conversations the server saw end to end. A reply answered on-device never
 * reached the server, so its session has a different history than the panel shows.
 */
export function canBranch(turns: BranchTurn[]): boolean {
  return !turns.some((t) => t.edgeTier != null);
}

/**
 * Attaches the server's ids and version positions to the panel's local turns by position, keeping
 * everything local (activity trace, request snapshots, eval refs, streamed text). Returns null when
 * the two do not line up - different length or roles - so the caller rebuilds from the server path
 * instead of mislabelling a message.
 */
export function mergeServerPath(turns: BranchTurn[], path: BranchPath): BranchTurn[] | null {
  const server = path.messages;
  if (turns.length !== server.length) return null;
  for (let i = 0; i < turns.length; i++) {
    if (turns[i].role !== server[i].role) return null;
  }
  return turns.map((t, i) => ({
    ...t,
    // A failed reply the server recorded shows as the error bubble even if this panel did not know it failed.
    ...(server[i].role === "assistant" && isFailedMessage(server[i])
      ? { failed: true, content: failedDisplayText(server[i]) }
      : {}),
    messageId: server[i].id,
    siblingIndex: server[i].siblingIndex,
    siblingCount: server[i].siblingCount,
  }));
}

/**
 * Local turns rebuilt from the server's active path (after a switch, or when `mergeServerPath`
 * could not line up). `extrasById` restores what the panel knew about a message the last time it
 * was on screen - its trace, request snapshots, eval ref - so flipping back and forth loses nothing.
 */
export function turnsFromPath(path: BranchPath, extrasById: Record<string, Partial<BranchTurn>> = {}): BranchTurn[] {
  return path.messages.map((m) => ({
    ...(extrasById[m.id] || {}),
    role: m.role,
    content: m.role === "assistant" && isFailedMessage(m) ? failedDisplayText(m) : m.content,
    ...(m.role === "assistant" && isFailedMessage(m) ? { failed: true } : {}),
    messageId: m.id,
    siblingIndex: m.siblingIndex,
    siblingCount: m.siblingCount,
  }));
}

/** What to remember about each turn so `turnsFromPath` can restore it later. */
export function collectExtras(turns: BranchTurn[]): Record<string, Partial<BranchTurn>> {
  const out: Record<string, Partial<BranchTurn>> = {};
  for (const t of turns) {
    if (!t.messageId) continue;
    const rest: Partial<BranchTurn> = { ...t };
    delete rest.role;
    delete rest.content;
    delete rest.messageId;
    delete rest.siblingIndex;
    delete rest.siblingCount;
    out[t.messageId] = rest;
  }
  return out;
}

/** Local turns after an edit starts: everything before the edited message, then the new text and an empty reply. */
export function turnsForEdit(turns: BranchTurn[], index: number, text: string): BranchTurn[] {
  return [...turns.slice(0, index), { role: "user", content: text }, { role: "assistant", content: "" }];
}

/** Local turns after a regenerate starts: everything up to and including the user message, then an empty reply. */
export function turnsForRegenerate(turns: BranchTurn[], assistantIndex: number): BranchTurn[] {
  return [...turns.slice(0, assistantIndex), { role: "assistant", content: "" }];
}

/** The user message a regenerate answers: the turn right before the assistant turn, when it is one. */
export function userTextBefore(turns: BranchTurn[], assistantIndex: number): string | null {
  const prev = turns[assistantIndex - 1];
  return prev && prev.role === "user" ? prev.content : null;
}

/** The id `delta` steps from the current message in a siblings response, or null at either end (clamped, no wrap). */
export function siblingIdAt(siblings: Pick<BranchSiblings, "ids" | "index">, delta: number): string | null {
  const target = siblings.index + delta;
  if (target < 0 || target >= siblings.ids.length) return null;
  return siblings.ids[target];
}
