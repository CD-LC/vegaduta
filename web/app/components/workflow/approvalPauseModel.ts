// Pure data side of the approval pause card: the request shapes it resolves
// and the defensive parsers over core's approval / input-request responses.

// Reported inline by this card: a lookup miss hides a section, 409 = already
// decided elsewhere. Keeps the global error toast from contradicting it.
export const QUIET_LOOKUP = [403, 404];
export const QUIET_ACTION = [403, 404, 409];
export interface InputField {
  key: string;
  label: string;
  type: string;
  required: boolean;
  options: string[];
}

export interface ApprovalInfo {
  id: string;
  status: string;
  summary: string | null;
  payload: string | null;
  formFields: string[];
}

export interface InputInfo {
  id: string;
  message: string | null;
  fields: InputField[];
}

export function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function str(v: unknown): string | null {
  return typeof v === "string" && v.trim() !== "" ? v : null;
}

function parseJson(raw: string | null): unknown {
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function prettyPayload(raw: string | null): string | null {
  if (!raw) return null;
  const parsed = parseJson(raw);
  return parsed === null ? raw : JSON.stringify(parsed, null, 2);
}

export function errStatus(err: unknown): number | undefined {
  return isRecord(err) && typeof err.status === "number" ? err.status : undefined;
}

export function errMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message;
  return isRecord(err) ? (str(err.message) ?? fallback) : fallback;
}

export function toApproval(v: unknown): ApprovalInfo | null {
  if (!isRecord(v)) return null;
  const id = str(v.id);
  if (!id) return null;
  const payload = str(v.payload);
  const parsed = parseJson(payload);
  const rawFields = isRecord(parsed) && Array.isArray(parsed.formFields) ? parsed.formFields : [];
  return {
    id,
    status: str(v.status) ?? "PENDING",
    summary: str(v.summary),
    payload,
    formFields: rawFields.filter((f): f is string => typeof f === "string" && f.trim() !== ""),
  };
}

export function toInput(v: unknown): InputInfo | null {
  if (!isRecord(v)) return null;
  const id = str(v.id);
  if (!id) return null;
  const schema = isRecord(v.formSchema) ? v.formSchema : null;
  const rawFields = schema && Array.isArray(schema.fields) ? schema.fields : [];
  const fields: InputField[] = [];
  for (const f of rawFields) {
    if (!isRecord(f)) continue;
    const key = str(f.key);
    if (!key) continue;
    fields.push({
      key,
      label: str(f.label) ?? key,
      type: str(f.type) ?? "text",
      required: f.required === true,
      options: Array.isArray(f.options) ? f.options.filter((o): o is string => typeof o === "string") : [],
    });
  }
  return { id, message: schema ? str(schema.message) : null, fields };
}

// Approval payloads carry runId + nodeId (OrchestratorService), which is how a
// parked node is matched to its request when the frame had no requestId.
export function payloadMatches(item: unknown, runId: string, nodeId: string): boolean {
  if (!isRecord(item)) return false;
  const p = parseJson(str(item.payload));
  return isRecord(p) && p.runId === runId && p.nodeId === nodeId;
}
