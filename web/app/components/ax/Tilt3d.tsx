"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { cn } from "../../lib/utils";

// Pointer-tracked 3D tilt wrapper for the aurora depth layer (2026-08-12
// post-auth redesign). Pure CSS-transform 3D - no WebGL, no per-frame React
// renders: pointer moves write four custom properties (--rx/--ry for the
// .tilt-3d perspective transform, --mx/--my for the .card-sheen specular
// highlight) straight onto the element inside one rAF, so the hot path never
// touches React state or layout.
//
// Self-disabling: renders as a plain passthrough div on touch devices
// (hover:none / pointer:coarse - a tilt you can't hover is just jank) and
// under prefers-reduced-motion, matching the CSS side's own media gates in
// globals.css. `maxTilt` stays small on purpose; past ~6deg the effect reads
// as a gimmick and text starts to shear.
export default function Tilt3d({
  children,
  className,
  maxTilt = 4,
}: {
  children: ReactNode;
  className?: string;
  maxTilt?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const frame = useRef(0);
  const [enabled, setEnabled] = useState(false);

  useEffect(() => {
    const query = window.matchMedia(
      "(hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)"
    );
    const update = () => setEnabled(query.matches);
    update();
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);

  const onPointerMove = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      const el = ref.current;
      if (!el) return;
      const rect = el.getBoundingClientRect();
      const px = (e.clientX - rect.left) / rect.width;
      const py = (e.clientY - rect.top) / rect.height;
      cancelAnimationFrame(frame.current);
      frame.current = requestAnimationFrame(() => {
        el.style.setProperty("--ry", `${((px - 0.5) * 2 * maxTilt).toFixed(2)}deg`);
        el.style.setProperty("--rx", `${((0.5 - py) * 2 * maxTilt).toFixed(2)}deg`);
        el.style.setProperty("--mx", `${(px * 100).toFixed(1)}%`);
        el.style.setProperty("--my", `${(py * 100).toFixed(1)}%`);
      });
    },
    [maxTilt]
  );

  const onPointerLeave = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    cancelAnimationFrame(frame.current);
    el.style.setProperty("--rx", "0deg");
    el.style.setProperty("--ry", "0deg");
  }, []);

  useEffect(() => () => cancelAnimationFrame(frame.current), []);

  if (!enabled) {
    return <div className={className}>{children}</div>;
  }

  return (
    <div
      ref={ref}
      className={cn("tilt-3d", className)}
      onPointerMove={onPointerMove}
      onPointerLeave={onPointerLeave}
    >
      {children}
    </div>
  );
}
