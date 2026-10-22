// Runtime public config (OSS "one image, any domain", 2026-09-29).
//
// THE PROBLEM. Next.js inlines every `process.env.NEXT_PUBLIC_*` reference
// into the client bundle - and the server bundle - at BUILD time. A base
// `next build` therefore bakes the localhost defaults, which breaks sign-in on
// any real domain, and a prebuilt image can never be pointed at someone
// else's domain.
//
// THE FIX. The root layout (already dynamic - it reads cookies()/headers() on
// every request) renders `<script id="__public-env">window.__ENV={...}</script>`
// as the first element of <head>, built from THIS process's environment at
// request time via publicEnvScript(). Every read goes through getPublicEnv():
//
//   browser: window.__ENV[key]   -> build-time inlined value
//   server:  process.env[key]    -> build-time inlined value
//            (dynamic lookup, so the bundler cannot inline it)
//
// Precedence is RUNTIME > BUILD-TIME. An empty string counts as unset, so a
// compose file that passes `NEXT_PUBLIC_X=${NEXT_PUBLIC_X:-}` never clobbers a
// value that was baked in with --build-arg (the private repo's current path).
//
// Only the keys in PUBLIC_ENV_KEYS are ever serialised into the page. Never
// add a server secret here: everything in this list is readable by anyone
// who opens view-source.
//
// Timing: client modules that read these at module load (e.g. `API_BASE` in
// lib/api/core.ts) are only evaluated when the RSC flight data in <body>
// references them, which is always after the parser has executed this <head>
// script. See web/docs/RUNTIME-CONFIG.md.

export const PUBLIC_ENV_KEYS = [
  "NEXT_PUBLIC_API_BASE_URL",
  "NEXT_PUBLIC_KEYCLOAK_URL",
  "NEXT_PUBLIC_KEYCLOAK_REALM",
  "NEXT_PUBLIC_KEYCLOAK_CLIENT_ID",
  // "keycloak" (default) or "local": which sign-in the console uses. "local" talks to core's own
  // single-user auth (app.auth.mode=local) through the /api/local-auth proxy; see lib/authMode.ts.
  "NEXT_PUBLIC_AUTH_MODE",
  // "personal" | "lite" | "full": the edition core runs as (core: app.edition.tier). The synchronous
  // seed for everything the console hides on a personal install; unset falls back to the pre-edition
  // derivation (local auth = personal, NEXT_PUBLIC_DEPLOY_ENV = lite | full). See lib/edition.ts.
  "NEXT_PUBLIC_EDITION",
  "NEXT_PUBLIC_ACADEMY_URL",
  "NEXT_PUBLIC_CODE_INTEL_URL",
  "NEXT_PUBLIC_APP_BUILDER_URL",
  "NEXT_PUBLIC_EDGE_AGENTS",
  "NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL",
  "NEXT_PUBLIC_DEPLOY_ENV",
  "NEXT_PUBLIC_FREE_MODE",
  "NEXT_PUBLIC_COFFEE_URL",
  // "1" shows plans/prices/upgrade/billing UI (hosted SaaS). Unset = off,
  // the self-hosted default. See lib/billing-flag.ts.
  "NEXT_PUBLIC_BILLING_ENABLED",
  // "oss" (default) = technical open-source public pages; "classic" = the
  // original marketing pages. See lib/siteMode.ts.
  "NEXT_PUBLIC_SITE_MODE",
  // Absolute URL of the published documentation site (docs/, mkdocs.yml). Unset =
  // the in-app help at /docs. See lib/docsUrl.ts.
  "NEXT_PUBLIC_DOCS_URL",
  // Absolute URL of a hosted/commercial offering of this software. Unset = the OSS pages
  // show no such link. See lib/siteLinks.ts.
  "NEXT_PUBLIC_COMMERCIAL_URL",
  // Operator identity (site origin, contact, legal footer) - OSS deployments
  // set their own.
  "NEXT_PUBLIC_SITE_URL",
  "NEXT_PUBLIC_CONTACT_EMAIL",
  "NEXT_PUBLIC_LEGAL_ENTITY",
  "NEXT_PUBLIC_LEGAL_CIN",
  "NEXT_PUBLIC_LEGAL_DIN",
  // "1" makes the Google/X/Atlassian OAuth authorize-url call send credentials
  // so core can set its SEC-018 browser-binding cookie. Turn on together with
  // core CORS allow-credentials + app.oauth.state.require-browser-cookie.
  "NEXT_PUBLIC_OAUTH_STATE_COOKIE",
  // Optional direct-download Android APK (lib/mobileApp.ts). Unset hides the panels.
  "NEXT_PUBLIC_MOBILE_APK_URL",
  "NEXT_PUBLIC_MOBILE_APK_SHA256",
  "NEXT_PUBLIC_MOBILE_APK_VERSION",
  "NEXT_PUBLIC_MOBILE_APK_SIZE_MB",
  // "1" renders MCP Apps (sandboxed ui:// resources) in chat. Unset = off.
  "NEXT_PUBLIC_MCP_APPS",
  // "1" turns on the chat Artifacts pane (lib/artifacts/artifactsFlag.ts). Unset = off.
  "NEXT_PUBLIC_ARTIFACTS",
  // Comma-separated extra https hosts model-rendered markdown images may load from.
  "NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS",
  // Canonical tenant-console origin - the merged platform-admin section
  // (app/platform-admin/) uses it to build "open in the tenant console"
  // links. Used to be web-admin's NEXT_PUBLIC_APP_URL.
  "NEXT_PUBLIC_APP_URL",
  // Git SHA this image was built from - shown on /platform-admin/ops next to
  // the backend service versions (was web-admin's NEXT_PUBLIC_BUILD_SHA).
  "NEXT_PUBLIC_BUILD_SHA",
] as const;

