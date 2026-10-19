// Single-source shim (2026 console merge): web/app/lib/logger.ts is the
// canonical pino logger (same shape web-admin's copy had, differing only in
// the pino "service" field). Re-exported, including the default export which
// `export *` alone does not forward.
export * from "../../lib/logger";
export { default } from "../../lib/logger";
