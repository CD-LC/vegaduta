"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "../lib/api";
import { firePulseAtElement } from "../lib/pulseBus";
import Select from "./Select";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import { Button } from "./ui/button";
import { StatusDot, type StatusDotStatus } from "./ui/status-dot";
import { useTranslations } from "../lib/i18n";

// Mirrors WorkflowInputRequestStatus - PENDING is the actionable queue,
// "" is the "all" filter, SUBMITTED is the audit view.
const STATUS_FILTERS = ["PENDING", "", "SUBMITTED"];

// Mirrors WorkflowInputRequestController.InputRequestView.
interface InputRequestItem {
  id: string;
  runId: string;
  workflowId: string;
  workflowName?: string | null;
  nodeId?: string | null;
  nodeLabel?: string | null;
  formSchema?: { message?: string; fields?: InputFieldDef[] } | null;
  status: string;
  requestedBy?: string | null;
  createdAt?: string | null;
  submittedValues?: Record<string, any> | null;
  submittedBy?: string | null;
  submittedAt?: string | null;
}

interface InputFieldDef {
  key: string;
  label?: string;
  type?: string;
  required?: boolean;
  options?: string[];
}

function statusDot(status: string): StatusDotStatus {
  if (status === "PENDING") return "pending";
  if (status === "SUBMITTED") return "up";
  return "unknown";
}

