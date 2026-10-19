import { formatUsd, intFmt, isNum, rateFmt } from "./format";
import type { InspectorVisit } from "./types";

export function CostPanel({ visit, durationMs }: { visit: InspectorVisit; durationMs: number | null }) {
  const tin = isNum(visit.tokensIn) ? visit.tokensIn : null;
  const tout = isNum(visit.tokensOut) ? visit.tokensOut : null;
  const cost = isNum(visit.costUsd) ? visit.costUsd : null;
  const total = (tin ?? 0) + (tout ?? 0);
  const inPct = total > 0 && tin !== null ? (tin / total) * 100 : 0;
  // Derived, honest numbers only: throughput needs both output tokens and a real duration.
  const tps = tout !== null && durationMs !== null && durationMs > 0 ? tout / (durationMs / 1000) : null;
  const per1k = cost !== null && total > 0 ? (cost / total) * 1000 : null;
  return (
    <div className="ri-cost">
      {cost !== null ? (
        <div className="ri-cost-hero">
          <span className="ri-cost-v ri-num">{formatUsd(cost)}</span>
          <span className="ri-cost-l">for this visit, as metered by the platform</span>
        </div>
      ) : null}
      {total > 0 ? (
        <>
          <div
            className="ri-split"
            role="img"
            aria-label={`${intFmt.format(tin ?? 0)} input tokens and ${intFmt.format(tout ?? 0)} output tokens`}
          >
            <span className="ri-split-in" style={{ width: `${inPct}%` }} />
            <span className="ri-split-out" style={{ width: `${100 - inPct}%` }} />
          </div>
          <dl className="ri-readout">
            <div>
              <dt>
                <span className="ri-key" data-k="in" aria-hidden="true" /> Input
              </dt>
              <dd className="ri-num">{tin === null ? "-" : intFmt.format(tin)}</dd>
            </div>
            <div>
              <dt>
                <span className="ri-key" data-k="out" aria-hidden="true" /> Output
              </dt>
              <dd className="ri-num">{tout === null ? "-" : intFmt.format(tout)}</dd>
            </div>
            <div>
              <dt>Total tokens</dt>
              <dd className="ri-num">{intFmt.format(total)}</dd>
            </div>
            {tps !== null ? (
              <div>
                <dt>Output speed</dt>
                <dd className="ri-num">{rateFmt.format(tps)} tok/s</dd>
              </div>
            ) : null}
            {per1k !== null ? (
              <div>
                <dt>Per 1K tokens</dt>
                <dd className="ri-num">{formatUsd(per1k)}</dd>
              </div>
            ) : null}
          </dl>
        </>
      ) : null}
    </div>
  );
}
