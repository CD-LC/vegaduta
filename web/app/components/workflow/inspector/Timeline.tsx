import { formatDuration, isNum } from "./format";
import type { InspectorVisit, RunStepInspectorProps } from "./types";

export function VisitRibbon({ index, count }: { index: number; count: number }) {
  // Decorative: the "k/n" text beside it carries the meaning.
  const MAX = 12;
  if (count > MAX) {
    return (
      <span className="ri-ribbon" aria-hidden="true">
        <span className="ri-ribbon-track">
          <span className="ri-ribbon-fill" style={{ width: `${((index + 1) / count) * 100}%` }} />
        </span>
      </span>
    );
  }
  return (
    <span className="ri-ribbon" aria-hidden="true">
      {Array.from({ length: count }, (_, i) => (
        <span key={i} className="ri-dot" data-on={i === index ? "true" : undefined} data-past={i < index ? "true" : undefined} />
      ))}
    </span>
  );
}

export function FlightStrip({ visit, runSpan, now, durationMs }: {
  visit: InspectorVisit; runSpan?: RunStepInspectorProps["runSpan"]; now: number; durationMs: number | null;
}) {
  if (!runSpan || !isNum(runSpan.startMs) || !isNum(visit.startMs)) return null;
  const runEnd = isNum(runSpan.endMs) ? runSpan.endMs : now;
  const total = Math.max(1, runEnd - runSpan.startMs);
  const off = Math.max(0, visit.startMs - runSpan.startMs);
  const len = Math.max(0, durationMs ?? 0);
  const leftPct = Math.min(100, (off / total) * 100);
  const widthPct = Math.max(0.8, Math.min(100 - leftPct, (len / total) * 100));
  const label = `Started at T+${formatDuration(off)} and ran ${formatDuration(len)} of a ${formatDuration(total)} run`;
  return (
    <div className="ri-flight" role="img" aria-label={label}>
      <span className="ri-flight-t">T+{formatDuration(off)}</span>
      <span className="ri-flight-track">
        <span className="ri-flight-seg" style={{ left: `${leftPct}%`, width: `${widthPct}%` }} />
      </span>
      <span className="ri-flight-t">{formatDuration(total)}</span>
    </div>
  );
}
