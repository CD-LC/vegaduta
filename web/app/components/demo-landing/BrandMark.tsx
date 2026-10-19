"use client";

import { useState } from "react";

// BrandMark — the one place that turns a brand key into artwork.
//
// ---------------------------------------------------------------------------
// WHY THIS EXISTS, AND HOW IT RELATES TO ProviderWall.tsx's "no logos" note
// ---------------------------------------------------------------------------
// ProviderWall.tsx:28-42 records a deliberate decision: no vendor logo files
// in this repo, abstract geometric glyphs drawn inline instead. That note was
// written when web/public/ carried no brand assets at all and the landing CSP
// was read as blocking images outright.
//
// Direct instruction (2026-09-11): "providers, channels and other features
// should be represented with images, logos". So the constraint is re-stated
// here rather than reversed, because only half of it was ever a constraint:
//
//   * REMOTE images are the thing that cannot ship. next.config.js:25 sets
//     `img-src 'self' data: blob: https:` (report-only today, promotable to
//     enforcing), and hotlinking a vendor CDN is exactly what must not be
//     done. SAME-ORIGIN files under web/public/ are 'self' and always load.
//   * TRADEMARK is the other half, and it is unchanged: nothing in
//     web/public/logos/ is a reproduction of any company's registered mark.
//     Each file is a GENERIC glyph authored in this repo — a chat bubble, a
//     paper plane, an envelope, a microphone, a hash, a letter-X monogram, an
//     aperture, or one of ProviderWall's own abstract marks (prism, orbit,
//     ring, node, grid, stack, chevrons, shard, helix, pulse, bars, arc,
//     wave) re-drawn on a 32x32 canvas. Identification is carried by the
//     WORDMARK — the company's plain name, which is how you refer to a
//     company in running text — exactly as ProviderWall already does.
//
// So the shipped rule is now: OUR glyph, THEIR colour, THEIR name in text.
// No partnership or endorsement is implied or stated anywhere on the page.
//
// ---------------------------------------------------------------------------
// COLOUR LEDGER — `source` on every entry below, no guessing
// ---------------------------------------------------------------------------
//   "brand"   the company's own widely-published brand colour, used because
//             the colour is how that brand is recognised at a glance.
//   "mono"    the brand's own colour is black/white (X, OpenAI, Grok, Ollama,
//             ElevenLabs, OpenRouter) or we do not have a published hex we are
//             confident of (Sarvam AI, E2B). Rendered in a neutral slate that
//             stays legible in both themes rather than inventing a hex and
//             presenting it as theirs.
//   "house"   not a company at all — our own generic capability (Email, Voice,
//             the self-hosted Whisper sidecar, local sentence-transformers
//             embeddings, WebLLM on-device). Our colour, our glyph.
//
// TILE TINT vs GLYPH COLOUR (2026-09-24, vd-tier1 palette rule)
//   The palette allows no red/pink/orange outside destructive actions, and a
//   32px tile washed in Instagram magenta or Bedrock orange is exactly that.
//   So `hex` now colours only the GLYPH (and the monogram fallback); the tile
//   behind a "brand" entry is always the neutral MONO tint, so every vendor
//   tile reads as the same quiet slate chip and the vendor hue survives only
//   inside the glyph artwork, where recognition needs it. "house" and "mono"
//   entries keep tinting the tile from their own hex because those hues are
//   ours to choose and are already cool/neutral. See tileHex() below.
//
// ---------------------------------------------------------------------------
// WHY <img> AND NOT next/image
// ---------------------------------------------------------------------------
// next/image refuses SVG sources unless next.config.js sets
// `images.dangerouslyAllowSVG: true`, and it does not (next.config.js:96-100
// configures only remotePatterns). Rather than flip a security flag for
// decoration, these render as a plain <img> with explicit width/height so the
// intrinsic box is known before paint and nothing shifts — the same
// `eslint-disable-next-line @next/next/no-img-element` idiom already used by
// PromoBanner.tsx:59 and EmbedChat.tsx:295.
//
// Colours are baked into each file because an <img>-loaded SVG is an isolated
// document: it inherits no CSS custom properties and `currentColor` there
// resolves to black, which is why no mark uses it. The surrounding tile tint
// is computed from the same hex with an alpha suffix, so it sits correctly on
// both the light and the dark surface without a single `dark:` variant (see
// the NOTE in HeroUniversalAgent.tsx for why `dark:` is banned in this tree).

