"use client";

// White-label / brand derivations for the rail. Lifted out of AppShell.tsx unchanged.

import { useAppChrome } from "../AppChromeProvider";
import { initialsOf } from "./initialsOf";

export function useShellBrand() {
  const { tenantSettings } = useAppChrome();
  // White-label entitlement (UNLIMITED-tier only, see TenantSettingsResponse.
  // whiteLabel / features/page.tsx's own read of the same field). Runtime,
  // per-tenant - never a build-time flag - since it rides the same
  // best-effort tenantSettings fetch above. When true, this tenant has paid
  // to present the product as their own: the rail's brand mark/wordmark
  // below are suppressed (generic org-initials mark instead of our winged
  // emblem, org name instead of "VegaDūta") and the brand-identity showcase
  // modal - which is inherently a VegaDūta marketing artifact, see its own
  // module comment - is no longer reachable from here. Nothing else in the
  // shell (footer copyright, theme, nav labels) mentions our brand name, so
  // no further gating is needed in this file.
  const isWhiteLabel = !!tenantSettings?.whiteLabel;

  // The tenant's own brand mark for the rail (2026-09-11, direct instruction:
  // the white-label logo "should reflect in dashboard also"). `brandLogoUrl`
  // is already on the tenantSettings this shell fetches - the same field
  // /admin/branding reads - so this costs no extra request.
  //
  // Only consulted in the white-label branch: on a normal tenant the rail
  // shows the winged emblem, always, and a stray brand_logo_url must never
  // displace it.
  const brandLogoUrl = isWhiteLabel ? tenantSettings?.brandLogoUrl || null : null;

  // brandDisplayName is what the admin explicitly typed on /admin/branding and
  // what the public share page already renders; organizationName is the
  // account's own name and only the fallback for a tenant that never set one.
  const brandWordmark = tenantSettings?.brandDisplayName || tenantSettings?.organizationName || "";

  // Initials of whatever name is actually shown as the wordmark - a tenant
  // with a brand display name but no organizationName used to score "?" and
  // render an empty initials tile even though they DO have a brand name.
  const orgInitials = initialsOf(brandWordmark);

  // Empty-brand fallback (2026-09-25, direct feedback: "brand logo should get
  // displayed, if there is no image in branding"). A white-label tenant that
  // has provided NOTHING - no uploaded/hosted logo AND no brand/org name -
  // has no brand to show: without this check the rail rendered an empty
  // tinted tile plus a blank wordmark. They get the VegaDūta mark + animated
  // brand showcase instead, exactly like a non-white-label tenant; the
  // moment they upload a logo or name their own brand takes over.
  //
  // 2026-10-09 (direct instruction: "only when user sets the brand it should use
  // that icon and name ... without branding details it should use the VegaDuta
  // logo and name"): "has a brand" now means the tenant SET one on
  // /admin/branding - an uploaded logo or a brand display name. organizationName
  // no longer counts: every account has one from signup, and every signup gets
  // a white-label-entitled trial plan, so every new customer saw an initials
  // tile instead of the VegaDuta mark.
  const tenantSetBranding = !!brandLogoUrl || !!tenantSettings?.brandDisplayName?.trim();
  const tenantBrandEmpty = isWhiteLabel && !tenantSetBranding;
  const showTenantBrand = isWhiteLabel && !tenantBrandEmpty;
  return { isWhiteLabel, brandLogoUrl, brandWordmark, orgInitials, tenantBrandEmpty, showTenantBrand };
}
