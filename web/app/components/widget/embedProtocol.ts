// postMessage contract between the widget LOADER (the unbundled script a
// customer pastes into their own site, web/public/widget/vegaduta-widget.js)
// and the framed chat page it opens (/a/embed/<shareId>, rendered by
// EmbedChat.tsx).
//
// ############################################################################
// # HAND-MAINTAINED PAIR - the loader CANNOT import this file.               #
// #                                                                          #
// # web/public/widget/vegaduta-widget.js is served byte-for-byte outside the #
// # Next build graph (that is the whole point of it: a stable, unbundled URL #
// # a customer hard-codes into their HTML), so the constants below are       #
// # DUPLICATED there by hand. If you change any value or message type here,  #
// # change it there in the same commit, and bump PROTOCOL_VERSION so a       #
// # cached old loader talking to a freshly deployed page detects the skew    #
// # instead of failing silently. Both files carry this warning, in both      #
// # directions.                                                              #
// ############################################################################
//
// Security model of the channel, for anyone auditing it later: nothing secret
// ever crosses it. The share id is already sitting in the customer's public
// HTML source; the messages below are pure UI choreography (open/close/unread).
// The origin/source checks on both ends exist to keep unrelated frames from
// DRIVING our UI (an ad iframe force-opening the chat panel), not to protect
// data.

/**
 * Bumped only when the message shapes below change. Passed by the loader as
 * `?v=` on the frame URL; EmbedChat compares it against this constant and
 * logs a console warning on mismatch rather than refusing to render - a
 * version skew means "the choreography may glitch", not "the chat is broken",
 * and fail-open is this platform's posture for degraded-but-working surfaces.
 */
export const PROTOCOL_VERSION = 1;

/**
 * `source` discriminator on every message the FRAME posts to the host page.
 * A page hosting several unrelated widgets must never mistake somebody
 * else's postMessage traffic for ours.
 */
export const MSG_FROM_FRAME = "vegaduta-widget";

/** `source` discriminator on every message the HOST loader posts to the frame. */
export const MSG_FROM_HOST = "vegaduta-widget-host";

/**
 * Frame -> host message types:
 *  - "ready":  the chat page has mounted and is listening; the loader replays
 *              the current open/closed state in response (the frame may have
 *              finished loading after the visitor already opened the panel).
 *  - "close":  the visitor asked to close from INSIDE the frame (the panel's
 *              own X button, or Escape pressed while focus is in the frame -
 *              a keydown inside a cross-origin iframe is invisible to the
 *              host document, so the frame must forward it).
 *  - "unread": an agent reply arrived while the panel was closed; the loader
 *              shows a dot on the launcher. A dot, not a count - the widget
 *              only truthfully knows "at least one", so that is all it says.
 */
export type FrameMessageType = "ready" | "close" | "unread";

/**
 * Host -> frame message types: the loader mirrors its panel state into the
 * frame ("opened"/"closed") so the frame can decide whether a fresh agent
 * reply is "seen" or should be announced as unread.
 */
export type HostMessageType = "opened" | "closed";

export type FrameMessage = { source: typeof MSG_FROM_FRAME; type: FrameMessageType };
export type HostMessage = { source: typeof MSG_FROM_HOST; type: HostMessageType };

/**
 * Validates the `parentOrigin` query parameter the loader passed us, returning
 * a usable postMessage target origin or null.
 *
 * Why validate at all: the value rides in on a URL query string, so anything
 * could be there. It is only ever used as the targetOrigin argument of
 * postMessage - never rendered, never fetched - so the risk of a bad value is
 * misdelivery, not injection. Restricting to a well-formed http(s) origin
 * means a garbage value degrades to "frame posts nothing" (the loader's
 * documented fallback for file:// host pages, whose origin serializes to the
 * literal string "null") rather than throwing on every post.
 */
export function sanitizeParentOrigin(raw: string | null | undefined): string | null {
  if (!raw || raw === "null") return null;
  try {
    const url = new URL(raw);
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    // Round-trip through URL.origin so the target we post to is exactly a
    // scheme://host[:port] triple - postMessage rejects targets with paths.
    return url.origin;
  } catch {
    return null;
  }
}

/**
 * Posts a frame -> host message. No-ops (rather than posting to "*") when the
 * parent origin is unknown: broadcasting even harmless UI events to every
 * listener on an unknown page is sloppier than staying silent, and the widget
 * works fine without the choreography (the launcher still toggles the panel;
 * only the unread dot and in-frame close are lost).
 */
export function postToHost(parentOrigin: string | null, type: FrameMessageType): void {
  if (!parentOrigin) return;
  if (typeof window === "undefined" || window.parent === window) return;
  const message: FrameMessage = { source: MSG_FROM_FRAME, type };
  try {
    window.parent.postMessage(message, parentOrigin);
  } catch {
    // A postMessage to a since-navigated parent can throw in some browsers;
    // UI choreography is never worth surfacing an error over.
  }
}

/**
 * True when `data` is a well-formed host -> frame message. The ORIGIN check
 * is the caller's job (EmbedChat compares event.origin against the
 * parentOrigin it was configured with) - this only vets the payload shape,
 * so a random window.postMessage from some other script on the page cannot
 * flip our open-state tracking with a lookalike object lacking the
 * discriminator.
 */
export function isHostMessage(data: unknown): data is HostMessage {
  if (!data || typeof data !== "object") return false;
  const candidate = data as { source?: unknown; type?: unknown };
  return (
    candidate.source === MSG_FROM_HOST &&
    (candidate.type === "opened" || candidate.type === "closed")
  );
}

/**
 * Accent colour allowlist - the same regex, hand-duplicated, as the loader's
 * ACCENT_RE (see the pairing warning at the top of this file). The loader
 * already validated the customer's data-accent before forwarding it, but the
 * frame URL is world-visible and anyone can craft `?accent=`, and the value
 * is written into an inline style custom property here - so it is re-validated
 * at the trust boundary it actually crosses. Hex / rgb() / hsl() / bare
 * keyword only; anything else falls back to the brand default.
 */
const ACCENT_RE =
  /^(#[0-9a-fA-F]{3}|#[0-9a-fA-F]{4}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}|rgba?\(\s*[\d.,%\s/]+\)|hsla?\(\s*[\d.,%\s/deg]+\)|[a-zA-Z]{3,24})$/;

export function sanitizeAccent(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  return ACCENT_RE.test(trimmed) ? trimmed : null;
}
