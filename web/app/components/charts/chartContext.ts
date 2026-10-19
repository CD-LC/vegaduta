"use client";

// How a chart learns its accessible name. ChartFrame provides its `summary`
// sentence here; each chart reads it for its aria-label unless the caller
// passes an explicit `ariaLabel`. Kept in its own file so a chart used
// without a frame does not pull the frame's table / button imports.
import { createContext, useContext } from "react";

export const ChartSummaryContext = createContext<string | undefined>(undefined);

/**
 * The accessible name a chart should use: its own `ariaLabel` prop if given,
 * otherwise the enclosing ChartFrame's summary sentence.
 */
export function useChartLabel(ariaLabel?: string): string | undefined {
  const summary = useContext(ChartSummaryContext);
  return ariaLabel ?? summary;
}
