"use client";

import Image from "next/image";

// The REAL VegaDūta emblem (design_handoff_axon/brand/logo-mark.png - the
// winged-helmet/face-profile mark, exactly as designed), rendered pixel for
// pixel. Direct feedback: "the animation logo also it should be same exact
// human wearing a helmet, whats there in the logo, nothing extra or no
// change ... exact same pixel to pixel animation logo." An earlier version
// of this component redrew the mark as flat abstract SVG shapes (a wing
// chevron, a speed-line comet, a circuit trace) that only approximated the
// real artwork - wrong per that feedback. This version renders the actual
// transparent logo-mark.png (1024px, alpha-channel background, same file
// Logo.js's static mark is cropped from) untouched, and animates purely
// with CSS around it - an ambient glow pulse behind it and a gentle float -
// so the artwork's own pixels never change, only its motion/presence does.
//
// logo-mark.png (not the smaller favicon-64.png Logo.js uses) so the mark
// stays crisp when the browser scales it up to the 72px size the brand
// identity modal renders at, not just the ~26-32px nav sizes.
//
// `idle` (default true) runs the glow-pulse + float loop; pass idle={false}
// for a static context (e.g. next to a lot of other motion already on
// screen) where a second independent animation loop would be visual noise.
// Hover/tap tilt is handled by the call site's own wrapper (.gxa-brand-mark
// / .ax-rail-logo :hover), matching the existing .brand-lockup:hover
// .brand-mark pattern in globals.css - kept there instead of here so every
// call site doesn't have to fight this component's own hover state.
//
// ONE ARTWORK AT EVERY SIZE (direct instruction, 2026-07-25): the brand logo
// is ALWAYS this winged emblem — never the "V" monogram ("our brand logo is
// always this one, not just V"). This reverses the earlier asset-by-size rule
// that swapped in /logo-monogram.png below 40px; the .vd-mark-clip zoom hack
// below is what keeps the emblem readable at the 30-32px nav sizes.
export default function AnimatedBrandMark({ size = 32, idle = true, className = "" }) {
  return (
    <span
      className={`vd-mark${idle ? " vd-mark-idle" : ""} ${className}`}
      style={{ width: size, height: size }}
    >
      {/* Direct feedback ("increase the inner image of logo, such that its
          cleanly visible... Brand logo is not visible in that logo") -
          logo-mark.png (even after trimming its transparent canvas margin,
          see the crop note below) is an inherently wide/short emblem
          (spread wings), so at 1:1 it can only ever fill ~67% of a SQUARE
          tile's height under object-fit:contain - that's geometry, not
          fixable by cropping alone without cutting real artwork. This
          .vd-mark-clip wrapper (overflow:hidden, rounded to match the tile)
          lets .vd-mark-img run visually larger than its box via a CSS scale
          (see globals.css) and clips the small overflow at the edges,
          instead of just shrinking to fit with wasted margin on every side -
          same "bleed the icon to the edges" treatment any app-icon design
          uses. Kept as its own element (not applied to .vd-mark itself)
          because .vd-mark::before's ambient glow deliberately bleeds PAST
          this box (inset: -35%) and would get clipped if overflow:hidden
          sat on .vd-mark instead. */}
      <span className="vd-mark-clip">
        {/* next/image (perf-cwv audit follow-up, matching Logo.tsx's own
            2026-07-30 switch): this was a plain <img> serving the full 359KB
            master PNG at every call size instead of the resized/compressed
            asset Next generates. alt="" - every call site pairs this with an
            adjacent visible wordmark (the rail, the brand modal), so the
            mark itself is decorative to assistive tech. */}
        <Image
          src="/logo-mark.png"
          width={size}
          height={size}
          alt=""
          className="vd-mark-img"
          draggable={false}
        />
      </span>
    </span>
  );
}
