import type { Metadata } from "next";
import type { ReactNode } from "react";
import { getPublicEnv } from "../../lib/env";

// Metadata shell for the public shared-agent viewer (/a/<shareId>).
// page.tsx is "use client" and cannot export metadata itself — same
// pass-through-layout pattern as /try/layout.tsx.
//
// robots noindex/nofollow stays the point of this file: these are
// USER-GENERATED pages (an anonymous sandbox visitor named the agent and
// controls what it says), minted in unbounded numbers and dead within days.
// Letting a search engine index them would put arbitrary anonymous content
// under this domain's name and litter the index with 410s.
//
// What CHANGED (SEO/social pass, 2026-07-25): noindex is not the same as
// "no unfurl". These links are passed hand to hand on WhatsApp/Telegram,
// where the preview CARD is the entire first impression — a generic
// "Shared agent" card wastes the one viral surface this feature has. So the
// static export became generateMetadata: a server-side lookup of the same
// public GET /api/demo/share/{shareId} the page itself calls (see api.ts's
// ShareMetaResponse contract: agentName/personaLabel/expiresAt), producing a
// per-agent title/description + OpenGraph/Twitter card. Messengers and
// crawlers read raw HTML only, so this must happen server-side.
//
// Failure posture: metadata is never worth breaking the page over. Any
// fetch failure, non-200, timeout (2s) or malformed body falls back to the
// exact generic metadata this file always served. Successes are cached for
// 60s (next.revalidate); failures return before any body-shape trust and are
// never re-thrown.

const GENERIC_METADATA: Metadata = {
  title: "Shared agent — VegaDūta",
  robots: {
    index: false,
    follow: false,
  },
};

// Server-side base for reaching core. Runtime API_BASE_URL, then the compose
// CORE_INTERNAL_URL (the web container's direct line to core - a localhost
// fallback would dial the web container itself, not core), then the build-time
// NEXT_PUBLIC_API_BASE_URL the browser bundle uses; final fallback is the
// local-dev core (same default as lib/api/core.ts). Hosted deployments must
// set one of the three.
const SERVER_API_BASE =
  process.env.API_BASE_URL ||
  process.env.CORE_INTERNAL_URL ||
  getPublicEnv("NEXT_PUBLIC_API_BASE_URL") ||
  "http://localhost:8080";

// agentName is anonymous-visitor-supplied text headed into <title>/OG tags.
// Next.js escapes it (no injection risk), but control chars / newlines /
// absurd lengths would still make a garbage card — normalize to one trimmed,
// capped line and reject empties.
function cleanAgentName(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  let stripped = "";
  for (const ch of raw) {
    const code = ch.codePointAt(0) ?? 0;
    // C0 control range + DEL -> space (kept out of a regex literal on
    // purpose: raw control bytes in source files break tooling).
    stripped += code < 32 || code === 127 ? " " : ch;
  }
  const oneLine = stripped.replace(/\s+/g, " ").trim();
  if (!oneLine) return null;
  return oneLine.length > 80 ? `${oneLine.slice(0, 77)}…` : oneLine;
}

export async function generateMetadata(
  { params }: { params: Promise<{ shareId: string }> }
): Promise<Metadata> {
  try {
    const { shareId } = await params;
    if (!shareId) return GENERIC_METADATA;
    const res = await fetch(
      `${SERVER_API_BASE}/api/demo/share/${encodeURIComponent(shareId)}`,
      {
        signal: AbortSignal.timeout(2000),
        next: { revalidate: 60 },
      }
    );
    if (!res.ok) return GENERIC_METADATA;
    const body = await res.json();
    const agentName = cleanAgentName(body?.agentName);
    if (!agentName) return GENERIC_METADATA;

    const title = `Chat with ${agentName} — built with VegaDūta`;
    const description =
      `${agentName} is a live demo AI agent built on VegaDūta. ` +
      "Talk to it right now — no signup, no card — then build your own for free.";
    return {
      title,
      description,
      robots: {
        index: false,
        follow: false,
      },
      openGraph: {
        title,
        description,
        type: "website",
        images: ["/og-image.png"],
      },
      twitter: {
        card: "summary_large_image",
        title,
        description,
        images: ["/og-image.png"],
      },
    };
  } catch {
    // Timeout, network failure, JSON parse error — all land here. The page
    // itself still renders and does its own client-side load/error handling.
    return GENERIC_METADATA;
  }
}

export default function SharedAgentLayout({ children }: { children: ReactNode }) {
  return children;
}
