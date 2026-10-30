"use client";

// Presentational widgets for the demo sandbox console (page.tsx): stat tiles,
// the status badge, the daily two-series chart and the settings controls.

import { useMemo } from "react";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Badge } from "../components/ui/badge";
import { Card, CardContent } from "../components/ui/card";
import { fmtInt } from "./sandboxFormat";
import { useTranslations } from "../../lib/i18n";

export function StatTile({
  label,
  value,
  hint,
  emphasis = false,
}: {
  label: string;
  value: any;
  hint?: any;
  emphasis?: boolean;
}) {
  return (
    <Card className="gap-0 py-0">
      <CardContent className="p-4">
        <div className="text-xs text-muted-foreground">{label}</div>
        <div
          className={`mt-1 font-mono text-2xl font-semibold tabular-nums ${
            emphasis ? "text-primary" : "text-foreground"
          }`}
        >
          {value}
        </div>
        {hint && <div className="mt-1 text-[11px] leading-snug text-muted-foreground">{hint}</div>}
      </CardContent>
    </Card>
  );
}

export function StatusBadge({ status }: { status: string }) {
  if (status === "CONVERTED") return <Badge className="text-[0.62rem]">Converted</Badge>;
  if (status === "LIVE")
    return (
      <Badge variant="accent" className="text-[0.62rem]">
        Live
      </Badge>
    );
  return (
    <Badge variant="secondary" className="text-[0.62rem]">
      Expired
    </Badge>
  );
}

// Grouped two-series bar chart, plain SVG. Fixed viewBox coordinate space
// scaled to the container's width by the `w-full` class, so it stays readable
// on a phone without any resize listener or layout measurement.
export function DailyChart({ series }: { series: any[] }) {
    const tHC = useTranslations("hcPlatformadminDemosandboxSandboxWidgets");
  const width = 760;
  const height = 170;
  const padBottom = 22;
  const padTop = 8;

  const max = useMemo(
    () => Math.max(1, ...series.map((p: any) => Math.max(p.created ?? 0, p.converted ?? 0))),
    [series]
  );

  if (!series.length) {
    return <p className="text-sm text-muted-foreground">{tHC("s4")}</p>;
  }

  const slot = width / series.length;
  const barW = Math.max(2, Math.min(10, slot / 2 - 1));
  const plotH = height - padBottom - padTop;
  const scale = (v: number) => (v / max) * plotH;

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-sm bg-primary" aria-hidden="true" /> {tHC("s3")}
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block size-2.5 rounded-sm bg-[var(--state-success)]" aria-hidden="true" /> {tHC("s2")}
        </span>
        <span className="ms-auto font-mono">peak {fmtInt(max)}/day</span>
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="h-[170px] w-full"
        role="img"
        aria-label={`Daily demo sandboxes created versus converted, ${series.length} days`}
      >
        {/* Baseline */}
        <line
          x1={0}
          y1={height - padBottom}
          x2={width}
          y2={height - padBottom}
          className="stroke-border"
          strokeWidth={1}
        />
        {series.map((point: any, i: number) => {
          const created = point.created ?? 0;
          const converted = point.converted ?? 0;
          const x = i * slot;
          const createdH = scale(created);
          const convertedH = scale(converted);
          return (
            <g key={point.day ?? i}>
              <title>{`${point.day}: ${created} created, ${converted} converted`}</title>
              {/* An invisible full-height hit target so the tooltip works on
                  empty days too - otherwise a day with 0 of both has nothing
                  to hover and reads as "no data" instead of "no activity". */}
              <rect x={x} y={padTop} width={slot} height={plotH} fill="transparent" />
              <rect
                x={x + slot / 2 - barW - 1}
                y={height - padBottom - createdH}
                width={barW}
                height={createdH}
                rx={1}
                className="fill-primary"
              />
              <rect
                x={x + slot / 2 + 1}
                y={height - padBottom - convertedH}
                width={barW}
                height={convertedH}
                rx={1}
                className="fill-[var(--state-success)]"
              />
            </g>
          );
        })}
      </svg>
      <div className="flex justify-between font-mono text-[11px] text-muted-foreground">
        <span>{series[0]?.day}</span>
        <span>{series[series.length - 1]?.day}</span>
      </div>
    </div>
  );
}

// A labelled on/off control. Two buttons rather than a checkbox so it matches
// the segmented theme control in AdminShell and so the ACTIVE state is legible
// at a glance - these gate an unauthenticated endpoint, and "is the demo on?"
// should never require squinting at a tick box.
export function Toggle({
  label,
  description,
  value,
  onChange,
  settingKey,
}: {
  label: string;
  description?: any;
  value: boolean;
  onChange: (next: boolean) => void;
  settingKey?: string;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border p-3">
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium text-foreground">{label}</div>
        {description && <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>}
        {settingKey && <code className="mt-1 block font-mono text-[11px] text-muted-foreground">{settingKey}</code>}
      </div>
      <div className="flex items-center gap-0.5 rounded-md border border-border bg-secondary/40 p-0.5" role="group">
        <Button
          size="sm"
          variant={value ? "default" : "ghost"}
          aria-pressed={value}
          onClick={() => onChange(true)}
          className="h-7"
        >
          On
        </Button>
        <Button
          size="sm"
          variant={!value ? "destructive" : "ghost"}
          aria-pressed={!value}
          onClick={() => onChange(false)}
          className="h-7"
        >
          Off
        </Button>
      </div>
    </div>
  );
}

export function NumberField({
  label,
  description,
  value,
  onChange,
  settingKey,
  fallback,
}: {
  label: string;
  description?: any;
  value: any;
  onChange: (next: string) => void;
  settingKey?: string;
  fallback?: any;
}) {
    const tHC = useTranslations("hcPlatformadminDemosandboxSandboxWidgets");
  return (
    <div className="rounded-lg border border-border p-3">
      <Label className="text-sm font-medium text-foreground">{label}</Label>
      {description && <p className="mt-0.5 text-xs text-muted-foreground">{description}</p>}
      <Input
        className="mt-2 max-w-40"
        inputMode="numeric"
        value={value ?? ""}
        onChange={(e) => onChange(e.target.value)}
      />
      <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-0.5">
        {settingKey && <code className="font-mono text-[11px] text-muted-foreground/70">{settingKey}</code>}
        {fallback !== undefined && fallback !== null && (
          <span className="text-[11px] text-muted-foreground/70">{tHC("s1")} {String(fallback)}</span>
        )}
      </div>
    </div>
  );
}
