// Server-only guards for this app's own public route handlers (app/api/*).
// Security audit 2026-10: request.json()/request.text() buffer whatever the
// client sends, and the handlers had no per-caller limit, so one script could
// exhaust memory or flood the logs.

/**
 * Reads the body as UTF-8, refusing (null) once it exceeds maxBytes - checked
 * against Content-Length up front and again while streaming, since a chunked
 * body declares no length.
 */
export async function readCappedText(request: Request, maxBytes: number): Promise<string | null> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > maxBytes) return null;
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
}

/** Client IP as Cloudflare reports it, falling back to the first X-Forwarded-For hop. */
export function clientIp(request: Request): string {
  return (
    request.headers.get("cf-connecting-ip") ??
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ??
    "unknown"
  );
}

/**
 * Fixed-window per-key limiter, in-process (per web replica). The map is
 * bounded: when full it sweeps expired windows, and if it is still full the
 * call is refused, so rotating keys cannot grow it without limit.
 */
export function createRateLimiter(max: number, windowMs: number, maxTracked = 10_000) {
  const hits = new Map<string, { start: number; count: number }>();
  return function allow(key: string, now = Date.now()): boolean {
    const entry = hits.get(key);
    if (!entry || now - entry.start > windowMs) {
      if (hits.size >= maxTracked) {
        for (const [k, v] of hits) {
          if (now - v.start > windowMs) hits.delete(k);
        }
        if (hits.size >= maxTracked) return false;
      }
      hits.set(key, { start: now, count: 1 });
      return true;
    }
    entry.count += 1;
    return entry.count <= max;
  };
}
