"use client";

import type { ReactElement } from "react";
import { useTranslations } from "../../../lib/i18n";

const WIDTH = 200;
const HEIGHT = 14;
const MID = WIDTH / 2;

/** "+0.12", "−0.05", "0.00": two decimals, no sign on a value that rounds to zero. */
export function formatSigned(value: number): string {
  const magnitude = Math.abs(value).toFixed(2);
  if (magnitude === "0.00") return magnitude;
  return (value > 0 ? "+" : "−") + magnitude;
}

/**
 * Prompt Knockout's one chart: a single bar from zero to the score change,
 * drawn over a shaded band for the noise floor, so "is the bar outside the
 * band?" can be read at a glance. The domain is symmetric around zero and is
 * passed in, so every row of a results list shares one scale.
 *
 * `tone` comes from the row's label rather than being re-derived here: the
 * bar is only coloured when the label says the change cleared the floor.
 */
export default function DeltaBar(props: {
  delta: number | null;
  floor: number | null;
  domain: number;
  tone: "positive" | "negative" | "neutral";
}): ReactElement {
  const { delta, floor, domain, tone } = props;
  const t = useTranslations("agentLab");
  const span = domain > 0 ? domain : 1;
  const xOf = (value: number) => MID + (Math.max(-span, Math.min(span, value)) / span) * MID;

  let label = t("koChartLabel") + ": " + (delta === null ? t("koLabelNotMeasured") : formatSigned(delta));
  if (floor !== null) label += ", " + t("koNoiseFloor", { value: floor.toFixed(2) });

  let fill = "currentColor";
  if (tone === "positive") fill = "var(--state-success)";
  else if (tone === "negative") fill = "var(--state-error)";

  const barStart = delta === null ? MID : Math.min(MID, xOf(delta));
  const barWidth = delta === null ? 0 : Math.abs(xOf(delta) - MID);

  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      preserveAspectRatio="none"
      className="block h-3.5 w-full text-muted-foreground"
    >
      <rect x="0" y="0" width={WIDTH} height={HEIGHT} rx="2" fill="currentColor" opacity="0.08" />
      {floor !== null && (
        <rect x={xOf(-floor)} y="0" width={xOf(floor) - xOf(-floor)} height={HEIGHT} fill="currentColor" opacity="0.22" />
      )}
      {barWidth > 0 && (
        <rect x={barStart} y="3" width={barWidth} height={HEIGHT - 6} fill={fill} opacity={tone === "neutral" ? 0.7 : 1} />
      )}
      <line x1={MID} y1="0" x2={MID} y2={HEIGHT} stroke="currentColor" strokeWidth="1" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}
