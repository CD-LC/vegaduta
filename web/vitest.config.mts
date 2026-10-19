import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Node 25+ ships a built-in global `localStorage`/`sessionStorage` (Web
// Storage). Without --localstorage-file it is undefined, and it shadows the
// jsdom window storage, so `localStorage.setItem` throws in tests. Turn Node's
// copy off in the test workers so jsdom's is used. Guarded so older Node
// versions that don't know the flag don't refuse to start the worker.
const nodeFlags = process.allowedNodeEnvironmentFlags;
const workerExecArgv =
  nodeFlags.has("--experimental-webstorage") || nodeFlags.has("--no-experimental-webstorage")
    ? ["--no-experimental-webstorage"]
    : [];

// Vitest + React Testing Library for the tenant console.
// Run: npm install (adds the devDeps below) then `npx vitest run`.
export default defineConfig({
  plugins: [react()],
  // lib/edge/tier1.ts loads WebLLM from the vendored same-origin copy
  // (/vendored/web-llm.js) at runtime; in tests that URL resolves to the npm
  // package so vi.doMock("@mlc-ai/web-llm") applies.
  resolve: {
    alias: [{ find: /^\/vendored\/web-llm\.js$/, replacement: "@mlc-ai/web-llm" }],
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    execArgv: workerExecArgv,
    // Only our hand-written unit tests; never crawl node_modules or .next.
    include: ["app/**/*.test.{ts,tsx}"],
    exclude: ["node_modules", ".next"],
    // 20s instead of Vitest's 5s default. Suites that dynamically import the
    // heavy edge/local-model modules (lib/edge tier1*, routingState, webmcp,
    // lib/localcode/ask, AskAiHelper) spend most of their first test in module
    // transform, which under a full parallel run regularly crossed 5s and
    // failed as a timeout, not an assertion. A genuinely hung test still fails.
    // Do not pass a smaller per-test timeout (it overrides this one).
    testTimeout: 20_000,
    hookTimeout: 20_000,
    // keycloak-js is a real "dependencies" package (not a devDep), so Vite's
    // default SSR dep handling treats it as externalized and resolves it via
    // Node's own require() rather than through Vitest's transformed module
    // graph - which means vi.mock("keycloak-js", ...) in auth.test.ts never
    // intercepts it, and the REAL Keycloak client runs instead of the mock.
    // Forcing it inline makes Vite process/transform it like any other
    // module, so the mock factory applies. (Confirmed failure mode: web-admin's
    // equivalent test hung on a live login-required redirect and getToken()
    // fell back to null against the real, uninitialized client.)
    server: {
      deps: {
        inline: ["keycloak-js"],
      },
    },
    // Coverage (opt-in via `npm run test:coverage`; `npm test` stays
    // uninstrumented). No thresholds on purpose: the number is for review
    // and trend, not a gate - a gate would be set once the baseline is
    // known. lcov feeds editors/CI uploaders; text-summary is the human
    // one-screen readout.
    coverage: {
      provider: "v8",
      reporter: ["text-summary", "lcov"],
      include: ["app/**/*.{ts,tsx}"],
      exclude: ["app/**/*.test.{ts,tsx}", "app/**/*.d.ts"],
    },
  },
});
