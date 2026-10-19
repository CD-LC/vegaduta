# TypeScript strictness ladder (web + web-admin)

Last measured: 2026-09-29 (second web pass the same day, see below). Both apps still run with `"strict": false`. This page tracks
which strict-family and lint-grade flags are enabled one at a time, what each remaining
flag would cost, and the order to take them in.

## How the numbers were measured

Each flag was added on the command line to the app's own tsconfig, one flag at a time:

```sh
npx tsc --noEmit -p tsconfig.json --incremental false --<flag>
```

The count is the number of `error TS` lines. `exactOptionalPropertyTypes` and
`noUncheckedIndexedAccess` do nothing without `strictNullChecks` (tsc either rejects them
or reports 0), so they were measured together with it. Their cost is the combined total
minus the `strictNullChecks` total.

## Measurements (before this pass)

| Flag | web errors | web-admin errors | Status |
|---|---|---|---|
| `noImplicitAny` | on (0) | 246 | web: on since 2026-08-17. web-admin: off |
| `strict` (all of the family) | 1179 | 463 | off |
| `strictNullChecks` | 971 | 218 | off (the real migration) |
| `useUnknownInCatchVariables` | 204 | 37 | **enabled in both** (web: second pass, via `app/lib/errors.ts`) |
| `noImplicitReturns` | 142 | 9 | **enabled in both** |
| `noImplicitOverride` | 0 | 0 | **enabled in both** (web-admin already had it) |
| `noFallthroughCasesInSwitch` | 0 | 0 | **enabled in both** (web-admin already had it) |
| `strictFunctionTypes` | 3 | 0 | **enabled in both** |
| `strictBindCallApply` | 0 | 0 | **enabled in both** |
| `noImplicitThis` | 0 | 0 | **enabled in both** |
| `alwaysStrict` | 0 | 0 | **enabled in both** |
| `noUnusedParameters` | 10 | 0 | **enabled in both** |
| `noUnusedLocals` | 65 | 2 | **enabled in both** (web: second pass) |
| `exactOptionalPropertyTypes` | +235 on top of strictNullChecks (1206 total) | +30 (248 total) | off, needs strictNullChecks first |
| `noUncheckedIndexedAccess` | +1223 on top of strictNullChecks (2194 total) | +34 (252 total) | off, needs strictNullChecks first |
| `noPropertyAccessFromIndexSignature` | 723 | 50 | off: style-only, low safety value (see below) |

No `noFallthroughCasesInSwitch` hits in either app, so there were no fallthroughs to judge.

## What was fixed to land the flags

All the fixes below leave runtime behaviour unchanged.

- **noImplicitReturns** (web 142, web-admin 9). Nearly all of these were a bare `return;`
  inside a `useEffect` that also returns a cleanup function on another path. They became
  `return undefined;`, which returns the same value, so the effect behaves the same. Two
  sites in web were written by hand:
  - `app/components/security/FraudChallengeModal.tsx`: added a trailing `return undefined;` to the effect.
  - `app/lib/edge/deviceTools.ts` `runReadDevice`: changed the final `refuse(...)` to
    `return refuse(...)`. `refuse` returns `never` (it always throws), so this changes nothing at runtime.
- **useUnknownInCatchVariables** (web-admin 37). Every hit was `err.message` (plus one
  `err.status`) in a page's `catch`. These became `(err as Error).message` and
  `(err as { status?: unknown }).status`. That is the same property read at runtime.
  web was not converted with casts. At 204 sites, a cast would be noise that
  hides the problem, so web uses a helper instead (see the second web pass below).
- **strictFunctionTypes** (web 3):
  - `ChannelsPanel.tsx`: gave `usePagination` an explicit element type. The source state is `any`, which made the inferred `T` `unknown`.
  - `HeroIllustration.tsx`: the anime.js function value now takes anime's `target?: Target` parameter and narrows it with a cast inside the function.
- **noUnusedParameters** (web 10). Unused callback parameters were dropped, or renamed with
  a `_` prefix where a later parameter is still used. `FraudChallengeModal` no longer
  destructures `nonce` or `hcaptchaKey`. Both are still props, and a comment says to
  re-add them when the commented-out HCaptcha block comes back.
- **noUnusedLocals** (web-admin 2): one unused import, and one unused state value (`[, setQuota]`).

## Second web pass (2026-09-29): `useUnknownInCatchVariables` + `noUnusedLocals`