export type PublicEnvKey = (typeof PUBLIC_ENV_KEYS)[number];

/**
 * Public settings whose NAMES read as sensitive to a source-to-sink analysis (billing, an OAuth cookie
 * switch, a trust list), although each value is a plain deploy-time switch or host list that is shipped
 * to the browser anyway (window.__ENV). They are read with getPublicPolicy(), which has its own
 * build-time reads, so their values never share a return value with the URL settings read by
 * getPublicEnv(). Without that split an analyzer sees the model-manifest URL as "derived from a billing
 * flag" and reports it as clear-text storage when the URL is cached in localStorage.
 */
export const PUBLIC_POLICY_KEYS = [
  "NEXT_PUBLIC_BILLING_ENABLED",
  "NEXT_PUBLIC_OAUTH_STATE_COOKIE",
  "NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS",
] as const;

export type PublicPolicyKey = (typeof PUBLIC_POLICY_KEYS)[number];
/** Every public key read with getPublicEnv(): all of them except the policy settings above. */
export type PublicValueKey = Exclude<PublicEnvKey, PublicPolicyKey>;

function isPolicyKey(key: PublicEnvKey): key is PublicPolicyKey {
  return (PUBLIC_POLICY_KEYS as readonly string[]).includes(key);
}

declare global {
  interface Window {
    __ENV?: Partial<Record<string, string>>;
  }
}

// Each reference MUST stay a literal `process.env.NEXT_PUBLIC_X` - that is
// the only form Next.js inlines. A switch (not a module-level object) keeps
// the value live under vitest, where tests assign process.env at run time.
function buildTimeValue(key: PublicValueKey): string | undefined {
  switch (key) {
    case "NEXT_PUBLIC_API_BASE_URL":
      return process.env.NEXT_PUBLIC_API_BASE_URL;
    case "NEXT_PUBLIC_KEYCLOAK_URL":
      return process.env.NEXT_PUBLIC_KEYCLOAK_URL;
    case "NEXT_PUBLIC_KEYCLOAK_REALM":
      return process.env.NEXT_PUBLIC_KEYCLOAK_REALM;
    case "NEXT_PUBLIC_KEYCLOAK_CLIENT_ID":
      return process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID;
    case "NEXT_PUBLIC_AUTH_MODE":
      return process.env.NEXT_PUBLIC_AUTH_MODE;
    case "NEXT_PUBLIC_EDITION":
      return process.env.NEXT_PUBLIC_EDITION;
    case "NEXT_PUBLIC_ACADEMY_URL":
      return process.env.NEXT_PUBLIC_ACADEMY_URL;
    case "NEXT_PUBLIC_CODE_INTEL_URL":
      return process.env.NEXT_PUBLIC_CODE_INTEL_URL;
    case "NEXT_PUBLIC_APP_BUILDER_URL":
      return process.env.NEXT_PUBLIC_APP_BUILDER_URL;
    case "NEXT_PUBLIC_EDGE_AGENTS":
      return process.env.NEXT_PUBLIC_EDGE_AGENTS;
    case "NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL":
      return process.env.NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL;
    case "NEXT_PUBLIC_DEPLOY_ENV":
      return process.env.NEXT_PUBLIC_DEPLOY_ENV;
    case "NEXT_PUBLIC_FREE_MODE":
      return process.env.NEXT_PUBLIC_FREE_MODE;
    case "NEXT_PUBLIC_COFFEE_URL":
      return process.env.NEXT_PUBLIC_COFFEE_URL;
    case "NEXT_PUBLIC_SITE_MODE":
      return process.env.NEXT_PUBLIC_SITE_MODE;
    case "NEXT_PUBLIC_SITE_URL":
      return process.env.NEXT_PUBLIC_SITE_URL;
    case "NEXT_PUBLIC_CONTACT_EMAIL":
      return process.env.NEXT_PUBLIC_CONTACT_EMAIL;
    case "NEXT_PUBLIC_LEGAL_ENTITY":
      return process.env.NEXT_PUBLIC_LEGAL_ENTITY;
    case "NEXT_PUBLIC_LEGAL_CIN":
      return process.env.NEXT_PUBLIC_LEGAL_CIN;
    case "NEXT_PUBLIC_LEGAL_DIN":
      return process.env.NEXT_PUBLIC_LEGAL_DIN;
    case "NEXT_PUBLIC_MOBILE_APK_URL":
      return process.env.NEXT_PUBLIC_MOBILE_APK_URL;
    case "NEXT_PUBLIC_MOBILE_APK_SHA256":
      return process.env.NEXT_PUBLIC_MOBILE_APK_SHA256;
    case "NEXT_PUBLIC_MOBILE_APK_VERSION":
      return process.env.NEXT_PUBLIC_MOBILE_APK_VERSION;
    case "NEXT_PUBLIC_MOBILE_APK_SIZE_MB":
      return process.env.NEXT_PUBLIC_MOBILE_APK_SIZE_MB;
    case "NEXT_PUBLIC_MCP_APPS":
      return process.env.NEXT_PUBLIC_MCP_APPS;
    case "NEXT_PUBLIC_ARTIFACTS":
      return process.env.NEXT_PUBLIC_ARTIFACTS;
    case "NEXT_PUBLIC_APP_URL":
      return process.env.NEXT_PUBLIC_APP_URL;
    case "NEXT_PUBLIC_BUILD_SHA":
      return process.env.NEXT_PUBLIC_BUILD_SHA;
    default:
      return undefined;
  }
}

