// Content-Security-Policy, ENFORCED, built per request (security review
// FE-002 / SEC-021, 2026-09-30). Until then this file produced a report-only
// policy with 'unsafe-inline' 'unsafe-eval' and all of cdn.jsdelivr.net in
// script-src, i.e. no XSS backstop at all.
//
// WHY PER REQUEST. next.config.js headers() are computed at `next build` and
// frozen into the routes manifest, so a policy there can only name the hosts
// the build happened to know. This app is "one image, any domain" (see
// lib/env.ts): the API, auth and code-intel origins are runtime config. And a
// script nonce must be fresh for every response. proxy.js therefore generates
// a nonce, builds the policy here, and sets it on the RESPONSE and on the
// REQUEST headers: Next.js reads the request's Content-Security-Policy to find
// the nonce and stamps it on its own framework/chunk <script> tags, and the
// root layout reads x-nonce for its inline window.__ENV / no-flash scripts.
// Every page is dynamically rendered (the layout reads cookies()/headers()),
// which a nonce requires.
//
// SCRIPT POLICY: 'nonce-…' 'strict-dynamic'. Only scripts carrying this
// response's nonce run; scripts THEY load (Next's chunks, next/dynamic
// imports, lazily injected vendor scripts such as /vendored/babel.min.js or
// Razorpay's checkout.js, import() of transformers.js' ONNX factory from a
// blob: URL, importScripts() in workers) are trusted transitively. CSP3
// browsers ignore 'self'/host sources when 'strict-dynamic' is present; 'self'
// stays only as a CSP2 fallback. No 'unsafe-eval' in production (next dev
// needs it for React Refresh, so it is added in development only).
// 'wasm-unsafe-eval' is kept on every page: WebAssembly compilation for the
// on-device AI path (WebLLM, ONNX Runtime, MediaPipe) can start from most
// console pages and from /try, and it permits wasm compilation only, never JS
// eval.
//
// OPERATOR ESCAPE HATCH: CSP_REPORT_ONLY=true (server env, read at runtime)
// sends the same policies as Content-Security-Policy-Report-Only instead. It
// is for diagnosing a violation on a custom deployment, not a resting state.
//
// Third-party hosts below are fixed because the browser code that calls them
// is fixed:
// - On-device AI (lib/edge/**) fetches model weights and runtimes straight
//   from the browser: huggingface.co (302s to the *.hf.co CDNs), WebLLM model
//   libraries from raw.githubusercontent.com, ONNX Runtime Web's .wasm from
//   cdn.jsdelivr.net (transformers.js' default wasmPaths), and the MediaPipe
//   face runtime + model from cdn.jsdelivr.net / storage.googleapis.com
//   (faceWorker.ts). jsdelivr is in connect-src ONLY - it is not a script
//   source; the App Builder's Babel and QR encoder are self-hosted.
// - Networked LOCAL tools (lib/edge/localToolsWeb.ts): en.wikipedia.org,
//   open-meteo, api.frankfurter.dev.
// - get.geojs.io: the coarse geo hint LanguageChoicePrompt/WeatherThemeEngine
//   read. Razorpay: checkout, only reachable when billing is enabled.
// - frame-src: lib/auth.ts's silent-SSO iframe passes through Keycloak's
//   origin; the App Builder frames a deployed preview on any https host.
import { getPublicEnv, type PublicValueKey } from "./env";

export const CSP_HEADER = "Content-Security-Policy";
export const CSP_REPORT_ONLY_HEADER = "Content-Security-Policy-Report-Only";
/** Request header the root layout reads to stamp its inline scripts. */
export const NONCE_HEADER = "x-nonce";

/** Static files that are served with their own policy (see staticFilePolicy). */
export const SILENT_CHECK_SSO_PATH = "/silent-check-sso.html";
export const SANDBOX_FRAME_PATH = "/sandbox-frame.html";
export const APP_FRAME_PATH = "/app-frame.html";
export const RUN_APP_PATH = "/run-app.html";

type EnvReader = (key: PublicValueKey) => string | undefined;

const EDGE_AND_TOOL_CONNECT_HOSTS = [
  "https://huggingface.co",
  "https://*.hf.co",
  "https://raw.githubusercontent.com",
  "https://cdn.jsdelivr.net",
  "https://storage.googleapis.com",
  "https://en.wikipedia.org",
  "https://geocoding-api.open-meteo.com",
  "https://api.open-meteo.com",
  "https://api.frankfurter.dev",
  "https://get.geojs.io",
  "https://api.razorpay.com",
  "https://lumberjack.razorpay.com",
];

