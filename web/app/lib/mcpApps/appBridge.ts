// Host side of the MCP Apps postMessage bridge (JSON-RPC 2.0 over window.postMessage), as a pure,
// DOM-free state machine so every security decision is unit-testable. The component (McpAppFrame.tsx)
// supplies the effects (posting into the frame, confirmation prompts, the API call) and calls handle()
// for each message event.
//
// The app is UNTRUSTED third-party code. Therefore:
//  - a message is considered only if its source is the iframe's own contentWindow AND its origin is the
//    literal "null" (the opaque origin of a sandbox without allow-same-origin);
//  - messages are JSON-RPC 2.0 shaped, size-capped and rate-limited, or ignored;
//  - the supported surface is a minimal allow-list; every other REQUEST gets a JSON-RPC error, every other
//    notification is ignored;
//  - nothing the app asks for happens without the user: links and chat messages need an explicit confirm,
//    and each tool the app wants to call needs a one-time consent (the server still applies the platform's
//    normal approvals, argument policies, classification and audit to every such call);
//  - after dispose() (teardown, collapse, unmount) every message is ignored and no late reply is posted.
//
// Spec notes: method names follow the MCP Apps profile (ui/initialize, ui/notifications/*, tools/call,
// ui/open-link, ui/message). They live in this one file; the profile is young.

export const BRIDGE_PROTOCOL_VERSION = "2025-11-21";
export const MAX_RPC_CHARS = 64 * 1024;
export const MAX_TOOL_ARGS_CHARS = 32 * 1024;
export const MAX_MESSAGE_CHARS = 2000;
export const MAX_LINK_CHARS = 2000;
export const RATE_WINDOW_MS = 10_000;
export const RATE_MAX_MESSAGES = 60;
export const MAX_INFLIGHT_TOOL_CALLS = 3;
export const MIN_APP_FRAME_HEIGHT = 80;
export const MAX_APP_FRAME_HEIGHT = 1200;

export const RPC_INVALID_REQUEST = -32600;
export const RPC_METHOD_NOT_FOUND = -32601;
export const RPC_INVALID_PARAMS = -32602;
export const RPC_INTERNAL = -32603;
export const RPC_BUSY = -32000;
export const RPC_DENIED = -32001;
export const RPC_NOT_INITIALIZED = -32002;

export interface MessageLike {
  source: unknown;
  origin: string;
  data: unknown;
}

export interface ToolCallOutcome {
  isError: boolean;
  text: string;
}

export interface AppBridgeHost {
  /** Post one JSON-RPC message into the frame. Only ever called while not disposed. */
  post: (message: Record<string, unknown>) => void;
  /** The frame's own contentWindow (re-read per message: it is null before mount). */
  frameWindow: () => unknown;
  /** The tool call this app was rendered for (already redacted server-side). */
  toolInput: () => Record<string, unknown>;
  toolResultText: () => { text: string; truncated: boolean };
  /** Routes to POST /api/mcp-apps/{appId}/tools/call (server-side approvals/policies/audit apply). */
  callTool: (name: string, args: Record<string, unknown>) => Promise<ToolCallOutcome>;
  /** One-time consent per tool name for this frame; false = user said no. */
  confirmTool: (name: string, args: Record<string, unknown>) => Promise<boolean>;
  confirmLink: (url: string) => Promise<boolean>;
  confirmMessage: (text: string) => Promise<boolean>;
  openLink: (url: string) => void;
  addMessage: (text: string) => void;
  onResize: (height: number) => void;
  theme?: () => "light" | "dark";
  now?: () => number;
}

export interface AppBridge {
  handle: (e: MessageLike) => Promise<void>;
  dispose: () => void;
  readonly disposed: boolean;
}

type RpcId = string | number;

