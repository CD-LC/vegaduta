"use client";

import { isTrustedImageUrl, imageHostLabel } from "../lib/safeImageUrl";

// Renders an image that came from MODEL output. Trusted sources (platform
// hosts, data:/blob:, same-origin paths) render normally. Anything else is NOT
// fetched: it shows a placeholder naming the host, with an explicit
// "open in new tab" link the person must click. Deliberately not an inline
// click-to-load swap: loading on click would still send the URL's query string
// (where exfiltrated data rides) to that host.

export default function SafeModelImage({
  src,
  alt,
  className = "max-h-80 rounded-lg border border-border object-contain",
}: {
  src: string | undefined | null;
  alt?: string;
  className?: string;
}) {
  if (!src) return null;
  const origin = typeof window !== "undefined" ? window.location.origin : undefined;
  if (isTrustedImageUrl(src, origin)) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img src={src} alt={alt ?? ""} className={className} />
    );
  }
  const openable = /^https?:\/\//i.test(src);
  return (
    <span
      data-testid="blocked-image"
      className="inline-flex flex-wrap items-center gap-2 rounded-lg border border-dashed border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground"
    >
      <span>Remote image blocked ({imageHostLabel(src)}){alt ? `: ${alt}` : ""}</span>
      {openable && (
        <a
          href={src}
          target="_blank"
          rel="noopener noreferrer nofollow"
          className="font-medium text-primary underline underline-offset-2"
        >
          Open in new tab
        </a>
      )}
    </span>
  );
}
