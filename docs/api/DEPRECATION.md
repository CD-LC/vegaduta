# API deprecation policy

The REST API under `/api` is unversioned (there is no `/api/v1` prefix). Changes are additive by default: new
endpoints, new optional parameters and new response fields can appear in any release, and clients must ignore
fields they do not know. Removing or incompatibly changing a route follows this policy. Versions before 1.0.0 can
change between releases; what 1.x promises is in [Stability from 1.0.0](#stability-from-100).

## How a route is deprecated

1. The handler (or its whole controller) is marked with `@ApiDeprecated(since, sunset, replacement, reason)`
   (`core/security/.../http/ApiDeprecated.java`).
2. From that release on, every response of the route carries:

   | Header | Standard | Example |
   |---|---|---|
   | `Deprecation` | RFC 9745 | `Deprecation: @1790812800` (the `since` date) |
   | `Sunset` | RFC 8594 | `Sunset: Thu, 01 Apr 2027 00:00:00 GMT` (when a sunset date is set) |
   | `Link` | RFC 8288 | `Link: <https://.../DEPRECATION.md>; rel="deprecation"; type="text/html"` |

   The link target is `app.api.deprecation-policy-url` ([configuration](../configuration.md#api-conventions)).
3. The OpenAPI document marks the operation `deprecated: true`, with `x-sunset`, `x-replacement` and a sentence in its
   description. Generated SDKs carry the flag through.
4. The change is listed under `### Deprecated` in [CHANGELOG.md](../../CHANGELOG.md).

## Notice period

- The sunset date is at least **180 days** after the `since` date. `ApiDeprecationPolicyTest` fails the build for a
  shorter notice or a malformed date.
- A route is removed only after its sunset date, in a release whose changelog lists the removal under `### Removed`. From 1.0.0 the removal also waits for the next major release (see below).
- A security fix may shorten the notice; the changelog then says why.

## What counts as breaking

Removing a route, a response field or an enum value; renaming anything; making an optional request field or parameter
required; narrowing accepted values; changing a field's type or meaning; changing the success status code. These go
through the policy above. When a change cannot be made compatibly at all, the replacement is a new route (or, for a
wholesale redesign, a new `/api/v2` prefix), and the old one is deprecated.

## Stability from 1.0.0

This section takes effect with the 1.0.0 release. Until then the rules above apply and the API may change between
0.x releases. It applies [semver.org](https://semver.org/spec/v2.0.0.html) to the platform:
one version number for the core, console, images, charts and generated clients.

**What is covered.** Every operation in [`openapi.json`](openapi.json) that is not preview: its path, verb,
`operationId`, required inputs, success body, enums, types and auth scheme. `scripts/ci/openapi-breaking.mjs`
checks these on every pull request ([API documentation](README.md)); an allowlist entry needs a reason and a
changelog line. Also covered, by review rather than by that gate: the exact 2xx code, error bodies beyond the shared
`Error` schema, permission requirements (`x-permissions`), header and webhook formats (the signed envelope and
`X-VegaDuta-Signature`), SSE streams ([`asyncapi.json`](asyncapi.json)), and the paging and idempotency conventions.
A change to any of these that breaks a documented client counts as breaking. The generated clients `sdk/typescript`
and `sdk/python` follow the same rules because they are regenerated from the spec.

**What is not covered.** Preview areas (listed in the 1.0.0 release notes and under "Early / niche" in the
[repository structure](../repository-structure.md); the spec does not carry a stability marker yet), the Java and
Python Agent SDKs (they version on their own tags and stay 0.x until announced), the console's routes and look,
metrics and log text, and undocumented properties. Preview items may change in any release, but the changelog still
lists the change.

**Which release may change what.**

| Release | May contain |
|---|---|
| Patch `1.y.Z` | bug and security fixes; nothing a covered surface would notice |
| Minor `1.Y.0` | additive changes (endpoints, optional parameters, response fields) and deprecations |
| Major `X.0.0` | the only release that removes or incompatibly changes a covered surface |

**Deprecation in 1.x.**
- A deprecation is announced in a minor release, in the way described above (headers, `deprecated: true` in the
  spec, `### Deprecated` in the changelog). Configuration keys and Helm values log a startup warning.
- A deprecated item keeps working for the rest of the major line and for at least 180 days after the announcement.
  It is removed only in the next major release, which lists it under `### Removed`. This replaces "after its sunset
  date, in any release" for the 1.x line.
- A renamed key or variable keeps the old name as an alias for that period.
- A security fix may shorten the period; the changelog then says why.
- A wholesale redesign ships as `/api/v2` next to `/api` for at least 180 days.

## For clients

- Log or alert on any response carrying `Deprecation`.
- Read `Sunset` to plan the move, and the operation's `x-replacement` in the OpenAPI document for where to go.
- Use the SDKs' deprecation markers to find call sites.
