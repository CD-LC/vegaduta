import { apiFetch, toJson, jsonBody } from "../../lib/api/core";
import type { DomainView } from "./domains";

// Owner API for custom domains - /api/studio-apps/{appId}/domains on core.
export const domainsApi = {
  list: (appId: string): Promise<DomainView[]> => apiFetch(`/api/studio-apps/${appId}/domains`).then(toJson<DomainView[]>),
  add: (appId: string, hostname: string): Promise<DomainView> =>
    apiFetch(`/api/studio-apps/${appId}/domains`, { method: "POST", ...jsonBody({ hostname }) }).then(toJson<DomainView>),
  verify: (appId: string, domainId: string): Promise<DomainView> =>
    apiFetch(`/api/studio-apps/${appId}/domains/${domainId}/verify`, { method: "POST" }).then(toJson<DomainView>),
  remove: (appId: string, domainId: string): Promise<boolean> =>
    apiFetch(`/api/studio-apps/${appId}/domains/${domainId}`, { method: "DELETE" }).then((r) => r.ok),
};
