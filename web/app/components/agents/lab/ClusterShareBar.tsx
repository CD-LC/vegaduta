"use client";

// Agent Lab - the Consistency check's one-line picture: what share of the
// samples landed in each answer group. A single stacked bar, largest group
// first. Groups are told apart three ways - a 2px gap, a step of one ink
// ramp (group 1 darkest), and the group number written inside the segment -
// so nothing rests on colour alone, and the group list under it doubles as
// the legend (ClusterSwatch) and the table view.
import { useId, type ReactElement } from "react";
import { useTranslations } from "../../../lib/i18n";

const WIDTH = 448;
const HEIGHT = 24;
const GAP = 2;
const RADIUS = 4;

// One step per group, strongest first. At most 8 samples can be compared, so
// at most 8 groups; anything past the table reuses the faintest step.
const INK_STEPS = [0.9, 0.74, 0.6, 0.42, 0.32, 0.24, 0.17, 0.11];
// Steps dark enough that the number inside reads better in the page colour.
const INVERSE_LABEL_STEPS = 3;

const inkStep = (index: number) => INK_STEPS[Math.min(index, INK_STEPS.length - 1)];

/** The same fill a group has in the bar, as a small square for its heading. */
export function ClusterSwatch(props: { index: number }): ReactElement {
  return (
    <svg aria-hidden="true" viewBox="0 0 12 12" className="size-3 shrink-0">
      <rect width="12" height="12" rx="3" fillOpacity={inkStep(props.index)} className="fill-foreground" />
    </svg>
  );
}

export default function ClusterShareBar(props: { sizes: number[] }): ReactElement | null {
  const t = useTranslations("agentLab");
  // useId can contain characters that are not safe inside url(#...).
  const clipId = `lab-share-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const sizes = props.sizes.filter((size) => size > 0);
  const total = sizes.reduce((acc, size) => acc + size, 0);
  if (total <= 0) return null;

  const drawable = WIDTH - GAP * (sizes.length - 1);
  let x = 0;
  const segments = sizes.map((size, index) => {
    const width = (size / total) * drawable;
    const segment = { index, size, x, width };
    x += width + GAP;
    return segment;
  });

  return (
    <svg
      role="img"
      aria-label={t("ccShareBarLabel")}
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      className="block h-auto w-full max-w-md"
    >
      <defs>
        {/* Rounds the two outer ends only; the joins between groups stay square. */}
        <clipPath id={clipId}>
          <rect x="0" y="0" width={WIDTH} height={HEIGHT} rx={RADIUS} />
        </clipPath>
      </defs>
      <g clipPath={`url(#${clipId})`}>
        {segments.map((segment) => (
          <g key={segment.index}>
            <title>
              {`${t("ccGroupLabel", { n: segment.index + 1 })}: ${t("ccGroupCount", { count: segment.size, total })}`}
            </title>
            <rect
              x={segment.x}
              y="0"
              width={segment.width}
              height={HEIGHT}
              fillOpacity={inkStep(segment.index)}
              className="fill-foreground"
            />
            <text
              x={segment.x + segment.width / 2}
              y={HEIGHT / 2}
              textAnchor="middle"
              dominantBaseline="central"
              fontSize="12"
              fontWeight="600"
              className={segment.index < INVERSE_LABEL_STEPS ? "fill-background" : "fill-foreground"}
            >
              {segment.index + 1}
            </text>
          </g>
        ))}
      </g>
    </svg>
  );
}
