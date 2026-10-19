import { useEffect, useState } from "react";
import RequestInspector, { type LlmRequestSnapshot } from "../../agents/RequestInspector";
import { useTranslations } from "../../../lib/i18n";
import { api } from "../../../lib/api";
import { isNum } from "./format";

/** Fetches what this node visit actually sent to the model (EDITOR-gated, in-memory on the server). */
export function SentPanel({ workflowId, runId, startMs, endMs }: { workflowId: string; runId: string; startMs?: number | null; endMs?: number | null }) {
  const t = useTranslations("requestInspector");
  const [state, setState] = useState<{ status: "loading" | "ok" | "forbidden" | "error"; items: LlmRequestSnapshot[] }>({ status: "loading", items: [] });
  useEffect(() => {
    let cancelled = false;
    // A small pad either side: the snapshot is stamped when the call starts, the trace span when the node does.
    const from = isNum(startMs) ? new Date(startMs - 1000).toISOString() : undefined;
    const to = isNum(endMs) ? new Date(endMs + 1000).toISOString() : undefined;
    setState({ status: "loading", items: [] });
    api
      .getWorkflowRunLlmRequests(workflowId, runId, from, to)
      .then((items: LlmRequestSnapshot[]) => {
        if (!cancelled) setState({ status: "ok", items: Array.isArray(items) ? items : [] });
      })
      .catch((e: { status?: number }) => {
        if (!cancelled) setState({ status: e?.status === 403 ? "forbidden" : "error", items: [] });
      });
    return () => {
      cancelled = true;
    };
  }, [workflowId, runId, startMs, endMs]);

  if (state.status === "loading") return <p className="text-xs text-muted-foreground">{t("workflowLoading")}</p>;
  if (state.status === "forbidden") return <p className="text-xs text-muted-foreground">{t("workflowForbidden")}</p>;
  if (state.status === "error") return <p className="text-xs text-muted-foreground">{t("workflowError")}</p>;
  if (state.items.length === 0) return <p className="text-xs text-muted-foreground">{t("workflowEmpty")}</p>;
  return <RequestInspector snapshots={state.items} defaultOpen />;
}
