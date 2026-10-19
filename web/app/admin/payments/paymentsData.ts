import { apiFetch, toJsonQuietOn } from "../../lib/api/core";
import { api } from "../../lib/api";
import type { MandateSpend, PaymentMandate } from "../../lib/api/payments";

/**
 * Spend-vs-cap for every ACTIVE mandate of an agent. Uses the batch endpoint
 * (GET /api/payments/mandates/spend?agentId=) when the core has it - one request
 * instead of one per mandate - and falls back to the per-mandate call otherwise
 * (older core: 404/405, or a response we cannot read). Accepts either an array
 * of MandateSpend or an object keyed by mandate id.
 */
export async function loadMandateSpends(agentId: string, mandates: PaymentMandate[]): Promise<Record<string, MandateSpend>> {
  const active = mandates.filter((m) => m.status === "ACTIVE");
  if (active.length === 0) return {};
  try {
    const res = await apiFetch(`/api/payments/mandates/spend?agentId=${encodeURIComponent(agentId)}`);
    const body = await toJsonQuietOn<unknown>([400, 403, 404, 405])(res);
    const out: Record<string, MandateSpend> = {};
    if (Array.isArray(body)) {
      for (const s of body as MandateSpend[]) if (s?.mandateId) out[s.mandateId] = s;
    } else if (body && typeof body === "object") {
      for (const [id, s] of Object.entries(body as Record<string, MandateSpend>)) {
        if (s && typeof s === "object") out[id] = { ...s, mandateId: s.mandateId ?? id };
      }
    }
    if (Object.keys(out).length > 0) return out;
  } catch {
    // fall through to the per-mandate calls
  }
  const entries = await Promise.all(
    active.map(async (m) => {
      try {
        return [m.id, await api.getMandateSpend(m.id)] as const;
      } catch {
        return null;
      }
    })
  );
  const next: Record<string, MandateSpend> = {};
  for (const e of entries) if (e) next[e[0]] = e[1];
  return next;
}
