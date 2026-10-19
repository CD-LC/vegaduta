import { apiFetch, jsonBody, toJsonQuietOn } from "./core";

// "Expose as MCP tool" switch (core/mcpserver McpToolExposureController, base path
// /api/mcpserver/exposure). `kind` is "agents" or "workflows". The read is quiet on every
// plausible failure (an older core has no route; the caller may not see the resource) because the
// switch simply hides then; the write keeps 400/403/404/423 inline for the control to show.
export const mcpExposureApi = {
  getMcpExposure: (kind: "agents" | "workflows", resourceId: string) =>
    apiFetch(`/api/mcpserver/exposure/${kind}/${resourceId}`).then(
      toJsonQuietOn([400, 403, 404, 500, 502, 503, 504])
    ),
  setMcpExposure: (kind: "agents" | "workflows", resourceId: string, exposed: boolean) =>
    apiFetch(`/api/mcpserver/exposure/${kind}/${resourceId}`, {
      method: "PUT",
      ...jsonBody({ exposed }),
    }).then(toJsonQuietOn([400, 403, 404, 423])),
};
