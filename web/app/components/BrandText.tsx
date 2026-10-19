"use client";

import { Fragment, type ReactNode } from "react";
import BrandName from "./BrandName";

// The wordmark inside arbitrary text (brand directive 2026-10-08, mandatory:
// the Yatra One face + "Vega" in the foreground colour + "Dūta" in brand orange,
// everywhere the name is shown). Splits a translated string on the name and
// renders each occurrence through <BrandName />, i.e. the same `.brand-name`
// treatment as the nav lockups. whitespace-nowrap keeps "VegaDūta" from ever
// line-breaking mid-name.
//
// Use for VISIBLE text nodes. Never feed aria-labels/alt/placeholder
// through this — those are plain-string attribute slots.
export default function BrandText({ text }: { text: ReactNode }) {
  if (typeof text !== "string" || !text.includes("VegaDūta")) return <>{text}</>;
  const parts = text.split("VegaDūta");
  return (
    <>
      {parts.map((part, i) => (
        <Fragment key={i}>
          {part}
          {i < parts.length - 1 && (
            <BrandName />
          )}
        </Fragment>
      ))}
    </>
  );
}
