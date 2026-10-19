// Pure, isomorphic geo config — no next/headers import here, deliberately.
// resolveLandingVariant() is called from BOTH a server component (RootLayout,
// which reads Cloudflare's CF-IPCountry request header) and, via the
// LandingVariant type, from client components (GeoProvider/useGeo in
// ./geo.tsx, DemoLanding.tsx and friends). Keeping this file free of any
// server-only import means it can be safely imported from either side of
// the client/server boundary without dragging next/headers into the client
// bundle (that import is only ever made in layout.tsx).
export type LandingVariant = "india" | "global";

/**
 * Maps an ISO-3166-1 alpha-2 country code (as reported by Cloudflare's
 * `CF-IPCountry` header — see web/app/layout.tsx) to which flavor of the
 * public landing page a visitor should see. "IN" gets the existing
 * India-focused experience (Sarvam speech AI, UPI payments, Hindi/Indian-
 * language framing); everyone else gets the neutral "global" variant —
 * including a missing header (local dev, or any request that never passed
 * through Cloudflare) and Cloudflare's own "XX" sentinel (used when it
 * can't resolve a country, e.g. some anonymizer traffic). Never throws,
 * never assumes the header is present — this is a soft personalization
 * signal, not a gate on anything.
 */
export function resolveLandingVariant(countryCode?: string | null): LandingVariant {
  return countryCode?.toUpperCase() === "IN" ? "india" : "global";
}
