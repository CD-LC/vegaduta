// Wire side of the live run stream (see liveRunStream.ts for the wire facts):
// an incremental text/event-stream parser and the run event shape parsed out
// of each frame. Re-exported through ./liveRunStream.

// ---------------------------------------------------------------------------
// SSE parsing
// ---------------------------------------------------------------------------

export type SseFrame = { event: string; data: string; id: string | null };

/**
 * Incremental text/event-stream parser (WHATWG rules): CR, LF and CRLF line
 * ends (a CRLF may straddle two chunks), multi-line `data:`, `:` comments,
 * `id:` persisting across frames, `retry:`. A frame without any data line is
 * not dispatched; a partial frame at end of stream is discarded.
 */
export class SseParser {
  lastEventId: string | null;
  retryMs: number | null = null;
  /** Comment lines seen so far - heartbeats (`:hb`) are comments. */
  commentCount = 0;
  private buf = "";
  private data: string[] = [];
  private hasData = false;
  private event = "";
  private skipLF = false;
  private started = false;

  constructor(lastEventId: string | null = null) {
    this.lastEventId = lastEventId;
  }

  push(chunk: string): SseFrame[] {
    let text = chunk;
    if (!this.started && text.length) {
      this.started = true;
      if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
    }
    // A CR ended the previous chunk: its LF half (if any) opens this one.
    if (this.skipLF && text.startsWith("\n")) text = text.slice(1);
    if (text.length) this.skipLF = false;
    this.buf += text;
    const frames: SseFrame[] = [];
    let start = 0;
    const b = this.buf;
    for (let i = 0; i < b.length; i++) {
      const c = b.charCodeAt(i);
      if (c !== 10 && c !== 13) continue;
      const line = b.slice(start, i);
      if (c === 13) {
        if (i + 1 < b.length) {
          if (b.charCodeAt(i + 1) === 10) i++;
        } else {
          this.skipLF = true;
        }
      }
      start = i + 1;
      this.line(line, frames);
    }
    this.buf = b.slice(start);
    return frames;
  }

  private line(line: string, out: SseFrame[]): void {
    if (line === "") {
      if (this.hasData) out.push({ event: this.event || "message", data: this.data.join("\n"), id: this.lastEventId });
      this.data = [];
      this.hasData = false;
      this.event = "";
      return;
    }
    if (line.charCodeAt(0) === 58 /* : */) {
      this.commentCount++;
      return;
    }
    const idx = line.indexOf(":");
    const field = idx < 0 ? line : line.slice(0, idx);
    let value = idx < 0 ? "" : line.slice(idx + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    switch (field) {
      case "data":
        this.data.push(value);
        this.hasData = true;
        break;
      case "event":
        this.event = value;
        break;
      case "id":
        if (!value.includes("\u0000")) this.lastEventId = value === "" ? null : value;
        break;
      case "retry":
        if (/^\d+$/.test(value)) this.retryMs = Number(value);
        break;
      default:
        break;
    }
  }
}

/** One-shot parse of a complete text/event-stream body (tests, non-streaming fallbacks). */
export function parseSseChunk(text: string, lastEventId: string | null = null): SseFrame[] {
  return new SseParser(lastEventId).push(text);
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

export type LiveRunEventType =
  | "snapshot"
  | "run.started"
  | "run.resumed"
  | "node.started"
  | "node.token"
  | "node.tool_call"
  | "node.tool_result"
  | "node.tool_error"
  | "node.agent_event"
  | "node.completed"
  | "run.awaiting"
  | "run.finished"
  | "error"
  | (string & {});

export type LiveRunEvent = {
  /** null for snapshot/error frames, which carry no seq. */
  seq: number | null;
  type: LiveRunEventType;
  runId: string | null;
  nodeId: string | null;
  /** Epoch ms, null when missing/unparseable. */
  ts: number | null;
  data: Record<string, unknown>;
};

export function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === "object" && !Array.isArray(v);
}

export function numOrNull(v: unknown): number | null {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

export function strOrNull(v: unknown): string | null {
  if (v == null) return null;
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  try {
    return JSON.stringify(v);
  } catch {
    return null;
  }
}

/** Instant as ISO string, epoch ms, or Jackson's epoch-seconds decimal. */
export function parseTs(v: unknown): number | null {
  if (typeof v === "number" && Number.isFinite(v)) return v < 1e11 ? Math.round(v * 1000) : v;
  if (typeof v === "string" && v.trim()) {
    const asNum = Number(v);
    if (Number.isFinite(asNum)) return parseTs(asNum);
    const t = Date.parse(v);
    return Number.isFinite(t) ? t : null;
  }
  return null;
}

/** An SSE frame as a run event; null for frames that are not JSON objects. */
export function parseLiveEvent(frame: SseFrame): LiveRunEvent | null {
  let payload: unknown;
  try {
    payload = JSON.parse(frame.data);
  } catch {
    return null;
  }
  if (!isRecord(payload)) return null;
  const type = typeof payload.type === "string" && payload.type ? payload.type : frame.event;
  const seq = numOrNull(payload.seq) ?? (frame.id != null && type !== "snapshot" ? numOrNull(frame.id) : null);
  const data: Record<string, unknown> = isRecord(payload.data) ? payload.data : {};
  // `error` frames put the message at the top level.
  if (type === "error" && typeof payload.message === "string" && data.message == null) data.message = payload.message;
  return {
    seq,
    type,
    runId: strOrNull(payload.runId),
    nodeId: strOrNull(payload.nodeId),
    ts: parseTs(payload.ts),
    data,
  };
}
