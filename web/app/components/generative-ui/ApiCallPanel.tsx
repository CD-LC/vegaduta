"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Loader2 } from "lucide-react";
import { apiFetch } from "../../lib/api/core";

/**
 * The live half of an `apiCall` node (schema.ts pins what it may ask for): calls this install's own
 * API with the signed-in user's session and shows what comes back in a shape that fits it. A GET runs
 * by itself; any other verb waits for the user to press Send, so a model can propose a change but
 * never make one. `{name}` segments in the path become inputs the user fills in first.
 */

const MAX_ROWS = 50;
const MAX_COLS = 8;

type Props = {
  method: string;
  path: string;
  title?: string;
  query?: Record<string, unknown>;
  body?: unknown;
};

type Outcome = { status: number; ok: boolean; data: unknown; text: string };

export function placeholdersOf(path: string): string[] {
  return [...path.matchAll(/\{([^/{}]+)\}/g)].map((m) => m[1]);
}

export function buildPath(path: string, values: Record<string, string>, query?: Record<string, unknown>): string {
  let out = path.replace(/\{([^/{}]+)\}/g, (_, k: string) => encodeURIComponent(values[k] ?? ""));
  const usp = new URLSearchParams();
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v === undefined || v === null || typeof v === "object") continue;
    usp.set(k, String(v));
  }
  const qs = usp.toString();
  if (qs) out += (out.includes("?") ? "&" : "?") + qs;
  return out;
}

/** Table-able: a non-empty array of plain objects. */
export function isRecordList(v: unknown): v is Record<string, unknown>[] {
  return Array.isArray(v) && v.length > 0 && v.every((r) => r !== null && typeof r === "object" && !Array.isArray(r));
}

/** The list inside a page-shaped response ({content|items|data|results: [...]}) or the value itself. */
export function listOf(data: unknown): Record<string, unknown>[] | null {
  if (isRecordList(data)) return data;
  if (data && typeof data === "object" && !Array.isArray(data)) {
    for (const k of ["content", "items", "data", "results", "records"]) {
      const inner = (data as Record<string, unknown>)[k];
      if (isRecordList(inner)) return inner;
    }
  }
  return null;
}

function cell(v: unknown): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "string") return v.length > 120 ? v.slice(0, 120) + "…" : v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  const j = JSON.stringify(v);
  return j.length > 120 ? j.slice(0, 120) + "…" : j;
}

