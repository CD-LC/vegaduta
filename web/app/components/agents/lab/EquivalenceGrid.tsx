"use client";

// Agent Lab - the judge's raw verdicts behind the Consistency check's answer
// groups: an n x n grid, one cell per pair of samples. Each verdict has its
// own glyph as well as its own tint (= same meaning, a crossed = for
// different, ? for a pair the judge gave no verdict on), so "not compared"
// can never be misread as "different" by anyone who cannot see the colours.
import type { ReactElement } from "react";
import { useTranslations } from "../../../lib/i18n";
import { pairVerdict } from "./consistencyMath";

type Verdict = "same" | "different" | "unknown";

const HEADER = 22;
const CELL = 28;
const GAP = 2;
const THRESHOLD = 0.5;

const GLYPH_SAME = "=";
const GLYPH_DIFFERENT = "≠";
const GLYPH_UNKNOWN = "?";

function verdictOf(matrix: (number | null)[][], a: number, b: number): Verdict {
  const value = pairVerdict(matrix, a, b);
  if (value === null) return "unknown";
  return value >= THRESHOLD ? "same" : "different";
}

function glyphOf(verdict: Verdict): string {
  if (verdict === "same") return GLYPH_SAME;
  if (verdict === "different") return GLYPH_DIFFERENT;
  return GLYPH_UNKNOWN;
}

/** One verdict's tinted square. The tint is a wash; the glyph carries it. */
function VerdictRect(props: { verdict: Verdict; x: number; y: number }): ReactElement {
  const { verdict, x, y } = props;
  if (verdict === "same") {
    return <rect x={x} y={y} width={CELL} height={CELL} rx="4" fillOpacity="0.2" style={{ fill: "var(--state-success)" }} />;
  }
  if (verdict === "different") {
    return <rect x={x} y={y} width={CELL} height={CELL} rx="4" fillOpacity="0.2" style={{ fill: "var(--state-error)" }} />;
  }
  return <rect x={x} y={y} width={CELL} height={CELL} rx="4" fillOpacity="0.07" className="fill-foreground" />;
}

function LegendItem(props: { verdict: Verdict; label: string }): ReactElement {
  return (
    <li className="flex items-center gap-1.5">
      <svg aria-hidden="true" viewBox={`0 0 ${CELL} ${CELL}`} className="size-4 shrink-0">
        <VerdictRect verdict={props.verdict} x={0} y={0} />
        <text x={CELL / 2} y={CELL / 2} textAnchor="middle" dominantBaseline="central" fontSize="20" className="fill-foreground">
          {glyphOf(props.verdict)}
        </text>
      </svg>
      {props.label}
    </li>
  );
}

/**
 * `matrix` is n x n and aligned to `sampleNumbers` (the 1-based sample number
 * of each row/column - failed samples are absent, so the numbers can skip).
 */
export default function EquivalenceGrid(props: { matrix: (number | null)[][]; sampleNumbers: number[] }): ReactElement | null {
  const { matrix, sampleNumbers } = props;
  const t = useTranslations("agentLab");
  const n = sampleNumbers.length;
  if (n < 2) return null;

  const size = HEADER + n * CELL + (n - 1) * GAP;
  const offset = (index: number) => HEADER + index * (CELL + GAP);

  const titleOf = (a: number, b: number, verdict: Verdict): string => {
    if (verdict === "same") return t("ccMatrixCell", { a, b, verdict: t("ccMatrixSame") });
    if (verdict === "different") return t("ccMatrixCell", { a, b, verdict: t("ccMatrixDifferent") });
    return t("ccMatrixCell", { a, b, verdict: t("ccMatrixUnknown") });
  };

  return (
    <div className="space-y-2">
      <svg
        role="img"
        aria-label={t("ccMatrixTitle")}
        viewBox={`0 0 ${size} ${size}`}
        width={size}
        height={size}
        className="block h-auto max-w-full"
      >
        {sampleNumbers.map((sampleNumber, index) => (
          <g key={sampleNumber} fontSize="11" className="fill-muted-foreground">
            <text x={offset(index) + CELL / 2} y={HEADER / 2} textAnchor="middle" dominantBaseline="central">
              {sampleNumber}
            </text>
            <text x={HEADER / 2} y={offset(index) + CELL / 2} textAnchor="middle" dominantBaseline="central">
              {sampleNumber}
            </text>
          </g>
        ))}
        {sampleNumbers.map((rowNumber, row) =>
          sampleNumbers.map((colNumber, col) => {
            const x = offset(col);
            const y = offset(row);
            // A sample against itself: a faint placeholder, nothing to read.
            if (row === col) {
              return <rect key={`${row}:${col}`} x={x} y={y} width={CELL} height={CELL} rx="4" fillOpacity="0.03" className="fill-foreground" />;
            }
            const verdict = verdictOf(matrix, row, col);
            return (
              <g key={`${row}:${col}`} data-verdict={verdict}>
                <title>{titleOf(rowNumber, colNumber, verdict)}</title>
                <VerdictRect verdict={verdict} x={x} y={y} />
                <text
                  x={x + CELL / 2}
                  y={y + CELL / 2}
                  textAnchor="middle"
                  dominantBaseline="central"
                  fontSize="14"
                  className="fill-foreground"
                >
                  {glyphOf(verdict)}
                </text>
              </g>
            );
          })
        )}
      </svg>
      <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <LegendItem verdict="same" label={t("ccMatrixSame")} />
        <LegendItem verdict="different" label={t("ccMatrixDifferent")} />
        <LegendItem verdict="unknown" label={t("ccMatrixUnknown")} />
      </ul>
    </div>
  );
}
