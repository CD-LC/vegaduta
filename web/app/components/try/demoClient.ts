// Client for core's unauthenticated demo-sandbox surface (/api/demo/**).
//
// Deliberately NOT built on app/lib/api.ts, and that is worth explaining
// because reusing it would look like the obvious move:
//   1. Every call in api.ts goes through auth.getToken(), which lazily
//      constructs a Keycloak instance. On /try there is no session and never
//      will be - the whole point - so that is a wasted round-trip on the one
//      page that has to feel instant.
//   2. api.ts routes failures into the global toast bus and a "we're sorry,
//      our service is temporarily unavailable" banner. That is exactly right
//      for the console and exactly wrong here: the sandbox's hard rule is
//      that a visitor never sees an error surface. This module therefore
//      resolves failures into typed results the UI renders as content, and
//      never throws.
//
// Nothing in here is duplicated logic - it is ~60 lines of fetch with a
// different error philosophy, which is the honest reason to keep it separate.
//
// The client is split by area under ./demo (http plumbing, session, chat, media, channels); this
// module is the stable import path and re-exports exactly the public API it always had.

export { DEMO_TOKEN_KEY, clearStoredToken, readStoredToken } from "./demo/demoHttp";
export * from "./demo/demoSession";
export * from "./demo/demoChat";
export * from "./demo/demoMedia";
export * from "./demo/demoChannels";