export type BrandSource = "brand" | "mono" | "house";

export interface BrandEntry {
  /** Wordmark — the company's plain name. Proper nouns, not translated copy. */
  label: string;
  /** Tint/accent hex. See the colour ledger above for what `source` means. */
  hex: string;
  source: BrandSource;
}

/** Neutral used for every `mono` entry — legible on both landing surfaces. */
const MONO = "#6E7681";

export const BRAND_MARKS = {
  // -- the 9 channels (ChannelType.java:32-42) ------------------------------
  whatsapp: { label: "WhatsApp", hex: "#25D366", source: "brand" },
  messenger: { label: "Messenger", hex: "#0084FF", source: "brand" },
  instagram: { label: "Instagram", hex: "#DD2A7B", source: "brand" },
  slack: { label: "Slack", hex: "#36C5F0", source: "brand" },
  discord: { label: "Discord", hex: "#5865F2", source: "brand" },
  x: { label: "X", hex: MONO, source: "mono" },
  telegram: { label: "Telegram", hex: "#26A5E4", source: "brand" },
  // Email was the house orange (#E08A2E); orange is off the palette, so it now
  // takes the sky accent-500 stop from tokens.css (a cool "comms" blue).
  // The glyph file public/logos/email.svg still carries the old stroke hex —
  // that asset is outside this component and is flagged for its owner.
  email: { label: "Email", hex: "#0284C7", source: "house" },
  voice: { label: "Voice", hex: "#0FA8A0", source: "house" },

  // -- the 14 BYOK language-model providers (ProviderWall.tsx LLM_TILES) -----
  anthropic: { label: "Anthropic", hex: "#D97757", source: "brand" },
  openai: { label: "OpenAI", hex: MONO, source: "mono" },
  "google-gemini": { label: "Google Gemini", hex: "#4285F4", source: "brand" },
  "google-vertex": { label: "Google Vertex AI", hex: "#4285F4", source: "brand" },
  "azure-openai": { label: "Azure OpenAI", hex: "#0078D4", source: "brand" },
  "aws-bedrock": { label: "AWS Bedrock", hex: "#FF9900", source: "brand" },
  openrouter: { label: "OpenRouter", hex: MONO, source: "mono" },
  mistral: { label: "Mistral AI", hex: "#FA520F", source: "brand" },
  deepseek: { label: "DeepSeek", hex: "#4D6BFE", source: "brand" },
  groq: { label: "Groq", hex: "#F55036", source: "brand" },
  grok: { label: "Grok (xAI)", hex: MONO, source: "mono" },
  meta: { label: "Meta", hex: "#0866FF", source: "brand" },
  sarvam: { label: "Sarvam AI", hex: MONO, source: "mono" },
  ollama: { label: "Ollama", hex: MONO, source: "mono" },

  // -- speech (ProviderWall.tsx:233-238) ------------------------------------
  elevenlabs: { label: "ElevenLabs", hex: MONO, source: "mono" },
  whisper: { label: "Whisper", hex: "#0FA8A0", source: "house" },

  // -- tools + embeddings (ProviderWall.tsx:240-243) ------------------------
  e2b: { label: "E2B", hex: MONO, source: "mono" },
  "sentence-transformers": { label: "sentence-transformers", hex: "#7C5CFF", source: "house" },

  // -- on-device runtimes (ProviderWall.tsx:350-351) ------------------------
  webllm: { label: "WebLLM · WebGPU", hex: "#7C5CFF", source: "house" },
  "chrome-ai": { label: "Chrome built-in AI", hex: "#4285F4", source: "brand" },
} as const satisfies Record<string, BrandEntry>;

