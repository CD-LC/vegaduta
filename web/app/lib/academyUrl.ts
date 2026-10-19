import { getPublicEnv } from "./env";
// Where the "Academy · learn free" links point. The Academy learning portal
// is a separate deployable (vegaduta-webapp-prototype) that runs on :4100
// locally and on its own public host in a hosted deployment.
//
// DETERMINISTIC on both sides of hydration (React #418, 2026-09-25): this
// helper previously sniffed window.location.hostname when the env var was
// unset, so a server render produced the hosted academy URL while the
// first client render on localhost (or a staging host) produced a different
// href for the same <a> - a guaranteed hydration mismatch on every SSR'd
// page that renders an Academy link (landing, /learn, /docs, demo-landings).
//
// NEXT_PUBLIC_* is baked at build time, which is also the correct mechanism
// here: every real build already sets it (docker-compose.domain.yml/.gcp.yml pin the per-env
// academy host). Hosted deployments set NEXT_PUBLIC_ACADEMY_URL
// to their public academy origin.
// No default (AGENTS.md rule 4): an OSS install without an Academy must not
// render a dead http://localhost:4100 link on a public page. Unset = no Academy.
// Hosted/classic builds set NEXT_PUBLIC_ACADEMY_URL.
export function academyUrl(): string {
  return (getPublicEnv("NEXT_PUBLIC_ACADEMY_URL") || "").trim().replace(/\/+$/, "");
}

/** True when an Academy origin is configured; callers hide the link otherwise. */
export function hasAcademy(): boolean {
  return academyUrl() !== "";
}

/** For call sites that always render an Academy link: the configured origin,
 *  else the on-domain /learn page (never a dead localhost URL). */
export function academyHref(): string {
  return academyUrl() || "/learn";
}