// Browser-facing first-party services whose origin goes into connect-src.
const CONNECT_ORIGIN_KEYS: PublicValueKey[] = [
  "NEXT_PUBLIC_API_BASE_URL",
  "NEXT_PUBLIC_KEYCLOAK_URL",
  "NEXT_PUBLIC_CODE_INTEL_URL",
];

// Scheme + host (+ port) only. Anything that is not a plain http(s) origin is
// dropped rather than risk a malformed value widening or breaking the policy.
const SAFE_ORIGIN = /^https?:\/\/[A-Za-z0-9.-]+(:\d{1,5})?$|^https?:\/\/\[[0-9A-Fa-f:.]+\](:\d{1,5})?$/;

export function originOf(url: string | undefined): string | null {
  if (!url) return null;
  try {
    const origin = new URL(url).origin;
    return SAFE_ORIGIN.test(origin) ? origin : null;
  } catch {
    return null;
  }
}

function unique(values: (string | null)[]): string[] {
  return [...new Set(values.filter((v): v is string => !!v))];
}

function firstPartyOrigins(read: EnvReader): string[] {
  return unique(CONNECT_ORIGIN_KEYS.map((k) => originOf(read(k))));
}

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

/**
 * Plain-websocket allowance for a console served from this machine. `wss:` does not cover
 * `ws://localhost:7880`, which is what a local LiveKit (voice and camera calls) is reached on, so
 * the browser blocked the call before it ever reached the server ("The voice service could not
 * be reached", 2026-10-18). Only when a first-party service is itself on a loopback host (a
 * personal or development install), and only for that host: a hosted install is untouched.
 */
function loopbackWebsockets(origins: string[]): string[] {
  const hosts = origins.map((o) => new URL(o).hostname).filter((h) => LOOPBACK_HOSTS.has(h));
  return unique(hosts.map((h) => `ws://${h}:*`));
}

/**
 * Frames for the Assistant's Preview pane: a dev server it started listens on this machine's loopback
 * (a published workspace-runner port), so the page is `http://localhost:<port>`. Same rule as the
 * websockets above: only when the console itself is served from a loopback host.
 */
function loopbackFrames(origins: string[]): string[] {
  const hosts = origins.map((o) => new URL(o).hostname).filter((h) => LOOPBACK_HOSTS.has(h));
  return unique(hosts.map((h) => `http://${h}:*`));
}

/** Which header carries the policy: enforced unless CSP_REPORT_ONLY=true. */
export function cspHeaderName(reportOnly: string | undefined = process.env.CSP_REPORT_ONLY): string {
  return (reportOnly || "").trim().toLowerCase() === "true" ? CSP_REPORT_ONLY_HEADER : CSP_HEADER;
}

/** 128 random bits, base64 - a fresh value per response. */
export function createNonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

const NONCE_SHAPE = /^[A-Za-z0-9+/=_-]{16,64}$/;

export interface PageCspOptions {
  nonce: string;
  read?: EnvReader;
  /** next dev: React Refresh evaluates code, so 'unsafe-eval' (and ws:) are added. */
  dev?: boolean;
  /** /a/embed/*: the chat widget must be frameable by any customer site. */
  frameable?: boolean;
}

/** The policy for every rendered page. */
export function buildPageCsp({ nonce, read = getPublicEnv, dev = false, frameable = false }: PageCspOptions): string {
  if (!NONCE_SHAPE.test(nonce)) throw new Error("buildPageCsp: malformed nonce");
  const authOrigin = originOf(read("NEXT_PUBLIC_KEYCLOAK_URL"));
  const script = [
    "'self'",
    `'nonce-${nonce}'`,
    "'strict-dynamic'",
    "'wasm-unsafe-eval'",
    ...(dev ? ["'unsafe-eval'"] : []),
  ];
  return [
    "default-src 'self'",
    `script-src ${script.join(" ")}`,
    // Inline style attributes are pervasive (React style props rendered by
    // SSR); styles cannot run script, so this stays.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob: https:",
    "font-src 'self' data:",
    "media-src 'self' data: blob: https:",
    "worker-src 'self' blob:",
    [
      "connect-src 'self' blob: data:",
      ...firstPartyOrigins(read),
      ...loopbackWebsockets(firstPartyOrigins(read)),
      "wss:",
      ...(dev ? ["ws:"] : []),
      ...EDGE_AND_TOOL_CONNECT_HOSTS,
    ].join(" "),
    [
      "frame-src 'self' blob: https:",
      ...(authOrigin ? [authOrigin] : []),
      ...loopbackFrames(firstPartyOrigins(read)),
    ].join(" "),
    "manifest-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    ["form-action 'self'", ...(authOrigin ? [authOrigin] : [])].join(" "),
    ...(frameable ? [] : ["frame-ancestors 'self'"]),
    // Violations go to app/api/csp-report (rate-limited, logs origin + path only).
    "report-uri /api/csp-report",
  ].join("; ");
}