export type BrandKey = keyof typeof BRAND_MARKS;

/** The 9 values of ChannelType, in enum order. Exported so a caller can never
 *  hand-maintain a second copy of the list and let it drift (en.json:3569's
 *  stale "11 channels" is what that drift looks like). */
export const CHANNEL_KEYS = [
  "whatsapp",
  "messenger",
  "instagram",
  "slack",
  "discord",
  "x",
  "telegram",
  "email",
  "voice",
] as const satisfies readonly BrandKey[];

/** Alpha suffixes appended to the 6-digit hex — an 8-digit hex is valid CSS
 *  everywhere this app runs, and keeps tile/ring colour derived from the one
 *  source of truth instead of a second hand-picked value. */
const TINT_ALPHA = "1f"; // ~12%
const RING_ALPHA = "3d"; // ~24%

/** The hex a tile is tinted from. Vendor ("brand") hues never wash the tile —
 *  see "TILE TINT vs GLYPH COLOUR" in the header. */
function tileHex(entry: BrandEntry): string {
  return entry.source === "brand" ? MONO : entry.hex;
}

export interface BrandMarkProps {
  brand: BrandKey;
  /** Rendered pixel size of the glyph itself. Default 24. */
  size?: number;
  /** Draw the tinted rounded tile behind the glyph. Default true. */
  tile?: boolean;
  /** Show the wordmark beside the glyph. Default false (mark only). */
  withLabel?: boolean;
  /** Force an empty alt. Set this when the caller already renders the brand
   *  name next to the mark (e.g. a translated channel label), so a screen
   *  reader announces it once instead of twice. */
  decorative?: boolean;
  className?: string;
}

/**
 * Renders one brand's mark. If the SVG under /logos/ is ever missing or fails
 * to decode, the tile falls back to a text monogram built from the wordmark —
 * so a missing asset degrades to something readable instead of an empty box.
 */
export default function BrandMark({
  brand,
  size = 24,
  tile = true,
  withLabel = false,
  decorative = false,
  className = "",
}: BrandMarkProps) {
  const [failed, setFailed] = useState(false);
  const entry: BrandEntry = BRAND_MARKS[brand];
  const box = Math.round(size * 1.6);

  const monogram = entry.label
    .replace(/[^A-Za-z ]/g, "")
    .split(" ")
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? "")
    .join("");

  const glyph = failed ? (
    <span
      aria-hidden="true"
      className="font-semibold leading-none"
      style={{ color: entry.hex, fontSize: Math.round(size * 0.52) }}
    >
      {monogram}
    </span>
  ) : (
    // eslint-disable-next-line @next/next/no-img-element -- local SVG asset; next/image rejects SVG without dangerouslyAllowSVG (see header)
    <img
      src={`/logos/${brand}.svg`}
      alt={withLabel || decorative ? "" : entry.label}
      width={size}
      height={size}
      loading="lazy"
      decoding="async"
      draggable={false}
      onError={() => setFailed(true)}
      style={{ width: size, height: size }}
    />
  );

  const marked = tile ? (
    <span
      className="inline-flex shrink-0 items-center justify-center rounded-[0.7rem] border"
      style={{
        width: box,
        height: box,
        background: `${tileHex(entry)}${TINT_ALPHA}`,
        borderColor: `${tileHex(entry)}${RING_ALPHA}`,
      }}
    >
      {glyph}
    </span>
  ) : (
    glyph
  );

  if (!withLabel) {
    return <span className={`inline-flex items-center ${className}`}>{marked}</span>;
  }

  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      {marked}
      <span className="min-w-0 truncate text-sm font-medium text-foreground/90">{entry.label}</span>
    </span>
  );
}
