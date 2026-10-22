// Hand-written runtime for the generated client (client.ts). Zero dependencies: it only uses the
// platform's fetch / Headers / TextDecoder, so it runs on Node 18+, Deno, Bun and browsers.

/** No built-in deployment: pass `baseUrl` (your VegaDūta API origin) or set VEGADUTA_BASE_URL (Node). */
export const DEFAULT_BASE_URL = "";

/**
 * The server sends this literal as the final SSE chunk when a response dies mid-stream, after the
 * HTTP status was already committed (it mirrors AgentController.STREAM_ERROR_MARKER in core).
 * The client turns it into a StreamInterruptedError instead of yielding it as text.
 */
export const STREAM_ERROR_MARKER = "@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@";

export type QueryValue =
  | string
  | number
  | boolean
  | null
  | undefined
  | ReadonlyArray<string | number | boolean>;
export type QueryParams = Record<string, QueryValue>;
export type HeaderParams = Record<string, string>;

/** Per-call options. Generated methods narrow `query` and `headers` to the operation's real parameters. */
export interface CallOptions<Q = QueryParams, H = HeaderParams> {
  query?: Q;
  headers?: H;
  signal?: AbortSignal;
  /** Overrides the client's `timeoutMs` for this call only. */
  timeoutMs?: number;
}

/** A bearer token, or a function returning one (called on every request, so it may refresh). */
export type TokenSource = string | (() => string | Promise<string>);

export interface ClientOptions {
  /** API origin of your deployment, e.g. https://api.example.com (else VEGADUTA_BASE_URL). No trailing slash needed. */
  baseUrl?: string;
  /** Keycloak access token (JWT) or a vmcp_ grant key, sent as `Authorization: Bearer ...`. */
  token?: TokenSource;
  /** Custom fetch (tests, proxies, older runtimes). Defaults to the global fetch. */
  fetch?: typeof fetch;
  /** Headers added to every request. */
  headers?: Record<string, string>;
  /**
   * Per-request timeout in milliseconds. `0` (the default) means no client-side
   * deadline; the caller's `signal` still applies. Does NOT bound SSE streams
   * (a stream lives as long as the server keeps it - it would be killed mid-answer).
   */
  timeoutMs?: number;
  /**
   * Retries for idempotent GET/HEAD calls that fail with 429, a 5xx, a `Retry-After`
   * response or a network error (default 2). Mutating methods are never retried.
   */
  maxRetries?: number;
  /** Extra segment appended to the `User-Agent` product token (your app name). */
  userAgent?: string;
}

/** Thrown for every non-2xx response. `body` is the parsed JSON error (see ErrorBody) or the raw text. */
export class ApiError extends Error {
  readonly status: number;
  readonly body: unknown;
  /** Machine-readable `ErrorBody.code` when the server sent one. */
  readonly code?: string;
  /** Response headers (kept: request ids, rate-limit and `Retry-After` live here). */
  readonly headers: Headers;
  /** `Retry-After` in milliseconds when the server asked us to wait. */
  readonly retryAfterMs?: number;

  constructor(status: number, body: unknown, headers?: Headers, message?: string) {
    super(message ?? describeError(status, body));
    this.name = "ApiError";
    this.status = status;
    this.body = body;
    this.headers = headers ?? new Headers();
    if (body && typeof body === "object") {
      const code = (body as Record<string, unknown>).code;
      if (typeof code === "string" && code) this.code = code;
    }
    const retryAfter = this.headers.get("retry-after");
    if (retryAfter) {
      const secs = Number(retryAfter);
      if (Number.isFinite(secs)) this.retryAfterMs = Math.max(0, secs * 1000);
    }
  }
}

/** Thrown when the request could not be made at all (DNS, TLS, refused, aborted deadline). */
export class NetworkError extends Error {
  readonly cause?: unknown;
  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = "NetworkError";
    this.cause = cause;
  }
}

/** Thrown while iterating a stream that the server aborted after it had started. */
export class StreamInterruptedError extends Error {
  constructor(message?: string) {
    super(message || "The response stream was interrupted. Please try again.");
    this.name = "StreamInterruptedError";
  }
}

function describeError(status: number, body: unknown): string {
  if (body && typeof body === "object") {
    const o = body as Record<string, unknown>;
    if (typeof o.error === "string" && o.error) return o.error;
    if (typeof o.message === "string" && o.message) return o.message;
  }
  if (typeof body === "string" && body) return body.slice(0, 300);
  return `HTTP ${status}`;
}

export interface RequestArgs {
  method: string;
  path: string;
  query?: object;
  headers?: object;
  body?: unknown;
  /** How `body` is sent: JSON (default), a FormData, or passed through with `contentType`. */
  bodyType?: "json" | "multipart" | "text" | "raw";
  contentType?: string;
  /** How a successful response is read (default json). */
  response?: "json" | "text" | "blob" | "void";
  signal?: AbortSignal;
}

