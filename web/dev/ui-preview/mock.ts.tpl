// LOCAL PREVIEW ONLY (never committed): a fetch mock so real page components render with fixture data.
import { FIXTURES } from "./fixtures";

export interface PvReq { method: string; path: string; url: URL; body: any }
export interface PvResp { status?: number; headers?: Record<string, string>; body?: any }
export interface PvRoute { method?: string; match: string | RegExp; respond: PvResp | ((r: PvReq) => PvResp) }

/** Paged JSON list answering limit/offset with X-Total-Count and X-Has-More, like the real endpoints. */
export function paged(items: any[], url: URL, wrap?: (page: any[], total: number) => any): PvResp {
  const limit = Number(url.searchParams.get("limit") || 50);
  const offset = Number(url.searchParams.get("offset") || 0);
  const page = items.slice(offset, offset + limit);
  return {
    headers: { "X-Total-Count": String(items.length), "X-Has-More": String(offset + limit < items.length) },
    body: wrap ? wrap(page, items.length) : page,
  };
}

export const misses: string[] = [];

export function installMock(): void {
  const w = window as any;
  if (w.__pvInstalled) return;
  w.__pvInstalled = true;
  const real = w.fetch.bind(w);
  w.fetch = async (input: any, init: any = {}) => {
    const urlStr = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    let url: URL;
    try { url = new URL(urlStr, location.origin); } catch { return real(input, init); }
    const isApi = url.pathname.startsWith("/api") || url.pathname.startsWith("/v1") || url.pathname.startsWith("/actuator");
    if (!isApi) return real(input, init);
    const method = (init.method || (typeof input !== "string" && (input as Request).method) || "GET").toUpperCase();
    let body: any = undefined;
    try { body = init.body ? JSON.parse(init.body) : undefined; } catch { body = init.body; }
    const req: PvReq = { method, path: url.pathname, url, body };
    const hit = FIXTURES.find((r) => (!r.method || r.method === method) && (typeof r.match === "string" ? r.match === url.pathname : r.match.test(url.pathname)));
    if (!hit) {
      const line = `${method} ${url.pathname}${url.search}`;
      if (!misses.includes(line)) { misses.push(line); console.warn("[preview] no fixture for", line); }
      return new Response(JSON.stringify(method === "GET" ? { message: "no fixture" } : {}), { status: method === "GET" ? 404 : 200, headers: { "Content-Type": "application/json" } });
    }
    const out = typeof hit.respond === "function" ? hit.respond(req) : hit.respond;
    const text = out.body === undefined ? "" : typeof out.body === "string" ? out.body : JSON.stringify(out.body);
    return new Response(text, { status: out.status ?? 200, headers: { "Content-Type": "application/json", ...(out.headers || {}) } });
  };
}
