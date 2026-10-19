"use client";

import { createContext, useContext, useMemo, type ReactNode } from "react";
import type { LandingVariant } from "./geo-config";

export type { LandingVariant };

interface GeoValue {
  country: string | null;
  variant: LandingVariant;
}

const DEFAULT_GEO: GeoValue = { country: null, variant: "global" };

const GeoContext = createContext<GeoValue>(DEFAULT_GEO);

/**
 * `country`/`variant` are resolved once, server-side, in RootLayout
 * (web/app/layout.tsx reads Cloudflare's CF-IPCountry header via
 * next/headers) and handed down here as plain data — no client-side geo-IP
 * call, no next/headers import anywhere in the client bundle.
 *
 * This mirrors I18nProvider's existing pattern (lib/i18n.tsx) for exactly
 * the same underlying problem: AuthProvider (components/AuthProvider.tsx)
 * hardcodes `<GenesisExperience />` for an unauthenticated visitor rather
 * than rendering the `children` RootLayout passes it, so a plain prop
 * threaded through `children` never reaches the landing page. Wrapping the
 * whole tree in a context provider fed by a server-computed value sidesteps
 * that — any descendant (GenesisExperience, DemoLanding, ...) can read it
 * via useGeo() regardless of how AuthProvider decides what to render.
 */
export function GeoProvider({
  country,
  variant,
  children,
}: {
  country: string | null;
  variant: LandingVariant;
  children: ReactNode;
}) {
  const value = useMemo(() => ({ country, variant }), [country, variant]);
  return <GeoContext.Provider value={value}>{children}</GeoContext.Provider>;
}

/**
 * Falls back to the DEFAULT_GEO ("global") when read outside a GeoProvider
 * instead of throwing — geo is a soft marketing-copy personalization
 * signal, never something worth crashing a render over (unlike
 * useTranslations()'s hard throw, which guards a real content lookup).
 */
export function useGeo(): GeoValue {
  return useContext(GeoContext);
}