/**
 * Incremental Server-Sent Events decoder for this platform's streams.
 *
 * Each event is the data lines up to the next blank line, joined with "\n" (Spring writes a chunk
 * containing newlines as several `data:` lines). It strips EXACTLY the five characters `data:`
 * and nothing else: the server never adds a framing space, so a chunk that is a single space
 * (a common word-boundary token) arrives as `data: ` and must be kept. This deliberately differs
 * from the generic SSE spec, which would drop one leading space.
 */
export class SseDecoder {
  private buffer = "";
  private data: string[] = [];

  /** Feed decoded text; returns the events completed by it. */
  push(text: string): string[] {
    this.buffer += text;
    const lines = this.buffer.split("\n");
    this.buffer = lines.pop() ?? "";
    const out: string[] = [];
    for (const line of lines) this.consume(line, out);
    return out;
  }

  /** Call at end of stream: flushes a final unterminated line and event. */
  flush(): string[] {
    const out: string[] = [];
    if (this.buffer) {
      this.consume(this.buffer, out);
      this.buffer = "";
    }
    if (this.data.length) {
      out.push(this.data.join("\n"));
      this.data = [];
    }
    return out;
  }

  private consume(raw: string, out: string[]): void {
    const line = raw.endsWith("\r") ? raw.slice(0, -1) : raw;
    if (line === "") {
      if (this.data.length) {
        out.push(this.data.join("\n"));
        this.data = [];
      }
      return;
    }
    if (line.startsWith("data:")) this.data.push(line.slice(5));
    // event:, id:, retry: and :comment lines carry no payload on this API.
  }
}

/** Yields the payload of every SSE event in a response body. Cancels the body when the consumer stops early. */
export async function* parseSse(body: ReadableStream<Uint8Array>): AsyncGenerator<string, void, undefined> {
  const reader = body.getReader();
  const text = new TextDecoder();
  const decoder = new SseDecoder();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      for (const event of decoder.push(text.decode(value, { stream: true }))) yield event;
    }
    for (const event of decoder.push(text.decode())) yield event;
    for (const event of decoder.flush()) yield event;
  } finally {
    await reader.cancel().catch(() => undefined);
  }
}

function encode(value: string | number | boolean): string {
  return encodeURIComponent(String(value));
}

export class HttpClient {
  private readonly baseUrl: string;
  private readonly token?: TokenSource;
  private readonly fetchImpl: typeof fetch;
  private readonly defaultHeaders: Record<string, string>;
  private readonly timeoutMs: number;
  private readonly maxRetries: number;
  private readonly userAgent: string;

  constructor(options: ClientOptions = {}) {
    const fromEnv = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env
      ?.VEGADUTA_BASE_URL;
    const base = options.baseUrl || fromEnv || DEFAULT_BASE_URL;
    if (!base) {
      throw new Error("baseUrl is required: pass your VegaDuta API origin (e.g. https://api.example.com) or set VEGADUTA_BASE_URL");
    }
    this.baseUrl = base.replace(/\/+$/, "");
    this.token = options.token;
    this.defaultHeaders = options.headers ?? {};
    this.fetchImpl = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
    this.timeoutMs = options.timeoutMs ?? 0;
    this.maxRetries = Math.max(0, options.maxRetries ?? 2);
    this.userAgent = ["vegaduta-sdk-ts", options.userAgent].filter(Boolean).join(" ");
  }

  /** Performs a request and returns the parsed body; throws ApiError for non-2xx. */
  async request<T>(args: RequestArgs): Promise<T> {
    const kind = args.response ?? "json";
    const accept = kind === "json" ? "application/json" : "*/*";
    const retryable = isRetryableMethod(args.method);
    let refreshed = false;
    let attempt = 0;
    for (;;) {
      try {
        const res = await this.fetchImpl(this.url(args), await this.init(args, accept));
        if (!res.ok) {
          const err = await toApiError(res);
          if (err.status === 401 && typeof this.token === "function" && !refreshed) {
            refreshed = true; // re-resolve the token source once and try again
            continue;
          }
          if (retryable && attempt < this.maxRetries && isRetryableStatus(err.status)) {
            await sleep(err.retryAfterMs ?? backoffMs(attempt++));
            continue;
          }
          throw err;
        }
        return (await readBody(res, kind)) as T;
      } catch (e) {
        if (e instanceof ApiError) throw e;
        const net = new NetworkError(describeNetworkError(e), e);
        if (retryable && attempt < this.maxRetries && !isAbort(e)) {
          await sleep(backoffMs(attempt++));
          continue;
        }
        throw net;
      }
    }
  }

