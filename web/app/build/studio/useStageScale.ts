import { useEffect, useRef, useState } from "react";
import { DEVICE_SIZES, type Device } from "./sessionTypes";

/** Fits the chosen device frame into the preview stage (split out of useProjectSession). */
export function useStageScale(device: Device) {
  const size = DEVICE_SIZES[device];
  const [scale, setScale] = useState(1);
  const stageRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = stageRef.current;
    if (!el) return undefined;
    const fit = () => {
      const pad = device === "desktop" ? 16 : 40;
      const s = Math.min(1, (el.clientWidth - pad) / size.w, (Math.max(el.clientHeight, 520) - pad) / size.h);
      // Floor is low on purpose: on a phone the desktop frame has to shrink a lot to fit at all.
      setScale(Math.max(0.2, s));
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [device, size.w, size.h]);
  return { size, scale, stageRef };
}
