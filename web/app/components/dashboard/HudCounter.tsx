"use client";

import { useEffect, useRef } from "react";
import { motion, useMotionValue, useSpring } from "motion/react";

// Console-local copy of the marketing site's AnimatedCounter (same spring-
// based count-up technique) rather than a cross-import from
// components/marketing/ - keeps the console and marketing site's component
// trees independent, same isolation principle marketing.css already
// follows. Always fed a REAL value from the API (see page.js's refresh()) -
// never a placeholder or fabricated figure; animates on mount rather than
// on scroll-into-view since this always renders above the fold.
// `value` is nullable by contract, not convenience: callers pass null until
// their refresh() has a real reading (e.g. admin page.tsx's `llm ?
// Number(llm.totalCalls) : null`), and the typeof guard below renders that
// as an explicit "—" - the "real value or explicitly absent" rule, never 0.
export default function HudCounter({ value, suffix = "" }: { value: number | null | undefined; suffix?: string }) {
  const motionValue = useMotionValue(0);
  const spring = useSpring(motionValue, { stiffness: 90, damping: 20, mass: 1 });
  const nodeRef = useRef(null);

  useEffect(() => {
    if (typeof value === "number") motionValue.set(value);
  }, [value, motionValue]);

  useEffect(() => {
    return spring.on("change", (latest) => {
      if (nodeRef.current) nodeRef.current.textContent = `${Math.round(latest)}${suffix}`;
    });
  }, [spring, suffix]);

  if (typeof value !== "number") {
    return <motion.span className="mc-hud-value mc-hud-value-empty">—</motion.span>;
  }

  return (
    <motion.span className="mc-hud-value">
      <span ref={nodeRef}>0{suffix}</span>
    </motion.span>
  );
}
