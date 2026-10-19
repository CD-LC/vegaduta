"use client";

import { useEffect, useState } from "react";
import { useGeo } from "./geo";

// Client-side region inference for the landing page's personalization (show
// India-first proof — UPI, 22 Indian languages, ₹ pricing — to visitors in
// India, global framing to everyone else). This is a soft hint for MARKETING
// copy only, never a gate on functionality, so a wrong guess just shows the
// other region's (equally true) framing.
//
// Geo audit 2026-07-31: the server-resolved country (Cloudflare's
// CF-IPCountry header, read once in RootLayout and handed down via
// GeoProvider — see lib/geo.tsx) is now the PRIMARY signal, so "India
// framing" means "visitor is IN India", matching the geo-targeted
// generateMetadata() in layout.tsx. The original browser heuristic (IANA
// timezone + navigator language tags) survives only as the fallback for
// requests that never passed through Cloudflare (local dev, direct hits) —
// where useGeo() reports country: null. Without this, a Hindi-locale
// browser outside India would still get the India positioning the owner
// wants shown only to visitors in India.
const INDIAN_LANG_CODES = [
  "hi", "bn", "mr", "te", "ta", "gu", "ur", "kn", "ml", "or", "pa", "as",
  "mai", "sat", "ks", "ne", "sd", "doi", "kok", "mni", "brx", "sa",
];

export type Region = "IN" | "INTL";

export function useRegion(): { region: Region; isIndia: boolean; ready: boolean } {
  // Server-resolved geo, identical on the server render and every client
  // render (it's plain context data computed in RootLayout), so deriving from
  // it synchronously cannot cause a hydration mismatch. "XX" is Cloudflare's
  // unknown-country sentinel — treated the same as a missing header.
  const { country } = useGeo();
  const countryCode = country?.toUpperCase();
  const geoKnown = !!countryCode && countryCode !== "XX";

  // Heuristic fallback path only. Default INTL so server render + first
  // client render match (no hydration mismatch); the real value is set after
  // mount.
  const [heuristicRegion, setHeuristicRegion] = useState<Region>("INTL");
  const [heuristicReady, setHeuristicReady] = useState(false);

  useEffect(() => {
    if (geoKnown) return; // Cloudflare already told us — skip the guesswork.
    let india = false;
    try {
      const tz = (Intl.DateTimeFormat().resolvedOptions().timeZone || "").toLowerCase();
      const langs = [navigator.language, ...(navigator.languages || [])]
        .filter(Boolean)
        .map((l) => l.toLowerCase());
      india =
        /kolkata|calcutta/.test(tz) ||
        langs.some((l) => l.endsWith("-in")) ||
        langs.some((l) => INDIAN_LANG_CODES.includes(l.split("-")[0]));
    } catch {
      /* Intl / navigator unavailable — stay INTL */
    }
    setHeuristicRegion(india ? "IN" : "INTL");
    setHeuristicReady(true);
  }, [geoKnown]);

  const region: Region = geoKnown
    ? countryCode === "IN"
      ? "IN"
      : "INTL"
    : heuristicRegion;

  return { region, isIndia: region === "IN", ready: geoKnown || heuristicReady };
}
