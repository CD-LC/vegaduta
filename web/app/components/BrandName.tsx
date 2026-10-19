// The VegaDūta wordmark, inline. One treatment everywhere (brand directive
// 2026-10-08: "mandatory"): the Yatra One wordmark face, "Vega" in the surrounding
// foreground colour (near-black on light, white on dark) and "Dūta" in the fixed
// brand orange (--brand-accent, #f97316). All of it comes from `.brand-name`
// (globals.css), which also pins the face's single 400 weight; font-size is
// inherited, so it is safe inside headings, body copy, buttons and card text.
// `.brand-name-accent` is the colour-only class for the accent half.
//
// No "use client": renders identically from server and client components.
export default function BrandName({ className }: { className?: string }) {
  return (
    <span className={`brand-name whitespace-nowrap${className ? ` ${className}` : ""}`}>
      Vega<span className="brand-name-accent">Dūta</span>
    </span>
  );
}