interface ParsedRpc {
  id?: RpcId;
  method: string;
  params: Record<string, unknown>;
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function jsonSize(v: unknown): number {
  try {
    return JSON.stringify(v)?.length ?? 0;
  } catch {
    return Number.POSITIVE_INFINITY; // cyclic or unserialisable: treat as oversize
  }
}

/** JSON-RPC 2.0 request/notification shape check. Returns null for anything else (responses included). */
export function parseRpc(data: unknown): ParsedRpc | null {
  if (!isPlainObject(data)) return null;
  if (data.jsonrpc !== "2.0") return null;
  if (typeof data.method !== "string" || data.method.length === 0 || data.method.length > 128) return null;
  let id: RpcId | undefined;
  if ("id" in data && data.id !== undefined) {
    const raw = data.id;
    if (typeof raw === "string" && raw.length > 0 && raw.length <= 128) id = raw;
    else if (typeof raw === "number" && Number.isFinite(raw)) id = raw;
    else return null;
  }
  if ("result" in data || "error" in data) return null;
  const params = data.params;
  if (params !== undefined && !isPlainObject(params)) return null;
  return { id, method: data.method, params: (params as Record<string, unknown> | undefined) ?? {} };
}

/** https only, no credentials, bounded; returns the normalised href or null. */
export function validateOpenLink(raw: unknown): string | null {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > MAX_LINK_CHARS) return null;
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return null;
  }
  if (u.protocol !== "https:") return null;
  if (u.username || u.password) return null;
  if (!u.hostname) return null;
  return u.href;
}

const TOOL_NAME = /^[A-Za-z0-9_.:-]{1,200}$/;

export function mentionsUiUri(value: unknown, depth = 0): boolean {
  if (depth > 12) return true;
  if (typeof value === "string") return value.toLowerCase().includes("ui://");
  if (Array.isArray(value)) return value.some((v) => mentionsUiUri(v, depth + 1));
  if (isPlainObject(value)) {
    return Object.entries(value).some(([k, v]) => k.toLowerCase().includes("ui://") || mentionsUiUri(v, depth + 1));
  }
  return false;
}

function messageText(params: Record<string, unknown>): string | null {
  const content = params.content;
  let t: unknown;
  if (isPlainObject(content)) {
    if (content.type !== undefined && content.type !== "text") return null;
    t = content.text;
  } else {
    t = params.text;
  }
  if (typeof t !== "string") return null;
  const text = t.trim();
  if (!text || text.length > MAX_MESSAGE_CHARS) return null;
  if (params.role !== undefined && params.role !== "user") return null;
  return text;
}

