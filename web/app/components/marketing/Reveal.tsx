"use client";

import type { ElementType, ReactNode } from "react";
import { motion } from "motion/react";

interface RevealProps {
  children: ReactNode;
  delay?: number;
  y?: number;
  duration?: number;
  className?: string;
  as?: ElementType;
  once?: boolean;
}

// Scroll-triggered stagger/reveal wrapper used by every section on the
// marketing page - the "scroll storytelling" mechanism the brief asks for,
// implemented with framer-motion's native `whileInView` (GPU-accelerated
// transform/opacity only, `once: true` so nothing re-animates and costs
// extra work on scroll-back-up).
export default function Reveal({
  children,
  delay = 0,
  y = 28,
  duration = 0.7,
  className = "",
  as: Component = motion.div,
  once = true,
}: RevealProps) {
  return (
    <Component
      className={className}
      initial={{ opacity: 0, y }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once, margin: "-80px" }}
      transition={{ duration, delay, ease: [0.16, 1, 0.3, 1] }}
    >
      {children}
    </Component>
  );
}
