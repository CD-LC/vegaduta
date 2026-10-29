import type { Page, TestInfo } from "@playwright/test";
import { API_URL } from "../helpers/prod-env";

/**
 * LOCAL STAGE FIX (agent B resume, 2026-10-29) - spec-local, not a shared helper.
 *
 * This stack's web container serves window.__ENV.NEXT_PUBLIC_API_BASE_URL=
 * http://localhost:8280 (runtime env via the __public-env head script), so the
 * console calls localhost:8280 directly instead of api.<domain>. Two things
 * break under the domain-routed test harness:
 *   1. helpers/prod-api.ts only captures the bearer token from requests to
 *      api.<domain> - no token is ever captured and every live spec dies at
 *      getBearerToken / freshToken / the control-sweep beforeEach;
 *   2. the document's Content-Security-Policy connect-src is built from the
 *      same value, so repointing __ENV alone is blocked by CSP.
 *
 * Fix, applied per spec (the stack may not be rebuilt mid-run):
 *   - test.use({ bypassCSP: true }) so the served CSP does not block api.* calls;
 *   - fixStageApiBase(page) BEFORE the first navigation: the app's head script
 *     assigns window.__ENV=Object.freeze({...}), so a setter that re-freezes the
 *     assigned object with the API base overridden repoints every apiFetch at
 *     API_URL - the shape this deployment was built for (core CORS already
 *     allows the app origin; nginx routes api.<domain> to core).
 */
export async function fixStageApiBase(page: Page): Promise<void> {
  await page.addInitScript((apiBase: string) => {
    Object.defineProperty(window, "__ENV", {
      configurable: true,
      enumerable: true,
      get: () => ({ NEXT_PUBLIC_API_BASE_URL: apiBase }),
      set(v: Record<string, string>) {
        Object.defineProperty(window, "__ENV", {
          value: Object.freeze({ ...(v ?? {}), NEXT_PUBLIC_API_BASE_URL: apiBase }),
          writable: true,
          configurable: true,
          enumerable: true,
        });
      },
    });
  }, API_URL);
}

/**
 * LOCAL STAGE FIX 2: helpers/prod-env.ts in this checkout does not export
 * skipIfMockLlm / LLM_IS_MOCK, but agent-workbench-behavior-live,
 * agent-reasoning-depth-streaming-live and llm-response-evaluation-live all
 * import them (TypeError at call site - every behaviour test dies). The
 * intended semantics: when the tenant's model is a deterministic mock, output
 * assertions cannot be judged, so skipIfMockLlm skips. This stack runs a real
 * (if small) Ollama model, so the default is false; E2E_LLM_IS_MOCK=1 forces
 * the mock path for parity with a mock-backed stack.
 */
export const LLM_IS_MOCK = process.env.E2E_LLM_IS_MOCK === "1";

export function skipIfMockLlm(testInfo: TestInfo, what: string): void {
  testInfo.skip(
    LLM_IS_MOCK,
    `the configured model is a deterministic mock - ${what} cannot be asserted on generated output`
  );
}
