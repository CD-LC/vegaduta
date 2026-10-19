// Self-hosted fonts for the root layout (layout.tsx). next/font/local paths are
// relative to this file, so it stays next to layout.tsx and app/fonts/.

import localFont from "next/font/local";

// AXON design system typography (dual-font, superseding the earlier
// single-Inter pass documented in globals.css's :root block - see memory
// note "axon-font-strategy-reversal" for why this is a deliberate,
// user-approved reversal and not a regression back to old tech debt).
// Geist (UI/display) + Geist Mono (telemetry, labels, timestamps, code)
// is the exact pairing AXON Design System.dc.html specifies. Both are
// first-party Vercel fonts, self-hosted (next/font/local) like the fonts
// they replace. Noto Sans Devanagari stays separate - Hindi script
// coverage, not part of the AXON "look."
//
// Exposed under the SAME --font-inter/--font-sora/--font-mono/
// --font-jetbrains-mono/--font-brand-display variable names every existing
// font-family rule across globals.css, dashboard.css, marketing.css,
// genesis.css, and signup.css already reads (see globals.css's :root
// aliasing block) - so this is a drop-in swap of the physical typeface
// those ~40+ call sites resolve to, zero changes needed at each site.
// Variable fonts: one file per family, weight axis limited to the range used.
export const geist = localFont({
  src: "./fonts/geist.woff2",
  weight: "400 700",
  variable: "--font-geist-loaded",
});
export const geistMono = localFont({
  src: "./fonts/geist-mono.woff2",
  weight: "400 600",
  variable: "--font-geist-mono-loaded",
});
// Devanagari + Latin in one file, every shaping feature kept (conjuncts need them).
export const notoSansDevanagari = localFont({
  src: "./fonts/noto-sans-devanagari.woff2",
  weight: "400 600",
  variable: "--font-noto-devanagari",
});

// BRAND WORDMARK FACE — Yatra One (2026-07-25 brand-system unification).
//
// Applied to the wordmark ONLY (`.brand-name` and the few other wordmark
// classes in globals.css). Body/UI typography stays Geist — this is a brand
// mark, not a typography change.
//
// Why Yatra One over the other Devanagari-companion candidates:
//   - The brief is "Latin glyphs that READ as Devanagari-inspired". Yatra One
//     is the only shortlisted family whose LATIN is drawn from its Devanagari
//     — flat horizontal top strokes echoing the shirorekha, brush-cut
//     terminals, near-monolinear weight. Tiro Devanagari Sanskrit's Latin is
//     an excellent (IAST-complete) book serif but reads as a normal serif;
//     Rozha One's Latin is a Didone whose hairlines vanish at the ~17px the
//     wordmark actually renders at in the topbar.
//   - Single 400 weight that is already display-heavy, so the wordmark stays
//     strong without synthetic bold (globals.css pins font-weight:400 on
//     .brand-name for exactly this reason).
//
// ū (U+016B) verification — this is the hard requirement, and it was checked
// against the real font binary, not assumed:
//   - Google Fonts lists Yatra One's subsets as
//     ["devanagari","latin","latin-ext"]; U+016B lives in latin-ext
//     (Latin Extended-A), which is why "latin-ext" is requested below.
//   - Downloading the served TTF and parsing its cmap: U+016B maps to glyph
//     267, a SIMPLE 2-contour outline (not a composite, not .notdef) whose
//     yMax is 601 vs plain "u" at 477 — i.e. a genuinely drawn macron sitting
//     above the bowl. Same check passed for Ū/ā/ī.
export const yatraOne = localFont({
  src: "./fonts/yatra-one.woff2", // latin + latin-ext, so U+016B is in it
  weight: "400",
  display: "swap",
  variable: "--font-brand-wordmark",
});

// MARKETING DISPLAY FACE — Sora (2026-09-10, direct feedback: "UI is
// broken, fonts are broken" on the live landing, then an explicit choice of
// "modern sans throughout"). Replaces Playfair Display, which held this role
// from 2026-08-14 (itself replacing Bricolage Grotesque on 2026-08-12).
// Playfair is a high-contrast SERIF: against this product's dark, technical
// console UI its ornate headlines read as a mis-loaded font rather than an
// editorial choice, which is exactly how it was reported. Sora is a
// geometric sans built for technical/AI interfaces - it sits with Geist
// body text instead of arguing with it, and holds up at hero display sizes.
// Same role, unchanged: public-page headlines only (`.marketing-display` in
// globals.css). Body/UI typography stays Geist everywhere; the console never
// uses this. Latin subset only - the Hindi tagline keeps Noto Devanagari.
// The fallback stack and tracking in globals.css moved back to sans-serif
// values with this change - see that file's comment.
// Tier 1 (2026-09-24): ONE typeface for display + body + console, exposed
// as --font-jakarta-loaded; tokens.css aliases every legacy font token
// (--font-inter/-sora/-brand-display/-marketing-*) to it.
export const jakarta = localFont({
  src: "./fonts/plus-jakarta-sans.woff2",
  weight: "400 700",
  display: "swap",
  variable: "--font-jakarta-loaded",
});

// MARKETING BODY FACE — Inter (2026-08-12, direct instruction "change this
// font, industry standard" on the hero body copy). Scoped to .marketing-root
// like the display face above; console/UI text stays Geist.
export const dmMono = localFont({
  src: [
    { path: "./fonts/dm-mono-400.woff2", weight: "400", style: "normal" },
    { path: "./fonts/dm-mono-500.woff2", weight: "500", style: "normal" },
  ],
  display: "swap",
  variable: "--font-dm-mono-loaded",
});