// public/silent-check-sso.html's one inline script, pinned by hash. Keep in
// step with the file (csp.test.ts recomputes it from disk).
export const SILENT_CHECK_SSO_SCRIPT_HASH = "'sha256-NuGEHWbQP7fFayUad46pwOQjhf59O6x6VUaef+Cctms='";

/** silent-check-sso.html: one hashed inline script, nothing else. Framed by the app itself. */
export function buildSilentCheckSsoCsp(): string {
  return [
    "default-src 'none'",
    `script-src ${SILENT_CHECK_SSO_SCRIPT_HASH}`,
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'self'",
  ].join("; ");
}

/** sandbox-frame.html: App Builder previews. Always embedded sandboxed without
 *  allow-same-origin (opaque origin), so generated code gets a permissive
 *  script policy but no reach into the console. Network is limited to esm.sh
 *  (bare-module imports in SandboxPreview) and blob:/data:. */
export function buildSandboxFrameCsp(): string {
  return [
    "default-src 'none'",
    "script-src 'unsafe-inline' 'unsafe-eval' blob: data: https://esm.sh",
    "style-src 'unsafe-inline' blob: data: https:",
    "img-src data: blob: https:",
    "font-src data: https:",
    "media-src data: blob: https:",
    "connect-src blob: data: https://esm.sh",
    "worker-src blob:",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'self'",
  ].join("; ");
}

/** app-frame.html: shared/published user apps (run-app.html). No network, no
 *  forms, inline script only - the policy run-app used to splice in as a meta
 *  tag, now a header the app cannot bypass (security review SEC-020). */
export function buildAppFrameCsp(): string {
  return [
    "default-src 'none'",
    "script-src 'unsafe-inline'",
    "style-src 'unsafe-inline'",
    "img-src data: blob:",
    "font-src data:",
    "media-src data: blob:",
    "connect-src 'none'",
    "form-action 'none'",
    "base-uri 'none'",
    "frame-ancestors 'self'",
  ].join("; ");
}

/** run-app.html: the launcher page itself (its own inline script, the public
 *  apps API, its service worker, and /app-frame.html). */
export function buildRunAppCsp(read: EnvReader = getPublicEnv): string {
  return [
    "default-src 'self'",
    "script-src 'self' 'unsafe-inline'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob:",
    ["connect-src 'self'", ...firstPartyOrigins(read)].join(" "),
    "frame-src 'self'",
    "worker-src 'self'",
    "manifest-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join("; ");
}

/** Any other public *.html (e.g. widget/demo.html): operator-authored static
 *  pages with inline scripts; no nonce can be injected into a static file. */
export function buildStaticHtmlCsp(): string {
  return [
    "default-src 'self'",
    "script-src 'self' 'unsafe-inline'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: https:",
    "frame-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "frame-ancestors 'self'",
  ].join("; ");
}

/** Policy for a static public file that has its own, or null to use the page policy. */
export function staticFilePolicy(pathname: string, read: EnvReader = getPublicEnv): string | null {
  switch (pathname) {
    case SILENT_CHECK_SSO_PATH:
      return buildSilentCheckSsoCsp();
    case SANDBOX_FRAME_PATH:
      return buildSandboxFrameCsp();
    case APP_FRAME_PATH:
      return buildAppFrameCsp();
    case RUN_APP_PATH:
      return buildRunAppCsp(read);
    default:
      return null;
  }
}

/** /a/embed/<shareId> must be frameable by the customer's site. */
export function isEmbedPath(pathname: string): boolean {
  return pathname.startsWith("/a/embed/");
}
