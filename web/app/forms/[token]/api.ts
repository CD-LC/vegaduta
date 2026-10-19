// Client for the PUBLIC hosted-form surface (core WorkflowFormController, /api/public/workflow-forms/{token}).
//
// Same separation as /workflow/[shareId]/api.ts: a visitor here has no session and nothing but the token in the
// URL, which is the only credential these permitAll endpoints take. Deliberately self-contained - zero dependency
// on the authenticated lib/api client (no bearer token, no auth redirects, no toast bus). Nothing in this module
// throws: every call resolves to a typed result the page renders as honest, labelled content.

import { publicApiBase } from "../../lib/env";
import {
  normalizeServerErrors,
  parsePublicForm,
  safeRedirect,
  type FieldErrorCode,
  type PublicForm,
} from "./formModel";

const API_BASE = publicApiBase();

/** The backend's failure vocabulary plus "unavailable" for everything else (network down, unknown code). */
export type FormErrorCode = "not_found" | "rate_limited" | "too_large" | "unavailable";

// One flat shape with optional members (not a discriminated union): this project's tsconfig has no strictNullChecks, so
// `r.ok` alone would not narrow a union and every consumer would need a cast.
export type FormLoadResult = { ok: boolean; form?: PublicForm; error?: FormErrorCode };

export type FormSubmitResult = {
  ok: boolean;
  message?: string | null;
  redirectUrl?: string | null;
  error?: FormErrorCode | "invalid";
  fieldErrors?: Record<string, FieldErrorCode>;
};

async function readJson(res: Response): Promise<any> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

function errorFor(status: number): FormErrorCode {
  if (status === 404 || status === 410) return "not_found";
  if (status === 429) return "rate_limited";
  if (status === 413) return "too_large";
  return "unavailable";
}

async function fetchWithTimeout(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function endpoint(token: string): string {
  return `${API_BASE}/api/public/workflow-forms/${encodeURIComponent(token)}`;
}

/** GET - the form definition to render. */
export async function loadWorkflowForm(token: string): Promise<FormLoadResult> {
  try {
    const res = await fetchWithTimeout(endpoint(token), { headers: { Accept: "application/json" } }, 10000);
    if (!res.ok) return { ok: false, error: errorFor(res.status) };
    const form = parsePublicForm(await readJson(res));
    return form ? { ok: true, form } : { ok: false, error: "unavailable" };
  } catch (error) {
    console.error("Failed to load the form:", error);
    return { ok: false, error: "unavailable" };
  }
}

/**
 * POST one submission. NOT retried: a submission starts a workflow run, and a retry after a timeout could start a
 * second one. A 400 carries per-field error codes; every other failure collapses to a labelled code.
 */
export async function submitWorkflowForm(token: string, payload: unknown): Promise<FormSubmitResult> {
  try {
    const res = await fetchWithTimeout(
      endpoint(token),
      {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify(payload),
      },
      15000
    );
    const body = await readJson(res);
    if (res.status === 400) {
      return { ok: false, error: "invalid", fieldErrors: normalizeServerErrors(body?.errors) };
    }
    if (!res.ok) return { ok: false, error: errorFor(res.status) };
    return {
      ok: true,
      message: typeof body?.message === "string" && body.message ? body.message : null,
      redirectUrl: safeRedirect(body?.redirectUrl),
    };
  } catch (error) {
    console.error("Failed to submit the form:", error);
    return { ok: false, error: "unavailable" };
  }
}
