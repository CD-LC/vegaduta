// Pure model of the PUBLIC hosted form (core WorkflowFormController/WorkflowFormSchema): parsing the definition
// the server sends, the client-side pre-check, and the payload. No React, no network - unit-testable. The pre-check
// only saves a round trip: the server validates every field again and its error codes win.

export type PublicFieldType = "text" | "long_text" | "number" | "email" | "select" | "checkbox" | "date";

export type PublicField = {
  key: string;
  label: string;
  type: PublicFieldType;
  required: boolean;
  placeholder: string;
  options: string[];
  maxLength: number | null;
  min: number | null;
  max: number | null;
};

export type PublicForm = {
  title: string;
  description: string;
  submitLabel: string;
  fields: PublicField[];
  /** Name of the hidden honeypot input the page must render empty (a bot fills it; a person never sees it). */
  honeypotField: string;
};

export type FormValues = Record<string, string | boolean>;

/** The stable error codes the server returns (and this pre-check reuses) - the page translates them. */
export type FieldErrorCode =
  | "required"
  | "too_long"
  | "invalid_number"
  | "out_of_range"
  | "invalid_email"
  | "invalid_option"
  | "invalid_date"
  | "invalid_type"
  | "invalid_characters"
  | "invalid_body";

export const KNOWN_ERROR_CODES: readonly string[] = [
  "required",
  "too_long",
  "invalid_number",
  "out_of_range",
  "invalid_email",
  "invalid_option",
  "invalid_date",
  "invalid_type",
  "invalid_characters",
  "invalid_body",
];

const TYPES: readonly string[] = ["text", "long_text", "number", "email", "select", "checkbox", "date"];
const EMAIL = /^[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,24}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

/** Defensive parse of GET /api/public/workflow-forms/{token}; null when the body is not a usable form. */
export function parsePublicForm(body: unknown): PublicForm | null {
  if (!body || typeof body !== "object") return null;
  const b = body as Record<string, unknown>;
  if (!Array.isArray(b.fields) || b.fields.length === 0) return null;
  const fields: PublicField[] = [];
  for (const raw of b.fields) {
    if (!raw || typeof raw !== "object") return null;
    const f = raw as Record<string, unknown>;
    const type = str(f.type);
    if (!str(f.key) || !TYPES.includes(type)) return null;
    fields.push({
      key: str(f.key),
      label: str(f.label) || str(f.key),
      type: type as PublicFieldType,
      required: f.required === true,
      placeholder: str(f.placeholder),
      options: Array.isArray(f.options) ? f.options.filter((o): o is string => typeof o === "string") : [],
      maxLength: num(f.maxLength),
      min: num(f.min),
      max: num(f.max),
    });
  }
  return {
    title: str(b.title),
    description: str(b.description),
    submitLabel: str(b.submitLabel),
    fields,
    honeypotField: str(b.honeypotField) || "_hp",
  };
}

export function initialValues(fields: PublicField[]): FormValues {
  const values: FormValues = {};
  for (const f of fields) values[f.key] = f.type === "checkbox" ? false : "";
  return values;
}

/** Client-side pre-check mirroring the server's rules; returns field key -> error code (empty = ok). */
export function validateValues(fields: PublicField[], values: FormValues): Record<string, FieldErrorCode> {
  const errors: Record<string, FieldErrorCode> = {};
  for (const f of fields) {
    const raw = values[f.key];
    if (f.type === "checkbox") {
      if (f.required && raw !== true) errors[f.key] = "required";
      continue;
    }
    const text = typeof raw === "string" ? raw.replace(/\r\n/g, "\n").trim() : "";
    if (!text) {
      if (f.required) errors[f.key] = "required";
      continue;
    }
    if (f.maxLength != null && (f.type === "text" || f.type === "long_text") && text.length > f.maxLength) {
      errors[f.key] = "too_long";
      continue;
    }
    switch (f.type) {
      case "number": {
        const n = Number(text);
        if (!Number.isFinite(n) || text.length > 40) errors[f.key] = "invalid_number";
        else if ((f.min != null && n < f.min) || (f.max != null && n > f.max)) errors[f.key] = "out_of_range";
        break;
      }
      case "email":
        if (text.length > 254) errors[f.key] = "too_long";
        else if (!EMAIL.test(text)) errors[f.key] = "invalid_email";
        break;
      case "select":
        if (!f.options.includes(text)) errors[f.key] = "invalid_option";
        break;
      case "date":
        if (!DATE.test(text) || Number.isNaN(Date.parse(`${text}T00:00:00Z`))) errors[f.key] = "invalid_date";
        break;
      default:
        break;
    }
  }
  return errors;
}

/** The POST body: trimmed strings, real booleans, and the (empty) honeypot. Number fields are sent as typed text. */
export function toPayload(fields: PublicField[], values: FormValues, honeypotField: string, honeypotValue: string) {
  const out: Record<string, string | boolean> = {};
  for (const f of fields) {
    const raw = values[f.key];
    out[f.key] = f.type === "checkbox" ? raw === true : typeof raw === "string" ? raw.trim() : "";
  }
  return { values: out, [honeypotField]: honeypotValue };
}

/** A redirect target only if it is an absolute https URL with a host and no credentials - never anything else. */
export function safeRedirect(url: unknown): string | null {
  if (typeof url !== "string" || !url || url.length > 2000) return null;
  try {
    const u = new URL(url);
    return u.protocol === "https:" && !!u.hostname && !u.username && !u.password ? u.toString() : null;
  } catch {
    return null;
  }
}

/** Server error map -> only codes this page can translate (anything else degrades to "invalid_type"). */
export function normalizeServerErrors(raw: unknown): Record<string, FieldErrorCode> {
  const out: Record<string, FieldErrorCode> = {};
  if (!raw || typeof raw !== "object") return out;
  for (const [key, code] of Object.entries(raw as Record<string, unknown>)) {
    out[key] = (typeof code === "string" && KNOWN_ERROR_CODES.includes(code) ? code : "invalid_type") as FieldErrorCode;
  }
  return out;
}