// Workflow input requests (Tier-3) - the structured mid-run form queue
// Human Input nodes create. Same list+detail shape as ApprovalsPanel:
// pending requests on the left, the rendered form schema on the right;
// submitting resumes the run server-side (CAS + replay grant, see
// WorkflowInputRequestService).
export default function InputRequestsPanel() {
  const t = useTranslations("inputRequestsPanel");
  const [statusFilter, setStatusFilter] = useState("PENDING");
  const [requests, setRequests] = useState<InputRequestItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [formValues, setFormValues] = useState<Record<string, string>>({});

  const refresh = useCallback(async (status: string) => {
    setError(null);
    try {
      const data: InputRequestItem[] = await api.listInputRequests(status || undefined);
      setRequests(data);
      setSelectedId((prev) => (prev && data.some((r) => r.id === prev) ? prev : (data[0]?.id ?? null)));
    } catch (err: any) {
      setError(err.message || t("loadFailed"));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void refresh(statusFilter);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter]);

  const selected = useMemo(() => requests.find((r) => r.id === selectedId) || null, [requests, selectedId]);
  const fields = useMemo(() => selected?.formSchema?.fields || [], [selected]);
  const { page, pageSize, pageItems, totalPages, totalItems, setPage, setPageSize } = usePagination(requests);

  async function handleSubmit(e: React.MouseEvent<HTMLButtonElement>) {
    if (!selected) return;
    const missing = fields.filter((f) => f.required && !(formValues[f.key] || "").trim());
    if (missing.length) {
      setError(t("missingRequired", { fields: missing.map((f) => f.key).join(", ") }));
      return;
    }
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      const result = await api.submitInputRequest(selected.id, formValues);
      setFormValues({});
      setSuccess(t("submittedSuccess", { status: result.status || "RUNNING" }));
      if (e?.currentTarget) firePulseAtElement(e.currentTarget, "success");
      void refresh(statusFilter);
    } catch (err: any) {
      setError(err.message || t("submitFailed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-4 md:grid-cols-[280px_1fr]">
      <div className="rounded-lg border border-border bg-card p-3">
        <div className="mb-3 flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold">{t("queueTitle")}</h2>
          <Select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            options={STATUS_FILTERS.map((s) => ({ value: s, label: s || t("filterAll") }))}
            aria-label={t("queueTitle")}
          />
        </div>
        {pageItems.length === 0 && <p className="text-xs text-muted-foreground">{t("emptyQueue")}</p>}
        <ul className="space-y-1">
          {pageItems.map((r) => (
            <li key={r.id}>
              <button
                type="button"
                onClick={() => {
                  setSelectedId(r.id);
                  setFormValues({});
                  setError(null);
                  setSuccess(null);
                }}
                className={`w-full rounded-md border px-3 py-2 text-start text-xs transition-colors ${
                  r.id === selectedId ? "border-primary bg-primary/5" : "border-border hover:bg-secondary/50"
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium truncate">{r.workflowName || r.workflowId}</span>
                  <StatusDot status={statusDot(r.status)} />
                </div>
                <div className="mt-0.5 text-muted-foreground truncate">
                  {r.nodeLabel || r.nodeId} · {r.createdAt ? new Date(r.createdAt).toLocaleString() : ""}
                </div>
              </button>
            </li>
          ))}
        </ul>
        {totalPages > 1 && (
          <PaginationControls
            page={page}
            pageSize={pageSize}
            totalPages={totalPages}
            totalItems={totalItems}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
          />
        )}
      </div>

      <div className="rounded-lg border border-border bg-card p-4">
        {!selected ? (
          <p className="text-sm text-muted-foreground">{t("selectPrompt")}</p>
        ) : (
          <>
            <div className="mb-4">
              <h2 className="text-sm font-semibold">{selected.nodeLabel || selected.nodeId}</h2>
              <p className="mt-1 text-xs text-muted-foreground">
                {selected.workflowName || selected.workflowId} · run {selected.runId?.slice(0, 8)}… · {selected.status}
              </p>
              {selected.formSchema?.message && (
                <p className="mt-2 rounded-md bg-secondary/40 px-3 py-2 text-sm">{selected.formSchema.message}</p>
              )}
            </div>

            {error && (
              <p className="mb-3 text-sm text-destructive" role="alert">
                {error}
              </p>
            )}
            {success && (
              <p className="mb-3 text-sm text-[var(--state-success)]" role="status">
                {success}
              </p>
            )}

            {selected.status !== "PENDING" ? (
              <div className="text-sm text-muted-foreground">
                <p>{t("alreadyHandled", { status: selected.status })}</p>
                {selected.submittedValues && (
                  <pre className="mt-2 max-h-64 overflow-auto rounded-md bg-secondary/40 p-3 text-xs">
                    {JSON.stringify(selected.submittedValues, null, 2)}
                  </pre>
                )}
              </div>
            ) : (
              <div className="space-y-3">
                {fields.length === 0 && <p className="text-sm text-muted-foreground">{t("acknowledgeOnly")}</p>}
                {fields.map((f) => {
                  const fieldId = `input-request-field-${f.key}`;
                  return (
                    <div key={f.key} className="field">
                      <label htmlFor={fieldId} className="mb-1 block text-xs font-medium">
                        {f.label || f.key}
                        {f.required ? " *" : ""}
                      </label>
                      {f.type === "select" ? (
                        <Select
                          id={fieldId}
                          value={formValues[f.key] || ""}
                          onChange={(e) => setFormValues((v) => ({ ...v, [f.key]: e.target.value }))}
                          placeholder={t("selectPlaceholder")}
                          options={(f.options || []).map((o) => ({ value: o, label: o }))}
                        />
                      ) : f.type === "boolean" ? (
                        <Select
                          id={fieldId}
                          value={formValues[f.key] || ""}
                          onChange={(e) => setFormValues((v) => ({ ...v, [f.key]: e.target.value }))}
                          placeholder={t("selectPlaceholder")}
                          options={[
                            { value: "true", label: t("yesLabel") },
                            { value: "false", label: t("noLabel") },
                          ]}
                        />
                      ) : f.type === "number" ? (
                        <input
                          id={fieldId}
                          type="number"
                          className="w-full rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm"
                          value={formValues[f.key] || ""}
                          onChange={(e) => setFormValues((v) => ({ ...v, [f.key]: e.target.value }))}
                        />
                      ) : (
                        <input
                          id={fieldId}
                          type="text"
                          className="w-full rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm"
                          value={formValues[f.key] || ""}
                          onChange={(e) => setFormValues((v) => ({ ...v, [f.key]: e.target.value }))}
                        />
                      )}
                    </div>
                  );
                })}
                <Button type="button" size="sm" disabled={busy} onClick={handleSubmit}>
                  {busy ? t("submitting") : t("submitResume")}
                </Button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
