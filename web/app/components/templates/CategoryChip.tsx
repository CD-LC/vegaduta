"use client";

import type { CSSProperties } from "react";
import { categoryMeta } from "./categories";

// Rounded-square, icon-backed category identity chip (.cat-chip). Colour is
// derived from the category's --cat token; the icon is mandatory (contract §2).
// `size` maps to the shared .cat-chip size modifiers so box metrics never leak
// into call sites as inline width/height.
export default function CategoryChip({
  categoryId,
  size = "md",
  iconSize,
}: {
  categoryId: string | null | undefined;
  size?: "sm" | "md" | "lg" | "xl";
  iconSize?: number;
}) {
  const meta = categoryMeta(categoryId);
  const Icon = meta.icon;
  const sizeClass =
    size === "sm" ? "cat-chip-sm" : size === "lg" ? "cat-chip-lg" : size === "xl" ? "cat-chip-xl" : "";
  const resolvedIconSize = iconSize ?? (size === "sm" ? 13 : size === "lg" ? 18 : size === "xl" ? 26 : 15);
  return (
    <span
      className={`cat-chip ${sizeClass}`.trim()}
      style={{ ["--cat" as string]: meta.cat } as CSSProperties}
      title={meta.label}
      aria-label={meta.label}
    >
      <Icon size={resolvedIconSize} strokeWidth={2} aria-hidden="true" />
    </span>
  );
}
