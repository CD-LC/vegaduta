"use client";

import { Suspense, useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { usePageReady } from "../../../components/RouteTransition";
import EmbedChat from "../../../components/widget/EmbedChat";
import {
  PROTOCOL_VERSION,
  sanitizeAccent,
  sanitizeParentOrigin,
} from "../../../components/widget/embedProtocol";

// /a/embed/<shareId> - the page the embeddable widget's iframe points at.
//
// WHY THIS PATH LIVES UNDER /a/ WHEN THE BACKEND IT TALKS TO IS THE
// AGENT-SHARE SURFACE (/api/share/agents/**, i.e. the /agent/[shareId]
// contract, NOT /a/[shareId]'s demo-sandbox one): pure routing pragmatics.
// The widget workstream owns web/app/a/**; web/app/agent/** belongs to
// another route's owner. A URL segment is not an API binding - the imports
// in EmbedChat.tsx are what bind this page, and they point at
// agent/[shareId]/api.ts (real tenant agents), deliberately. The static
// "embed" segment wins over the sibling dynamic [shareId] segment in
// Next.js routing, and it can never shadow a real share link: share ids are
// 16-64 URL-safe-Base64 chars (shape-gated server-side), so the 5-char
// literal "embed" is unreachable as an id.
//
// /a/ is a trailing-slash (pure prefix) entry in PUBLIC_ROUTES
// (web/app/lib/auth.ts), so this page is already public with NO auth.ts
// edit - a widget visitor has no session and never will.
//
// usePageReady(true) unconditionally, same reasoning as both share viewers:
// this page's own loading state is designed content. Extra weight here: the
// route-transition cover animating inside a just-opened 400px panel on
// somebody else's site would read as OUR widget flashing.
//
// QUERY PARAMETERS (written by web/public/widget/vegaduta-widget.js's
// buildFrameUrl - the hand-maintained pair contract in embedProtocol.ts):
//   v            protocol version; mismatch warns, never blocks (fail-open:
//                a stale cached loader still delivers a working chat, it
//                just might miss unread-dot choreography).
//   parentOrigin postMessage target; anything that is not a clean http(s)
//                origin degrades to "post nothing" (sanitizeParentOrigin).
//   accent       custom accent; re-validated HERE, not just in the loader,
//                because this URL is world-visible and the value lands in an
//                inline style custom property (sanitizeAccent).
//   greeting     host-authored opener bubble; rendered as React text (auto-
//                escaped), capped so a crafted URL can't paint paragraphs.
//   title        panel title override; same treatment.
//                greeting/title are honoured ONLY when this page is actually
//                framed by the site named in parentOrigin (security review
//                SEC-039): opened top-level from a crafted link they are
//                ignored, so nobody can put words in a real agent's mouth on
//                our own origin. A framing site controls its page anyway.
//   theme        light | dark | anything-else-means-auto.

const MAX_GREETING_CHARS = 300;
const MAX_TITLE_CHARS = 60;

/** One-line, length-capped text from an untrusted query param. Rendering is
 *  React-escaped already; this just keeps a crafted URL from turning the
 *  header or opener bubble into a wall of text. */
function cleanText(raw: string | null, maxLen: number): string | null {
  if (!raw) return null;
  const oneLine = raw.replace(/\s+/g, " ").trim();
  if (!oneLine) return null;
  return oneLine.length > maxLen ? `${oneLine.slice(0, maxLen - 1)}…` : oneLine;
}

/** True when this document is inside an iframe whose embedding page is on
 *  `parentOrigin` (checked via document.referrer, which for a framed page is
 *  the embedder's URL). */
function isFramedBy(parentOrigin: string | null): boolean {
  if (!parentOrigin || typeof window === "undefined") return false;
  let framed = false;
  try {
    framed = window.self !== window.top;
  } catch {
    framed = true; // cross-origin top access throws: we are framed
  }
  if (!framed) return false;
  try {
    return new URL(document.referrer).origin === parentOrigin;
  } catch {
    return false;
  }
}

function EmbedPageInner() {
  usePageReady(true);
  const params = useParams();
  const rawId = params?.shareId;
  const shareId = typeof rawId === "string" ? rawId : Array.isArray(rawId) ? rawId[0] || "" : "";

  const search = useSearchParams();

  const versionParam = search?.get("v") || "";
  useEffect(() => {
    // In an effect, not render: a skew warning should fire once per mount,
    // not once per keystroke re-render. Loud but non-blocking - see the
    // fail-open note in embedProtocol.ts.
    if (versionParam && versionParam !== String(PROTOCOL_VERSION)) {
       
      console.warn(
        `[VegaDuta widget] Loader protocol v${versionParam} != page protocol v${PROTOCOL_VERSION}. ` +
          "The embedding site is probably serving a cached copy of vegaduta-widget.js; " +
          "chat still works, launcher choreography may not."
      );
    }
  }, [versionParam]);

  const parentOrigin = sanitizeParentOrigin(search?.get("parentOrigin"));
  // Decided after mount: window/document.referrer do not exist during SSR.
  const [hostTextAllowed, setHostTextAllowed] = useState(false);
  useEffect(() => {
    setHostTextAllowed(isFramedBy(parentOrigin));
  }, [parentOrigin]);

  const themeParam = search?.get("theme");
  const theme: "light" | "dark" | "auto" =
    themeParam === "light" || themeParam === "dark" ? themeParam : "auto";

  return (
    <EmbedChat
      shareId={shareId}
      accent={sanitizeAccent(search?.get("accent"))}
      greeting={hostTextAllowed ? cleanText(search?.get("greeting"), MAX_GREETING_CHARS) : null}
      title={hostTextAllowed ? cleanText(search?.get("title"), MAX_TITLE_CHARS) : null}
      theme={theme}
      parentOrigin={parentOrigin}
    />
  );
}

// useSearchParams requires a Suspense boundary in a client page (same
// pattern, and same comment, as web/app/agents/page.tsx) - without it the
// static prerender pass fails the whole build.
export default function EmbedPage() {
  return (
    <Suspense fallback={null}>
      <EmbedPageInner />
    </Suspense>
  );
}
