"use client";

import { useEffect, useRef, useState } from "react";
import { runFaqEval, type FaqEvalCase, type FaqEvalReport } from "../../lib/edge/faqEval";
import { EDGE_OPT_IN_KEY, EDGE_ROUTING_PREF_KEY } from "../../lib/edge/capabilities";
import { useTranslations } from "../../lib/i18n";

declare global {
  interface Window {
    __FAQ_EVAL_RESULT__?: FaqEvalReport;
  }
}

/**
 * On-device FAQ eval runner — the browser half of the edge-faq eval suite
 * (see web/app/lib/edge/faqEval.ts for the honesty contract and
 * core/quality/eval-harness.md for the hosted-side EvalRunner).
 *
 * Drives the REAL routeTurn() through the canonical dataset served by
 * /api/eval/edge-faq. Exposes the finished report as
 * window.__FAQ_EVAL_RESULT__ and renders it into #eval-result so the
 * Playwright spec (web/e2e/edge-faq-eval.spec.ts) can collect it.
 *
 * This is a test harness, not a product page: it opts the browser into
 * edge routing (edge.optIn=1, edge.routingPref=edge-first) so a fresh
 * profile actually attempts the on-device path instead of sitting at
 * "awaiting-user-choice". If the build lacks NEXT_PUBLIC_EDGE_AGENTS=1 or
 * the device has no local tier, every case falls through with an
 * availability reason and the report status is "edge-unavailable" — an
 * explicit skip signal, never a fabricated pass.
 *
 * ?autorun=1 (or the Playwright spec's default navigation) starts
 * immediately; otherwise a Start button is shown for manual runs.
 */
export default function EdgeFaqEvalPage() {
  const tHC = useTranslations("hcEvalEdgefaqPage");
  const [status, setStatus] = useState("idle");
  const [progress, setProgress] = useState("");
  const [report, setReport] = useState<FaqEvalReport | null>(null);
  const started = useRef(false);

  async function run() {
    if (started.current) return;
    started.current = true;
    setStatus("loading dataset");
    try {
      localStorage.setItem(EDGE_OPT_IN_KEY, "1");
      localStorage.setItem(EDGE_ROUTING_PREF_KEY, "edge-first");

      const res = await fetch("/api/eval/edge-faq");
      if (!res.ok) throw new Error(`dataset fetch failed: ${res.status}`);
      const cases = (await res.json()) as FaqEvalCase[];

      setStatus(`running ${cases.length} cases`);
      const r = await runFaqEval(cases, {
        onProgress: (done, total, last) => setProgress(`${done}/${total} ${last.id}: ${last.outcome}`),
      });
      window.__FAQ_EVAL_RESULT__ = r;
      setReport(r);
      setStatus(`done: ${r.status}`);
    } catch (err) {
      const failed: FaqEvalReport = {
        status: "aborted",
        generatedAt: new Date().toISOString(),
        datasetCaseCount: 0,
        results: [],
        summary: {
          handled: 0,
          fallback: 0,
          errors: 0,
          abstentionExpected: 0,
          abstentionDetected: 0,
          abstentionMissed: 0,
          latencyMsP50: null,
          latencyMsMax: null,
        },
      };
      window.__FAQ_EVAL_RESULT__ = failed;
      setReport(failed);
      setStatus(`failed: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  useEffect(() => {
    if (new URLSearchParams(window.location.search).get("autorun") === "1") {
      void run();
    }
  }, []);

  return (
    <main style={{ padding: 24, fontFamily: "monospace" }}>
      <h1>{tHC("s1")}</h1>
      <p data-testid="eval-status" id="eval-status">
        {status}
      </p>
      <p id="eval-progress">{progress}</p>
      {!report && (
        <button onClick={() => void run()} disabled={started.current}>
          Start
        </button>
      )}
      {report && (
        <pre id="eval-result" data-testid="eval-result">
          {JSON.stringify(report, null, 2)}
        </pre>
      )}
    </main>
  );
}