  /** Performs a request that answers with Server-Sent Events and yields each event's payload. */
  async *stream(args: RequestArgs): AsyncGenerator<string, void, undefined> {
    // No timeout and no retries: a stream is not idempotent and may legitimately idle.
    let res: Response;
    try {
      res = await this.fetchImpl(this.url(args), await this.init(args, "text/event-stream"));
      if (res.status === 401 && typeof this.token === "function") {
        res = await this.fetchImpl(this.url(args), await this.init(args, "text/event-stream"));
      }
    } catch (e) {
      throw new NetworkError(describeNetworkError(e), e);
    }
    if (!res.ok) throw await toApiError(res);
    if (!res.body) {
      throw new ApiError(res.status, "response has no body", res.headers, `HTTP ${res.status}: the server returned an empty SSE stream`);
    }
    for await (const payload of parseSse(res.body)) {
      if (payload.startsWith(STREAM_ERROR_MARKER)) {
        throw new StreamInterruptedError(payload.slice(STREAM_ERROR_MARKER.length));
      }
      yield payload;
    }
  }

  private url(args: RequestArgs): string {
    const parts: string[] = [];
    for (const [key, value] of Object.entries((args.query ?? {}) as Record<string, unknown>)) {
      if (value === undefined || value === null) continue;
      if (Array.isArray(value)) {
        for (const item of value) parts.push(`${encodeURIComponent(key)}=${encode(item)}`);
      } else {
        parts.push(`${encodeURIComponent(key)}=${encode(value as string | number | boolean)}`);
      }
    }
    const qs = parts.length ? `?${parts.join("&")}` : "";
    return `${this.baseUrl}${args.path}${qs}`;
  }

  private async init(args: RequestArgs, accept: string): Promise<RequestInit> {
    const headers = new Headers();
    for (const [k, v] of Object.entries(this.defaultHeaders)) headers.set(k, v);
    const token = typeof this.token === "function" ? await this.token() : this.token;
    if (token) headers.set("Authorization", `Bearer ${token}`);
    headers.set("Accept", accept);
    headers.set("User-Agent", this.userAgent);
    for (const [k, v] of Object.entries((args.headers ?? {}) as Record<string, unknown>)) {
      if (v !== undefined && v !== null) headers.set(k, String(v));
    }
    let body: BodyInit | undefined;
    if (args.body !== undefined && args.body !== null) {
      const type = args.bodyType ?? "json";
      if (type === "json") {
        headers.set("Content-Type", "application/json");
        body = JSON.stringify(args.body);
      } else if (type === "multipart") {
        body = args.body as FormData; // fetch adds the multipart boundary itself
      } else {
        if (args.contentType && !headers.has("Content-Type")) headers.set("Content-Type", args.contentType);
        body = args.body as BodyInit;
      }
    }
    const perCall = (args as { timeoutMs?: number }).timeoutMs;
    const deadline = perCall ?? this.timeoutMs;
    let signal = args.signal;
    if (deadline > 0) {
      const timeout = AbortSignal.timeout(deadline);
      signal = signal ? AbortSignal.any([signal, timeout]) : timeout;
    }
    return { method: args.method, headers, body, signal };
  }
}

const RETRYABLE_STATUS = new Set([408, 425, 429, 500, 502, 503, 504]);

function isRetryableMethod(method: string): boolean {
  const m = method.toUpperCase();
  return m === "GET" || m === "HEAD";
}

function isRetryableStatus(status: number): boolean {
  return RETRYABLE_STATUS.has(status);
}

function backoffMs(attempt: number): number {
  // 250ms, 500ms, 1s, ... capped at 5s, with up to 40% jitter against herding.
  return Math.min(5000, 250 * 2 ** attempt) * (1 - Math.random() * 0.4);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === "AbortError";
}

function describeNetworkError(e: unknown): string {
  if (isAbort(e)) return "The request was aborted (caller signal or client timeout).";
  if (e instanceof Error && e.message) return e.message;
  return "The request could not be completed.";
}

async function toApiError(res: Response): Promise<ApiError> {
  const text = await res.text().catch(() => "");
  let body: unknown = text;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  return new ApiError(res.status, body, res.headers);
}

async function readBody(res: Response, kind: "json" | "text" | "blob" | "void"): Promise<unknown> {
  if (kind === "void") {
    await res.arrayBuffer().catch(() => undefined);
    return undefined;
  }
  if (kind === "blob") return res.blob();
  const text = await res.text();
  if (kind === "text") return text;
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    // A 2xx we cannot parse is still a failure the caller must see - a bare
    // SyntaxError would pretend it was a programming bug, not a server answer.
    throw new ApiError(res.status, text, res.headers, `HTTP ${res.status}: expected JSON, got an unparseable body`);
  }
}
