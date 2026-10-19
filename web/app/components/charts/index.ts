// Shared chart kit - the only charts the console should need. No chart
// library: plain SVG / HTML, colours from the --state-* and shadcn tokens.
//
// Usage shape:
//   <ChartFrame title=... summary="42 of 50 runs succeeded this week." table={{ columns, rows }}>
//     <MiniBars data={...} partialLast partialLabel={soFarLabel} />
//   </ChartFrame>
// A chart inside a ChartFrame takes the frame's summary as its aria-label.
// Built-in strings live in the `charts` namespace of messages/en.json.
export { default as ChartFrame } from "./ChartFrame";
export type { ChartFrameProps, ChartTableColumn, ChartTableData } from "./ChartFrame";
export { default as MiniBars } from "./MiniBars";
export type { MiniBarDatum, MiniBarsProps } from "./MiniBars";
export { default as SuccessRing } from "./SuccessRing";
export type { SuccessRingProps } from "./SuccessRing";
export { default as StackedStatusBar } from "./StackedStatusBar";
export type { StackedStatusBarProps, StatusSegment } from "./StackedStatusBar";
export { default as RunStrip } from "./RunStrip";
export type { RunStripItem, RunStripProps } from "./RunStrip";
export { default as RankedBars } from "./RankedBars";
export type { RankedBarItem, RankedBarsProps } from "./RankedBars";
export { default as FlowExplainer } from "../explainers/FlowExplainer";
export type { FlowExplainerProps, FlowOutcome, FlowStep } from "../explainers/FlowExplainer";
export { ChartSummaryContext, useChartLabel } from "./chartContext";
export * from "./chartMath";
