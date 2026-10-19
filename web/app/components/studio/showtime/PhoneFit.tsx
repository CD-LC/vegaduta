"use client";

import { useEffect, useRef, useState } from "react";

/** Scales the fixed-size phone to the room its parent gives it, so it is big on a monitor and still whole on a laptop. */
export function PhoneFit({ children, max = 1.1 }: { children: React.ReactNode; max?: number }) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [scale, setScale] = useState(0.8);
  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const measure = () => {
      const h = el.clientHeight || window.innerHeight * 0.7;
      const w = el.clientWidth || 340;
      setScale(Math.max(0.45, Math.min(max, (h - 8) / 640, (w - 8) / 312)));
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [max]);
  return (
    <div ref={ref} className="flex h-full w-full items-center justify-center">
      <div style={{ width: 312 * scale, height: 640 * scale }}>
        <div style={{ transform: `scale(${scale})`, transformOrigin: "top left", width: 312, height: 640 }}>
          {children}
        </div>
      </div>
    </div>
  );
}
