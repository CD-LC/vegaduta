import { describe, expect, it } from "vitest";
import { z } from "zod";

// The production CSP (lib/csp.ts) has no 'unsafe-eval'. zod 4 probes
// `new Function("")` when it builds an object schema, and a strict CSP reports
// that blocked call to /api/csp-report even though zod catches the throw: one
// false "eval" violation per page view. schemas.ts therefore switches zod to
// jitless mode before it builds any schema.
import "./schemas";

describe("zod and the enforced CSP", () => {
  it("is configured jitless so it never probes eval", () => {
    expect(z.core.globalConfig.jitless).toBe(true);
  });

  it("still parses with jitless on", async () => {
    const { MeResponseSchema } = await import("./schemas");
    const me = MeResponseSchema.parse({ subject: "s", roles: [], permissions: [], extra: 1 });
    expect(me.subject).toBe("s");
    expect((me as Record<string, unknown>).extra).toBe(1);
  });
});