- **Helper.** `app/lib/errors.ts` (tested in `errors.test.ts`):
  - `errorMessage(e, fallback?)`. With a fallback it returns exactly what the old
    `e.message || fallback` did for every input that idiom handled: an Error or any
    object with a string `message` gives the message; an empty or missing message gives
    the fallback. It also no longer throws when `e` is null. Without a fallback it
    returns the message (even if empty), else the thrown string, else `String(e)`
    ("Unknown error" for null/undefined).
  - `errorField` / `errorName` / `errorStatus` read the extra fields that
    `lib/api/core.ts` `toJson` attaches (`status`, `errors`, ...) and DOMException
    names. There is no API error class to narrow with `instanceof`: toJson throws a
    plain `Error` with expando fields. lib/api was left untouched on purpose.
  - The private copy in `admin/guardrails/page.tsx` now imports the shared helper.
    `lib/requestLifecycle.ts` and `LlmSpendSection.tsx` keep their local readers,
    because their no-fallback semantics differ.
- **Call sites.** 184 `.message` reads became `errorMessage(err, <same fallback>)`,
  so every user-visible fallback string is unchanged. The 8 bare `err.message` reads
  (no fallback) now show a string instead of `undefined` when a non-Error is thrown.
  Nothing else changes. `e.message || e` interpolations became `errorMessage(e)`.
  Other sites were narrowed without a cast: mic `err.name` goes through `errorName`,
  the voice turn `err.status` through `errorStatus`, and workflow
  `err.errors` through a local `validationErrorsOf` (`Array.isArray`).
  `AgentPlayground` tags `streamedChars` with `Object.assign`. The four
  `fetchWithRetry` copies type `lastError` as `unknown` and rethrow it unchanged.
- **noUnusedLocals (web 65).** Dead imports were removed, mostly unused `toast` and
  `auth` imports in `lib/api/*`. Other removals: unused locals, one unused state value
  (`[, setX]`), and two e2e bindings (the awaited call is kept). Dead code was deleted
  too: `HowHiringWorksSection` in DemoLanding, which was not rendered (its `landing`
  i18n keys remain), `kafkaColor` in DashboardHome, and the unused `next/dynamic`
  DashboardHome loader plus `useAuth()` read in `app/page.tsx` (it only redirects
  to /build). `McpServerGrantCreated` is now used to type the create/rotate results.
- **Real bug found, not fixed (behaviour-neutral pass).** In `lib/fraudInterceptor.ts`,
  `apiFetchWithFraudDetection` built a `retryOptions` object carrying the
  `X-Challenge-Pass` token and then never used it. The retry re-sends the original
  options, so a solved fraud challenge is never proven to the server. The dead object
  was replaced with a KNOWN BUG comment that describes the fix.

## `any` debt

`@typescript-eslint/no-explicit-any` is already set to **warn** in both
`eslint.config.mjs` files. Current counts:

| App | `no-explicit-any` warnings |
|---|---|
| web | 1350 |
| web-admin | 249 |

This pass did not add a rule that blocks new `any` only in new files. ESLint has no
native notion of a new file, so it would need a CI diff-based gate, for example linting
changed files with `--rule '{"@typescript-eslint/no-explicit-any":"error"}'`. That belongs
in `.github/`, which is outside web/web-admin.

## Next steps, in order

1. ~~web `noUnusedLocals`~~ and ~~web `useUnknownInCatchVariables`~~: done (second web
   pass above). Also fix the fraud-interceptor retry bug that pass found. Optionally,
   move web-admin's 37 `(err as Error).message` casts onto an equivalent helper.
2. **web-admin `noImplicitAny`** (246), then **web-admin `strictNullChecks`** (218).
   web-admin is small enough to reach full `strict` first and serve as the reference.
3. **web `strictNullChecks`** (971). This is where latent null bugs surface. Burn it down
   directory by directory, with a per-directory tsconfig or a
   `// @ts-expect-error`-free burn-down list.
4. After `strictNullChecks`, consider **`exactOptionalPropertyTypes`** (+235 web / +30
   web-admin). **`noUncheckedIndexedAccess`** (+1223 web) should wait. Landing it now would
   mean `!` assertions en masse.
5. **`noPropertyAccessFromIndexSignature`**: this is not recommended. It only forces
   `obj["x"]` instead of `obj.x` on index signatures, which is churn with no bug-catching value.

## Verification of this pass

| Check | web | web-admin |
|---|---|---|
| `npx tsc --noEmit` | 0 errors | 0 errors |
| `npx eslint . --quiet` | 0 errors | 0 errors |
| `npx vitest run` | 2104 / 2104 passed (126 files) | 120 / 120 passed (19 files) |

In web-admin, one test failed once during a run that overlapped the web suite. It passed
cleanly when re-run on its own, so it looks like a load-induced flake.
