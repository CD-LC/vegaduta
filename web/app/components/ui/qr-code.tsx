"use client";

import { useMemo } from "react";
import { QR_QUIET, qrPath } from "../../lib/qr";

type Props = {
  value: string;
  /** Rendered edge length in px. */
  px?: number;
  /** Accessible name; say what scanning does. */
  label: string;
  className?: string;
};

// Always black on white with the standard quiet zone, whatever the theme: scanners need the contrast, and a
// dark-mode-inverted code fails on several phone cameras.
export function QrCode({ value, px = 168, label, className }: Props) {
  const qr = useMemo(() => {
    try {
      return qrPath(value);
    } catch {
      return null;
    }
  }, [value]);
  if (!qr) return null;
  const box = qr.size + QR_QUIET * 2;
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${box} ${box}`}
      width={px}
      height={px}
      shapeRendering="crispEdges"
      className={className}
    >
      <rect width={box} height={box} fill="#fff" />
      <path d={qr.d} fill="#000" />
    </svg>
  );
}