// Same rule as buildTimeValue: literal `process.env.NEXT_PUBLIC_X` references only. These are the
// policy settings of PUBLIC_POLICY_KEYS, read here and nowhere else so they stay out of getPublicEnv().
function buildTimePolicy(key: PublicPolicyKey): string | undefined {
  switch (key) {
    case "NEXT_PUBLIC_BILLING_ENABLED":
      return process.env.NEXT_PUBLIC_BILLING_ENABLED;
    case "NEXT_PUBLIC_OAUTH_STATE_COOKIE":
      return process.env.NEXT_PUBLIC_OAUTH_STATE_COOKIE;
    case "NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS":
      return process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS;
    default:
      return undefined;
  }
}

function present(v: string | undefined | null): v is string {
  return typeof v === "string" && v !== "";
}

/** Runtime value of a key (runtime > build-time > undefined); `baked` is the build-time value. */
function resolvePublic(key: PublicEnvKey, baked: string | undefined): string | undefined {
  if (typeof window !== "undefined") {
    const runtime = window.__ENV?.[key];
    if (present(runtime)) return runtime;
  } else {
    // Dynamic key: never inlined, so this is the container's real env.
    const runtime = process.env[key as string];
    if (present(runtime)) return runtime;
  }
  return present(baked) ? baked : undefined;
}

/** Runtime value of a public config key (runtime > build-time > undefined). */
export function getPublicEnv(key: PublicValueKey): string | undefined {
  return resolvePublic(key, buildTimeValue(key));
}

/**
 * Runtime value of a public policy setting (billing switch, OAuth state cookie switch, trusted image
 * hosts); same precedence as {@link getPublicEnv}. See PUBLIC_POLICY_KEYS for why these are separate.
 */
export function getPublicPolicy(key: PublicPolicyKey): string | undefined {
  return resolvePublic(key, buildTimePolicy(key));
}

/** Server-only: the allowlisted public keys that currently have a value. */
/**
 * The browser-facing core API origin, with the single dev fallback that used
 * to be copied into ~20 files as `|| "http://localhost:8080"`. In a
 * production build the value must be configured (AGENTS.md rule 4): a missing
 * NEXT_PUBLIC_API_BASE_URL is reported once and returns "" so calls fail
 * visibly against the page origin instead of quietly going to localhost.
 */
export function publicApiBase(): string {
  return requiredPublicOrigin("NEXT_PUBLIC_API_BASE_URL", "http://localhost:8080");
}

/** Same contract as {@link publicApiBase} for the browser-facing Keycloak URL. */
export function publicKeycloakUrl(): string {
  return requiredPublicOrigin("NEXT_PUBLIC_KEYCLOAK_URL", "http://localhost:8180");
}

const reportedMissing = new Set<string>();

function requiredPublicOrigin(key: PublicValueKey, devDefault: string): string {
  const configured = getPublicEnv(key);
  if (configured) return configured;
  if (process.env.NODE_ENV !== "production") return devDefault;
  if (!reportedMissing.has(key)) {
    reportedMissing.add(key);
    console.error(`[env] ${key} is not set; browser calls will target the page origin`);
  }
  return "";
}

export function publicEnvSnapshot(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const key of PUBLIC_ENV_KEYS) {
    const v = isPolicyKey(key) ? getPublicPolicy(key) : getPublicEnv(key);
    if (v !== undefined) out[key] = v;
  }
  return out;
}

/** Body for the inline <script> the root layout renders first in <head>.
 *  JSON is escaped so no value can close the script tag or break out of JS. */
export function publicEnvScript(): string {
  return `window.__ENV=Object.freeze(${scriptSafeJson(publicEnvSnapshot())});`;
}

/** JSON.stringify for embedding inside a <script> element (executable or
 *  application/ld+json): no value can close the tag, open a comment or break
 *  out of a JS string. Shared by publicEnvScript and components/JsonLd. */
export function scriptSafeJson(value: unknown): string {
  return JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");
}
