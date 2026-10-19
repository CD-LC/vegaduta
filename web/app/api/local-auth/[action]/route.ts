import { handleLocalAuth } from "../../../lib/localAuthProxy";

// Same-origin proxy to core's local single-user auth (app.auth.mode=local), so the refresh token
// can live in an HttpOnly cookie. All logic is in lib/localAuthProxy.ts; this file only adapts it
// to Next's route-handler signature. Not reachable in keycloak mode: core has no
// /api/auth/local surface there, so every action answers core's 404.

// Runtime config (CORE_INTERNAL_URL, NEXT_PUBLIC_API_BASE_URL) must be read per request, not
// frozen at build time.
export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ action: string }> };

export async function GET(request: Request, ctx: Ctx) {
  const { action } = await ctx.params;
  return handleLocalAuth(request, action);
}

export async function POST(request: Request, ctx: Ctx) {
  const { action } = await ctx.params;
  return handleLocalAuth(request, action);
}
