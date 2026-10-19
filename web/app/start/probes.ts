// Quiet, read-only probes for the /start goal cards.
//
// Why not just api.xxx(): these are background look-ups the person never asked
// for, and several are admin-only. The default wrapper raises a global error
// toast on a 403, which is the normal answer for a non-admin - and a welcome
// page that greets a new member with "You don't have permission to do that" is
// the opposite of the point. The two helpers used here are the same ones every
// domain file in lib/api/ is built from; a 403/404 simply reads as "could not
// check" in signals.ts. Reads that are already quiet or toast-free for
// everyone (tenant settings, provider availability, the research status, the
// tutor probe) are taken from `api` unchanged - see StartFlow.tsx.

import { apiFetch, toJsonQuietOn } from "../lib/api/core";

const QUIET = [401, 403, 404, 503];

/** MCP servers registered in this workspace. Rejects (quietly) for a member without access. */
export function probeMcpServers(): Promise<Array<{ name?: string; enabled?: boolean }>> {
  return apiFetch("/api/mcp/registry").then(toJsonQuietOn(QUIET));
}

/** E2B credential status: null (204) when none is active. Rejects (quietly) for a member without access. */
export function probeE2bCredential(): Promise<unknown> {
  return apiFetch("/api/sdlc/credential/status").then((res) => (res.status === 204 ? null : toJsonQuietOn(QUIET)(res)));
}