export function createAppBridge(host: AppBridgeHost): AppBridge {
  let disposed = false;
  let initRequested = false;
  let delivered = false;
  let confirming = false;
  let inflight = 0;
  const recent: number[] = [];
  const now = host.now ?? (() => Date.now());

  const post = (m: Record<string, unknown>) => {
    if (disposed) return;
    try {
      host.post(m);
    } catch {
      /* the frame went away mid-reply: nothing to do */
    }
  };
  const reply = (id: RpcId, result: unknown) => post({ jsonrpc: "2.0", id, result });
  const fail = (id: RpcId | undefined, code: number, message: string) => {
    if (id !== undefined) post({ jsonrpc: "2.0", id, error: { code, message } });
  };

  const overRate = (): boolean => {
    const t = now();
    while (recent.length > 0 && t - recent[0] > RATE_WINDOW_MS) recent.shift();
    if (recent.length >= RATE_MAX_MESSAGES) return true;
    recent.push(t);
    return false;
  };

  /** Only one confirmation may be pending; a second concurrent ask is refused rather than queued. */
  const confirmOnce = async (ask: () => Promise<boolean>): Promise<boolean | "busy"> => {
    if (confirming) return "busy";
    confirming = true;
    try {
      return await ask();
    } catch {
      return false;
    } finally {
      confirming = false;
    }
  };

  const deliver = () => {
    if (delivered || disposed) return;
    delivered = true;
    post({ jsonrpc: "2.0", method: "ui/notifications/tool-input", params: { arguments: host.toolInput() } });
    const r = host.toolResultText();
    post({
      jsonrpc: "2.0",
      method: "ui/notifications/tool-result",
      params: { content: [{ type: "text", text: r.text }], isError: false, ...(r.truncated ? { _meta: { truncated: true } } : {}) },
    });
  };

  const allowedTools = new Set<string>();

  async function onToolsCall(id: RpcId | undefined, params: Record<string, unknown>) {
    if (id === undefined) return;
    if (!initRequested) return fail(id, RPC_NOT_INITIALIZED, "Call ui/initialize first.");
    const name = params.name;
    if (typeof name !== "string" || !TOOL_NAME.test(name)) return fail(id, RPC_INVALID_PARAMS, "Invalid tool name.");
    const args = params.arguments === undefined ? {} : params.arguments;
    if (!isPlainObject(args)) return fail(id, RPC_INVALID_PARAMS, "Arguments must be an object.");
    if (jsonSize(args) > MAX_TOOL_ARGS_CHARS) return fail(id, RPC_INVALID_PARAMS, "Arguments are too large.");
    if (mentionsUiUri(args)) return fail(id, RPC_INVALID_PARAMS, "ui:// references are not allowed.");
    if (inflight >= MAX_INFLIGHT_TOOL_CALLS) return fail(id, RPC_BUSY, "Too many tool calls in flight.");
    inflight++;
    try {
      if (!allowedTools.has(name)) {
        const ok = await confirmOnce(() => host.confirmTool(name, args));
        if (disposed) return;
        if (ok === "busy") return fail(id, RPC_BUSY, "Another confirmation is pending.");
        if (!ok) return fail(id, RPC_DENIED, "The user declined.");
        allowedTools.add(name);
      }
      const out = await host.callTool(name, args);
      if (disposed) return;
      reply(id, { content: [{ type: "text", text: out.text }], isError: out.isError });
    } catch {
      fail(id, RPC_INTERNAL, "Tool call failed.");
    } finally {
      inflight--;
    }
  }

  async function onOpenLink(id: RpcId | undefined, params: Record<string, unknown>) {
    if (id === undefined) return;
    const href = validateOpenLink(params.url);
    if (!href) return fail(id, RPC_INVALID_PARAMS, "Only https links are allowed.");
    const ok = await confirmOnce(() => host.confirmLink(href));
    if (disposed) return;
    if (ok === "busy") return fail(id, RPC_BUSY, "Another confirmation is pending.");
    if (!ok) return fail(id, RPC_DENIED, "The user declined.");
    host.openLink(href);
    reply(id, {});
  }

  async function onMessage(id: RpcId | undefined, params: Record<string, unknown>) {
    if (id === undefined) return;
    const text = messageText(params);
    if (!text) return fail(id, RPC_INVALID_PARAMS, "Invalid message.");
    const ok = await confirmOnce(() => host.confirmMessage(text));
    if (disposed) return;
    if (ok === "busy") return fail(id, RPC_BUSY, "Another confirmation is pending.");
    if (!ok) return fail(id, RPC_DENIED, "The user declined.");
    host.addMessage(text);
    reply(id, {});
  }

  return {
    get disposed() {
      return disposed;
    },
    dispose() {
      disposed = true;
    },
    async handle(e: MessageLike) {
      if (disposed) return;
      const fw = host.frameWindow();
      if (fw == null || e.source !== fw) return;
      if (e.origin !== "null") return;
      const rpc = parseRpc(e.data);
      if (!rpc) return;
      if (jsonSize(e.data) > MAX_RPC_CHARS) return fail(rpc.id, RPC_INVALID_REQUEST, "Message too large.");
      if (overRate()) return fail(rpc.id, RPC_BUSY, "Rate limited.");

      switch (rpc.method) {
        case "ui/initialize": {
          if (rpc.id === undefined) return;
          initRequested = true;
          reply(rpc.id, {
            protocolVersion: BRIDGE_PROTOCOL_VERSION,
            hostInfo: { name: "VegaDuta", version: "1" },
            hostCapabilities: { openLinks: {}, serverTools: {}, message: { text: {} } },
            hostContext: { theme: host.theme?.() ?? "light", displayMode: "inline", platform: "web" },
          });
          return;
        }
        case "ui/notifications/initialized":
          if (initRequested) deliver();
          return;
        case "ui/notifications/size-changed": {
          const h = rpc.params.height;
          if (typeof h === "number" && Number.isFinite(h)) {
            host.onResize(Math.min(MAX_APP_FRAME_HEIGHT, Math.max(MIN_APP_FRAME_HEIGHT, Math.round(h))));
          }
          return;
        }
        case "ping":
          if (rpc.id !== undefined) reply(rpc.id, {});
          return;
        case "tools/call":
          return onToolsCall(rpc.id, rpc.params);
        case "ui/open-link":
          return onOpenLink(rpc.id, rpc.params);
        case "ui/message":
          return onMessage(rpc.id, rpc.params);
        default:
          // resources/*, prompts/*, ui/request-display-mode, sampling, anything new: refused.
          // (A notification has no id and is silently dropped.)
          fail(rpc.id, RPC_METHOD_NOT_FOUND, "Method not supported by this host.");
      }
    },
  };
}
