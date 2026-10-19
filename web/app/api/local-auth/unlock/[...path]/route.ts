import { handleUnlock } from "../../../../lib/localAuthUnlockProxy";

// Same-origin proxy to core's passkey / device-PIN / recovery-code sign-in
// (/api/auth/local/unlock/*). All logic is in lib/localAuthUnlockProxy.ts: this file only adapts
// it to Next's route-handler signature, like ../../[action]/route.ts does for password sign-in.
// Not reachable in keycloak mode: core has no /api/auth/local surface there, so every call
// answers core's 404.

export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ path: string[] }> };

async function handle(request: Request, ctx: Ctx) {
  const { path } = await ctx.params;
  return handleUnlock(request, path);
}

export const GET = handle;
export const POST = handle;
export const DELETE = handle;
