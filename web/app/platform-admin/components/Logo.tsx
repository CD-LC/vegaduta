// Straight copy of web/app/components/Logo.js - same VegaDūta brand mark
// (design_handoff_axon/brand/), used in this app's own AdminShell topbar.
// Server-renderable (no "use client" needed), no client-only APIs. See the
// tenant console's Logo.js for why this renders /favicon-64.png rather than
// favicon.svg (empty placeholder in the brand kit) or the full-detail
// logo-mark.png (doesn't hold up at header sizes).
//
// `showMark` (default true, #920 follow-up) lets AdminShell show just the
// wordmark text here and render the actual (animated) mark once via the
// separate AnimatedBrandMark button next to it - same "don't repeat the
// logo twice" pattern the tenant console's AppShell already uses.
//
// ONE ARTWORK AT EVERY SIZE (direct instruction, 2026-07-25, mirrored from
// the tenant console's Logo.tsx): the brand logo is ALWAYS the detailed
// winged messenger, /logo-mark.png — never the "V" monogram. Sized by HEIGHT
// with width:auto + object-contain (wide/short 1024x753 artwork).
// Intrinsic artwork ratio (1024x753, see the file comment above) - passed as
// width/height attributes alongside the height style below so the browser
// can reserve the correct box (via the width/height-attribute default
// aspect-ratio) before the image has actually loaded, instead of laying out
// at zero width and shifting once it arrives.
const MARK_ASPECT = 1024 / 753;

export default function Logo({ size = 28, withWordmark = true, showMark = true, className = "" }) {
  return (
    <span className={`brand-lockup ${className}`}>
      {showMark && (
        <img
          src="/logo-mark.png"
          width={Math.round(size * MARK_ASPECT)}
          height={size}
          alt="VegaDūta"
          className="brand-mark"
          style={{ height: size, width: "auto", objectFit: "contain" }}
        />
      )}
      {withWordmark && (
        <span className="brand-name" style={{ fontSize: size * 0.62 }}>
          Vega<span className="brand-name-accent">Dūta</span>
        </span>
      )}
    </span>
  );
}
