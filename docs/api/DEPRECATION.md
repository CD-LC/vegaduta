# API deprecation policy

The REST API under `/api` is unversioned. Changes are additive by default: new endpoints, new optional parameters and
new response fields can appear in any release, and clients must ignore fields they do not know. Removing or
incompatibly changing a route follows this policy.

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
- A route is removed only after its sunset date, in a release whose changelog lists the removal under `### Removed`.
- A security fix may shorten the notice; the changelog then says why.

## What counts as breaking

Removing a route, a response field or an enum value; renaming anything; making an optional request field or parameter
required; narrowing accepted values; changing a field's type or meaning; changing the success status code. These go
through the policy above. When a change cannot be made compatibly at all, the replacement is a new route (or, for a
wholesale redesign, a new `/api/v2` prefix), and the old one is deprecated.

## For clients

- Log or alert on any response carrying `Deprecation`.
- Read `Sunset` to plan the move, and the operation's `x-replacement` in the OpenAPI document for where to go.
- Use the SDKs' deprecation markers to find call sites.
