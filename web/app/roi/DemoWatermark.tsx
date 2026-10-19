"use client";

// "Example" watermark for a workspace seeded by `scripts/vegaduta seed-demo` (the server reports it at
// /api/tenant/roi/demo). Fixed over the whole viewport and pointer-transparent, so a screenshot of any part of the page
// carries it and the data underneath stays usable. Renders nothing for a real workspace or when the probe fails.
import { useEffect, useState } from "react";
import { roiPilotApi } from "../lib/api/roiPilot";
import { useTranslations } from "../lib/i18n";

export default function DemoWatermark() {
  const t = useTranslations("roiPilot");
  const [label, setLabel] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    roiPilotApi
      .getDemo()
      .then((d): void => {
        if (!cancelled) setLabel(d.demo ? (d.label ?? "") : undefined);
      })
      .catch((): void => undefined);
    return (): void => {
      cancelled = true;
    };
  }, []);

  if (label === undefined) return null;
  return (
    <>
      <div
        aria-hidden="true"
        data-testid="demo-watermark"
        className="pointer-events-none fixed inset-0 z-50 flex items-center justify-center overflow-hidden select-none"
      >
        <span className="-rotate-[25deg] text-[clamp(4rem,16vw,11rem)] font-extrabold tracking-[0.2em]" style={{ color: "color-mix(in srgb, var(--destructive) 12%, transparent)" }}>
          {t("demoWord")}
        </span>
      </div>
      <p role="status" data-testid="demo-banner" className="mb-4 rounded-xl border px-4 py-2 text-sm font-semibold" style={{ borderColor: "var(--warning-border, var(--state-warning))", background: "var(--warning-bg, transparent)", color: "var(--warning-text, inherit)" }}>
        {t("demoBanner")}
        {label ? ` (${label})` : ""}
      </p>
    </>
  );
}