function ResultView({ outcome }: { outcome: Outcome }) {
  const [raw, setRaw] = useState(false);
  const rows = listOf(outcome.data);
  const obj =
    !rows && outcome.data && typeof outcome.data === "object" && !Array.isArray(outcome.data)
      ? (outcome.data as Record<string, unknown>)
      : null;
  let body: React.ReactNode;
  if (raw || (!rows && !obj)) {
    const text = outcome.data !== undefined ? JSON.stringify(outcome.data, null, 2) : outcome.text;
    body = (
      <pre className="max-h-72 overflow-auto rounded-lg bg-muted/50 p-2 text-xs text-foreground">{text || "(empty)"}</pre>
    );
  } else if (rows) {
    const cols = [...new Set(rows.slice(0, MAX_ROWS).flatMap((r) => Object.keys(r)))].slice(0, MAX_COLS);
    body = (
      <div className="max-h-72 overflow-auto rounded-lg border border-border">
        <table className="w-full text-xs">
          <thead>
            <tr className="border-b border-border bg-muted/40">
              {cols.map((c) => (
                <th key={c} className="px-2 py-1.5 text-left font-medium text-foreground">
                  {c}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.slice(0, MAX_ROWS).map((r, i) => (
              <tr key={i} className="border-b border-border/60 last:border-0">
                {cols.map((c) => (
                  <td key={c} className="px-2 py-1.5 align-top text-foreground">
                    {cell(r[c])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length > MAX_ROWS && (
          <p className="px-2 py-1 text-xs text-muted-foreground">Showing {MAX_ROWS} of {rows.length}.</p>
        )}
      </div>
    );
  } else {
    body = (
      <dl className="grid max-h-72 grid-cols-[auto_1fr] gap-x-4 gap-y-1 overflow-auto text-xs">
        {Object.entries(obj!).slice(0, 40).map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-muted-foreground">{k}</dt>
            <dd className="break-words text-foreground">{cell(v)}</dd>
          </div>
        ))}
      </dl>
    );
  }
  return (
    <div className="mt-2 space-y-1.5">
      <div className="flex items-center justify-between text-xs">
        <span className={outcome.ok ? "text-[var(--success-text)]" : "text-[var(--danger-text)]"}>
          {outcome.ok ? "OK" : "Failed"} · {outcome.status}
        </span>
        {(rows || obj) && (
          <button type="button" className="text-muted-foreground underline" onClick={() => setRaw((r) => !r)}>
            {raw ? "Formatted" : "Raw JSON"}
          </button>
        )}
      </div>
      {body}
    </div>
  );
}

export default function ApiCallPanel({ method, path, title, query, body }: Props) {
  const names = placeholdersOf(path);
  const [values, setValues] = useState<Record<string, string>>({});
  const [bodyText, setBodyText] = useState(body === undefined ? "" : JSON.stringify(body, null, 2));
  const [running, setRunning] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [error, setError] = useState<string | null>(null);
  const isRead = method === "GET";
  const autoRan = useRef(false);

  const run = useCallback(async () => {
    setRunning(true);
    setError(null);
    try {
      let payload: string | undefined;
      if (!isRead && bodyText.trim()) {
        try {
          payload = JSON.stringify(JSON.parse(bodyText));
        } catch {
          setError("The body is not valid JSON.");
          return;
        }
      }
      // contract-exempt: the path is runtime data - the generative-ui spec declares the endpoint,
      // so there is no literal for the contract to name; auth and error handling still go through apiFetch.
      const res = await apiFetch(buildPath(path, values, query), {
        method,
        ...(payload ? { body: payload, headers: { "Content-Type": "application/json" } } : {}),
        skipGetCache: true,
        timeoutMs: 30_000,
      });
      const text = await res.text();
      let data: unknown;
      try {
        data = text ? JSON.parse(text) : undefined;
      } catch {
        data = undefined;
      }
      setOutcome({ status: res.status, ok: res.ok, data, text });
    } catch (e) {
      setError(e instanceof Error ? e.message : "The request failed.");
    } finally {
      setRunning(false);
    }
  }, [bodyText, isRead, method, path, query, values]);

  // A read with nothing to fill in just runs; the user already asked for the answer.
  useEffect(() => {
    if (isRead && names.length === 0 && !autoRan.current) {
      autoRan.current = true;
      void run();
    }
  }, [isRead, names.length, run]);

  const ready = names.every((n) => (values[n] ?? "").trim());
  return (
    <div className="rounded-xl border border-border bg-card p-3" data-testid="api-call">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="rounded bg-secondary px-1.5 py-0.5 font-mono font-semibold text-secondary-foreground">{method}</span>
        <code className="break-all text-foreground">{path}</code>
      </div>
      {title && <p className="mt-1 text-sm text-foreground">{title}</p>}
      {names.map((n) => (
        <label key={n} className="mt-2 flex flex-col gap-1 text-xs text-muted-foreground">
          {n}
          <input
            className="rounded-md border border-border bg-background px-2 py-1 text-sm text-foreground"
            value={values[n] ?? ""}
            onChange={(e) => setValues((v) => ({ ...v, [n]: e.target.value }))}
          />
        </label>
      ))}
      {!isRead && (
        <label className="mt-2 flex flex-col gap-1 text-xs text-muted-foreground">
          Request body (JSON)
          <textarea
            rows={4}
            className="rounded-md border border-border bg-background px-2 py-1 font-mono text-xs text-foreground"
            value={bodyText}
            onChange={(e) => setBodyText(e.target.value)}
          />
        </label>
      )}
      {(!isRead || names.length > 0 || outcome) && (
        <button
          type="button"
          disabled={running || !ready}
          onClick={() => void run()}
          className="mt-2 inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground transition hover:opacity-90 disabled:opacity-60"
        >
          {running && <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />}
          {isRead ? (outcome ? "Run again" : "Run") : outcome ? "Send again" : "Send"}
        </button>
      )}
      {!isRead && !outcome && (
        <p className="mt-1 text-xs text-muted-foreground">Nothing is sent until you press Send.</p>
      )}
      {running && isRead && !outcome && <p className="mt-2 text-xs text-muted-foreground">Loading…</p>}
      {error && <p className="mt-2 text-xs text-[var(--danger-text)]">{error}</p>}
      {outcome && <ResultView outcome={outcome} />}
    </div>
  );
}
