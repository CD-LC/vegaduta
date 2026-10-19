// Single-source shim (2026 console merge): the tenant console's copy in
// web/app/lib/auth.ts is the canonical implementation. Kept as a re-export so
// the merged platform-admin screens keep their original "../lib/auth"-style
// import paths untouched.
export * from "../../lib/auth";
