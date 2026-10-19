"use client";

// Run / dataset export downloads for EvalDatasetsSection: JUnit XML and NDJSON
// per run, and the dataset's live cases as NDJSON. Each is a no-op on an older
// api bundle that lacks the client method.

import { useCallback } from "react";
import { api } from "../../../lib/api";
import { saveTextFile } from "./pageState";

export function useEvalDownloads(agentId: string | null, expandedId: string | null) {
  // JUnit XML download for a finished run (V343 CI hook). The same file a
  // Jenkins/GitHub Actions test-report step consumes.
  const downloadJunit = useCallback(
    async (runId: string) => {
      if (!agentId || !expandedId) return;
      const client = api as any;
      if (typeof client.getEvalRunJunit !== "function") return; // older api bundle
      try {
        const xml = await client.getEvalRunJunit(agentId, expandedId, runId);
        saveTextFile(xml, "application/xml", `eval-run-${runId}.xml`);
      } catch {
        /* global toast already shown */
      }
    },
    [agentId, expandedId]
  );

  // NDJSON counterpart of downloadJunit: one JSON line per case, the format the
  // offline prompt/skill optimizer ingests (V395).
  const downloadRunJsonl = useCallback(
    async (runId: string) => {
      if (!agentId || !expandedId) return;
      const client = api as any;
      if (typeof client.getEvalRunJsonl !== "function") return; // older api bundle
      try {
        const ndjson = await client.getEvalRunJsonl(agentId, expandedId, runId);
        saveTextFile(ndjson, "application/x-ndjson", `eval-run-${runId}.jsonl`);
      } catch {
        /* global toast already shown */
      }
    },
    [agentId, expandedId]
  );

  // The dataset's live cases as NDJSON - the training-set view independent of
  // any single run.
  const downloadDatasetJsonl = useCallback(
    async (datasetId: string) => {
      if (!agentId) return;
      const client = api as any;
      if (typeof client.getEvalDatasetJsonl !== "function") return;
      try {
        const ndjson = await client.getEvalDatasetJsonl(agentId, datasetId);
        saveTextFile(ndjson, "application/x-ndjson", `eval-dataset-${datasetId}.jsonl`);
      } catch {
        /* global toast already shown */
      }
    },
    [agentId]
  );

  return { downloadJunit, downloadRunJsonl, downloadDatasetJsonl };
}
