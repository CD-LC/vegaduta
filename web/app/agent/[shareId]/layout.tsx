import type { Metadata } from "next";
import type { ReactNode } from "react";
import { getPublicEnv } from "../../lib/env";

// Metadata shell for the public shared-agent viewer (/agent/<shareId>) -
// a real, registered tenant's own agent, shared to the external world (as
// opposed to /a/[shareId], which is the anonymous demo-sandbox equivalent).
// page.tsx is "use client" and cannot export metadata itself - same
// pass-through-layout pattern as /a/[shareId]/layout.tsx.
//
// robots noindex/nofollow: this is still user-controlled content (the
// tenant chose the agent's name/behavior), minted and revoked freely by its
// owner - indexing it would put arbitrary tenant content under this
// domain's name and litter the index with dead links once revoked.
//
// generateMetadata does a server-side lookup of the same public GET
// /api/share/agents/{shareId} the page itself calls, so links shared on
// WhatsApp/Slack/etc. unfurl with the actual agent's name rather than a
// generic card. Failure posture: never worth breaking the page over - any
// fetch failure, non-200, timeout (2s) or malformed body falls back to the
// generic metadata below.

const GENERIC_METADATA: Metadata = {
  title: "Shared agent — VegaDūta",
  robots: {
    index: false,
    follow: false,
  },
};

// API_BASE_URL, then CORE_INTERNAL_URL (the compose-internal line to core -
// a localhost fallback inside the web container would dial the web container
// itself), then the browser-facing build-time URL, then the local-dev default.
const SERVER_API_BASE =
  process.env.API_BASE_URL ||
  process.env.CORE_INTERNAL_URL ||
  getPublicEnv("NEXT_PUBLIC_API_BASE_URL") ||
  "http://localhost:8080";

function cleanAgentName(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  let stripped = "";
  for (const ch of raw) {
    const code = ch.codePointAt(0) ?? 0;
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
      `${SERVER_API_BASE}/api/share/agents/${encodeURIComponent(shareId)}`,
      { signal: AbortSignal.timeout(2000), next: { revalidate: 60 } }
    );
    if (!res.ok) return GENERIC_METADATA;
    const body = await res.json();
    const agentName = cleanAgentName(body?.agentName);
    if (!agentName) return GENERIC_METADATA;

    // White-label (audit item C2): the meta endpoint only carries
    // brandDisplayName while the owner's plan includes white-label, so its
    // presence alone decides whether the unfurl card mentions VegaDūta.
    // Same control-character/length hygiene as the agent name - this is
    // tenant-typed text going into a <title>.
    const brandName = cleanAgentName(body?.brandDisplayName);
    const title = brandName
      ? `Chat with ${agentName} — ${brandName}`
      : `Chat with ${agentName} — built with VegaDūta`;
    const description = brandName
      ? `${agentName} is a live AI agent answering on behalf of ${brandName}. Talk to it right now.`
      : `${agentName} is a live AI agent built on VegaDūta. ` +
        "Talk to it right now, then build your own for free.";
    if (brandName) {
      // No VegaDūta og-image on a white-labeled unfurl - a text-only card
      // beats someone else's logo on the tenant's own share link.
      return {
        title,
        description,
        robots: { index: false, follow: false },
        openGraph: { title, description, type: "website" },
        twitter: { card: "summary", title, description },
      };
    }
    return {
      title,
      description,
      robots: { index: false, follow: false },
      openGraph: { title, description, type: "website", images: ["/og-image.png"] },
      twitter: { card: "summary_large_image", title, description, images: ["/og-image.png"] },
    };
  } catch {
    return GENERIC_METADATA;
  }
}

export default function SharedRealAgentLayout({ children }: { children: ReactNode }) {
  return children;
}
