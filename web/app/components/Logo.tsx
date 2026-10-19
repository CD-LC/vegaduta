import Image from "next/image";

// Shared brand mark - one place to change the logo everywhere it appears
// (login landing, signup, signup/complete, the main console header, the
// admin console header) instead of four copy-pasted <svg> blocks drifting
// out of sync. Pure presentational component, no client-only APIs, so it
// can render on the server too (no "use client" needed).
//
// The mark is the real VegaDūta emblem (design_handoff_axon/brand/) - a
// winged messenger in the brand's gold/violet/cyan gradient, "the swift
// messenger" carrying intent to done. Replaces the earlier placeholder
// abstract node-graph SVG (kept only in git history) that stood in before
// the client's actual logo was uploaded and extracted into the brand kit.
// Rendered via next/image against /public/logo-mark.png (switched from a
// plain <img> in the performance-cwv audit, 2026-07-30 - Next resizes/
// compresses the 359KB 1024px master into the small WebP/AVIF this component
// actually displays instead of shipping the full file at every call site)
// - the kit's own favicon.svg is an empty placeholder (verified by opening
// it), which is why the monogram favicon set is a separate pre-rendered
// PNG rather than a shared vector, and why this brand mark stays a raster
// asset rather than being re-vectorized.
// `showMark` (default true) lets a call site that already renders the brand
// mark elsewhere on screen (AppShell's topbar sits directly next to the left
// rail's own AnimatedBrandMark) show just the wordmark text here instead of
// a second copy of the logo image - direct feedback: "display logo in only
// one section and brand name in header - dont repeat the logo twice."
//
// ONE ARTWORK AT EVERY SIZE (direct instruction, 2026-07-25): the brand logo
// is ALWAYS the detailed winged messenger, /logo-mark.png — never the "V"
// monogram ("our brand logo is always this one, not just V"). This reverses
// the earlier asset-by-size rule that swapped in /favicon-64.png below 40px;
// the monogram/favicon set stays only as the browser tab icon. The emblem is
// a wide, short artwork (1024x753), so it is sized by HEIGHT with width:auto
// + object-contain — passing width=height=size would squash it to a square.
// 1024x753 master asset (see the header comment) - next/image needs an
// intrinsic width/height pair to size and optimize against, so this derives
// the width from the real aspect ratio for whatever `size` (height) is
// requested, same as the old inline `width: "auto"` did visually.
const LOGO_ASPECT = 1024 / 753;

export default function Logo({ size = 28, withWordmark = true, showMark = true, className = "" }) {
  return (
    <span className={`brand-lockup ${className}`}>
      {showMark && (
        <Image
          src="/logo-mark.png"
          height={size}
          width={Math.round(size * LOGO_ASPECT)}
          // Decorative when the wordmark renders right beside it (the default
          // and by far the most common call) - a visible text label already
          // announces the brand name, so a second "VegaDūta" on the image
          // would repeat it. Icon-alone call sites (withWordmark=false) keep
          // the descriptive alt, since nothing else names the brand there.
          alt={withWordmark ? "" : "VegaDūta"}
          className="brand-mark"
          style={{ height: size, width: "auto", objectFit: "contain" }}
          priority
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
