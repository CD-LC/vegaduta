"use client";

import { useMemo } from "react";
import { barGeometry, toneClasses, type Tone } from "../../lib/opsFormat";
import { useTranslations } from "../../../lib/i18n";

// Small shared pieces for the Operations pages. Status is never colour alone:
// every chip carries its word.

export function ToneChip({ tone, label, title }: { tone: Tone; label: string; title?: string }) {
  return (
    <span
      className={`inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-xs font-medium ${toneClasses(tone)}`}
      title={title}
    >
      {label}
    </span>
  );
}

/** A "not available" panel for an endpoint that answered with available:false or failed. */
export function UnavailableNote({ title, reason }: { title: string; reason?: string | null }) {
  return (
    <div className="rounded-lg border border-dashed border-border p-4 text-sm" role="status">
      <div className="font-medium text-foreground">{title}</div>
      {reason && <div className="mt-1 text-xs text-muted-foreground">{reason}</div>}
    </div>
  );
}

/**
 * Single-series bar chart in plain SVG (same approach as north-star's MiniChart
 * and demo-sandbox's DailyChart - no chart library). A null value draws a
 * dashed placeholder, never a zero bar.
 */
export function MiniBars({
  title,
  labels,
  values,
  fmt,
  testId,
}: {
  title: string;
  labels: string[];
  values: (number | null)[];
  fmt: (n: number) => string;
  testId?: string;
}) {
  const tHC = useTranslations("hcPlatformadminComponentsOpsOpsWidgets");
  const width = 360;
  const height = 120;
  const padTop = 10;
  const padBottom = 4;
  const geo = useMemo(() => barGeometry(values, { width, height, padTop, padBottom }), [values]);
  const barW = Math.max(3, Math.min(18, geo.slot * 0.6));
  const baseline = height - padBottom;
  const missing = values.filter((v) => v == null).length;

  return (
    <div data-testid={testId}>
      <div className="mb-1 flex items-baseline justify-between gap-2">
        <h3 className="text-xs font-medium text-foreground">{title}</h3>
        <span className="font-mono text-[11px] text-muted-foreground">peak {values.length ? fmt(geo.max) : "—"}</span>
      </div>
      {values.length === 0 ? (
        <p className="py-8 text-center text-xs text-muted-foreground">{tHC("s1")}</p>
      ) : (
        <svg
          viewBox={`0 0 ${width} ${height}`}
          className="h-[120px] w-full"
          role="img"
          aria-label={`${title}, ${values.length} points, ${missing} without data`}
        >
          <line x1={0} y1={baseline} x2={width} y2={baseline} className="stroke-border" strokeWidth={1} />
          {geo.points.map((p) => (
            <g key={p.index}>
              <title>{`${labels[p.index] ?? ""}: ${p.value == null ? "no data" : fmt(p.value)}`}</title>
              <rect x={p.x - geo.slot / 2} y={padTop} width={geo.slot} height={baseline - padTop} fill="transparent" />
              {p.value != null ? (
                <rect
                  x={p.x - barW / 2}
                  y={p.y}
                  width={barW}
                  height={Math.max(p.h, p.value > 0 ? 1 : 0)}
                  rx={1}
                  className="fill-primary"
                />
              ) : (
                <rect
                  x={p.x - barW / 2}
                  y={baseline - 10}
                  width={barW}
                  height={10}
                  rx={1}
                  className="fill-transparent stroke-muted-foreground"
                  strokeDasharray="2 2"
                />
              )}
            </g>
          ))}
        </svg>
      )}
      {labels.length > 0 && (
        <div className="flex justify-between font-mono text-[11px] text-muted-foreground">
          <span>{labels[0]}</span>
          <span>{labels[labels.length - 1]}</span>
        </div>
      )}
    </div>
  );
}
