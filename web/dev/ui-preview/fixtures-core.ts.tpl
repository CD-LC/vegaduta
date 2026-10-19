import type { PvRoute } from "../mock";
export const ME = { sub: "preview-user", email: "preview@example.com", username: "preview@example.com", firstName: "Preview", lastName: "Admin", tenantId: "t_preview", roles: ["tenant-admin"], permissions: [], plan: "PREMIUM", tenantName: "Preview Workspace" };
export const CORE: PvRoute[] = [
  { match: "/actuator/health/readiness", respond: { body: { status: "UP" } } },
  { match: "/api/public/status", respond: { body: { status: "UP", ok: true, components: [] } } },
  { match: /^\/api\/me$/, respond: { body: ME } },
  { match: "/api/notifications", respond: { headers: { "X-Total-Count": "2", "X-Has-More": "false" }, body: [
    { id: "n1", category: "BILLING", title: "Spend reached 80% of your monthly cap", body: "Example notification", read: false, createdAt: new Date().toISOString() },
    { id: "n2", category: "APPROVALS", title: "An action is waiting for approval", body: "Example notification", read: true, createdAt: new Date().toISOString() } ] } },
  { match: "/api/notifications/unread-count", respond: { body: { count: 1 } } },
];
