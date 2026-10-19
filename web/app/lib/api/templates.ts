import { apiFetch, toJson, jsonBody } from "./core";
import type {
  CommunityTemplateSummary,
  CommunityTemplateDetail,
  CommunityTemplateSubmission,
  CommunityTemplatePage,
  CommunityTemplateListParams,
  CommunityTemplateCreatePayload,
  CommunityTemplateEditPayload,
  CommunityTemplateImportResult,
  TemplateReview,
  TemplateStandardGuide,
} from "./types";

export const templatesApi = {
  // ---- Community template gallery (2026-07-30) --------------------
  // See the CommunityTemplate* interfaces above for shapes + endpoint map.
  // Public GET (approved only) - the two calls that must work without a
  // Keycloak session, so the gallery is crawlable/visitable logged-out.
  listGalleryTemplates: async (params: CommunityTemplateListParams = {}): Promise<CommunityTemplatePage> => {
    const p = new URLSearchParams();
    if (params.kind) p.set("kind", params.kind);
    if (params.category) p.set("category", params.category);
    if (params.tag) p.set("tag", params.tag);
    // `params.page` is the UI-facing 1-based page ("Page 1", "Page 2", ...);
    // the controller's `page` param is a Spring `Pageable` index, which is
    // 0-based. Sending the raw 1-based value verbatim (the bug, confirmed
    // 2026-08-10: every client-side re-fetch after the first requested one
    // page further than intended - e.g. UI "page 1" asked the backend for
    // its SECOND page, OFFSET size) silently truncated or zeroed every
    // filtered view with fewer than `size` * 2 approved rows - exactly what
    // made kind=WORKFLOW (5 rows) look completely empty. Always subtract 1
    // here, clamped at 0, so UI page 1 maps to backend page 0.
    if (params.page) p.set("page", String(Math.max(0, params.page - 1)));
    // The controller's @RequestParam is named `size`, not `pageSize` - a
    // mismatch that shipped because the item shape was reconciled against the
    // real backend but the query param name and the response envelope were
    // not. Confirmed live 2026-07-30: with the old name every request silently
    // used the server default (page 0, size 24) regardless of what was asked.
    if (params.pageSize) p.set("size", String(params.pageSize));
    const qs = p.toString();
    const items = await apiFetch(`/api/templates/gallery${qs ? `?${qs}` : ""}`).then(
      toJson<CommunityTemplateSummary[]>
    );
    const pageSize = params.pageSize ?? 24;
    return {
      items,
      page: params.page ?? 1,
      pageSize,
      // Heuristic, not a real count - see CommunityTemplatePage's comment.
      hasMore: items.length >= pageSize,
    };
  },
  getGalleryTemplate: (id: string): Promise<CommunityTemplateDetail> =>
    apiFetch(`/api/templates/gallery/${id}`).then(toJson<CommunityTemplateDetail>),
  // Authenticated: instantiates the template into the caller's own tenant.
  importTemplate: (id: string): Promise<CommunityTemplateImportResult> =>
    apiFetch(`/api/templates/${id}/import`, { method: "POST" }).then(toJson<CommunityTemplateImportResult>),
  // Authenticated: the caller's own submissions with lifecycle status.
  listMyTemplates: (): Promise<CommunityTemplateSubmission[]> =>
    apiFetch(`/api/templates/mine`).then(toJson<CommunityTemplateSubmission[]>),
  // Always creates a DRAFT - see CommunityTemplateCreatePayload. To submit for
  // review, follow this with submitTemplate(id); there is no submit-on-create.
  createTemplate: (payload: CommunityTemplateCreatePayload): Promise<CommunityTemplateSubmission> =>
    apiFetch(`/api/templates`, { method: "POST", ...jsonBody(payload) }).then(toJson<CommunityTemplateSubmission>),
  updateTemplate: (id: string, payload: CommunityTemplateEditPayload): Promise<CommunityTemplateSubmission> =>
    apiFetch(`/api/templates/${id}`, { method: "PUT", ...jsonBody(payload) }).then(toJson<CommunityTemplateSubmission>),
  submitTemplate: (id: string): Promise<CommunityTemplateSubmission> =>
    apiFetch(`/api/templates/${id}/submit`, { method: "POST" }).then(toJson<CommunityTemplateSubmission>),
  // The published standard (public GET): principles, every rule with its fix, steps, worked example.
  getTemplateStandard: (): Promise<TemplateStandardGuide> =>
    apiFetch(`/api/templates/gallery/standard`).then(toJson<TemplateStandardGuide>),
  // Check a template against the standard WITHOUT submitting. ai=true adds the AI review.
  validateTemplate: (id: string, ai = false): Promise<TemplateReview> =>
    apiFetch(`/api/templates/${id}/validate?ai=${ai ? "true" : "false"}`, { method: "POST" }).then(
      toJson<TemplateReview>
    ),
  withdrawTemplate: (id: string): Promise<CommunityTemplateSubmission> =>
    apiFetch(`/api/templates/${id}/withdraw`, { method: "POST" }).then(toJson<CommunityTemplateSubmission>),
};
