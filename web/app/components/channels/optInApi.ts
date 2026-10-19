import { apiFetch } from "../../lib/api/core";
import { pagedQuery, toPaged, type PagedRows } from "../../lib/api/paged";

/** One row of GET /api/channels/whatsapp-compliance/opt-in (WhatsAppPolicyController.OptInListItemResponse). */
export interface OptInListRow {
  phoneDigits: string;
  source: string;
  active: boolean;
  consentedAt?: string | null;
  revokedAt?: string | null;
}

/**
 * One page of this tenant's opt-in records, newest consent first. Reads X-Total-Count / X-Has-More.
 * Rejects when the endpoint is missing (older core) or the call fails; the card then falls back to
 * the records handled in this browser session.
 */
export async function listWhatsAppOptIns(
  limit: number,
  offset: number,
  activeOnly = false
): Promise<PagedRows<OptInListRow>> {
  const params = new URLSearchParams();
  if (activeOnly) params.set("activeOnly", "true");
  const res = await apiFetch(`/api/channels/whatsapp-compliance/opt-in?${pagedQuery(params, offset, limit)}`, {
    skipGetCache: true,
  });
  return toPaged<OptInListRow>(res, offset, limit);
}
