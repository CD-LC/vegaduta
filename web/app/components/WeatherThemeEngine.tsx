"use client";

import { useEffect } from "react";
import { useTheme } from "./ThemeProvider";

// "By default the theme should be light during day - check the IP region,
// calculate the time, then display the theme, and colour should be based on
// the weather condition of the region." Mounted once inside ThemeProvider
// (see layout.js) - runs a single best-effort lookup on first load and
// never again this session (there's nothing to re-check mid-session; a
// tenant's IP-derived region and the day/night line don't change while
// they're using the console).
//
// Deliberately IP-based, not navigator.geolocation: browser geolocation
// needs an explicit permission prompt for what is, here, a purely cosmetic
// feature (which accent color looks nicest) - not worth interrupting a
// first-time visitor with a location permission dialog before they've even
// seen the product. IP-based location is coarse (city/region, not exact
// coordinates) and needs no permission at all, which is the right trade for
// this specific use.
//
// Two independent, best-effort network calls, each optional:
//   1. get.geojs.io/v1/ip/geo.json - free, no API key, HTTPS, and crucially
//      CORS-open. (Switched from ipapi.co 2026-07-28: ipapi.co started
//      CORS-blocking this origin, and although the catch below handled it,
//      the BROWSER still logged an uncaught network error on every single
//      page load - the app-wide console noise the full-app audit caught.
//      geojs returns latitude/longitude as STRINGS, hence the Number()
//      normalisation at the use site.) Gives latitude/longitude/timezone
//      for the visitor's IP.
//   2. open-meteo.com/v1/forecast - free, no API key - given those
//      coordinates, returns the current WMO weather code AND its own
//      is_day flag (computed from real sunrise/sunset at that location,
//      more accurate than a fixed "6am-7pm" rule of thumb would be).
//
// If either call fails (offline, ad-blocker, the free tier rate-limited),
// this silently does nothing further - ThemeProvider's existing
// prefers-color-scheme-based "system" resolution (already applied
// synchronously by the no-flash script) stands as the fallback, so a
// person never sees a broken or blank theme, just the previous behavior.
const GEO_URL = "https://get.geojs.io/v1/ip/geo.json";
const WEATHER_URL = (lat: number, lon: number) =>
  `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current_weather=true`;

// WMO weather codes (open-meteo's current_weather.weathercode) collapsed
// into the handful of categories this console's six accent palettes can
// actually represent, plus a coarse label for data-weather (a hook future
// visual work - e.g. the Dashboard ORBITAL reskin - can read from CSS
// without re-deriving it).
function classifyWeatherCode(code: number): { accent: string; label: string } | null {
  if (code === 0 || code === 1) return { accent: "amber", label: "clear" };
  if (code === 2 || code === 3) return { accent: "slate", label: "cloudy" };
  if (code === 45 || code === 48) return { accent: "slate", label: "fog" };
  if ([51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82].includes(code)) {
    return { accent: "sky", label: "rain" };
  }
  if ([71, 73, 75, 77, 85, 86].includes(code)) return { accent: "indigo", label: "snow" };
  if ([95, 96, 99].includes(code)) return { accent: "rose", label: "storm" };
  return null;
}

function localHourIsDay(timezone: string): boolean | null {
  try {
    const hourStr = new Intl.DateTimeFormat("en-US", { timeZone: timezone, hour: "numeric", hour12: false }).format(
      new Date()
    );
    const hour = parseInt(hourStr, 10) % 24;
    return hour >= 6 && hour < 19;
  } catch {
    return null; // unrecognized/invalid timezone string - let the caller fall back
  }
}

export default function WeatherThemeEngine(): null {
  const { setWeatherAccent, setWeatherResolvedMode } = useTheme();

  useEffect(() => {
    let cancelled = false;
    let idleHandle: number | undefined;
    let timeoutHandle: ReturnType<typeof setTimeout> | undefined;

    // AbortController threaded through both fetches so an in-flight request
    // can be killed cleanly the moment the user navigates away (e.g. the
    // full-page cross-origin jump to Keycloak on "Sign In"). Without this,
    // Chromium's own navigation-triggered abort of the request logs
    // "Failed to load resource: net::ERR_NAME_NOT_RESOLVED" to DevTools -
    // misleading (not a real DNS failure) but real console noise that trips
    // e2e/tests/visual.spec.ts's strict no-console-errors check on the
    // Keycloak login page. The try/catch below already swallowed the JS
    // promise rejection; it never could suppress that separate
    // browser-level resource-load log for a request killed out from under
    // it. Aborting the request ourselves, before navigation gets the chance
    // to kill it un-asked, avoids the browser log in the first place.
    const controller = new AbortController();

    // 'pagehide' catches the hard-navigation case cleanup can't: React's
    // cleanup runs on unmount, but a full-page navigation (this component
    // stays mounted the whole time, the tab is just leaving) may not
    // unmount in time to abort before Chromium tears down the request
    // itself. pagehide is the standard event for "the page is being
    // navigated away from" and fires reliably for that case.
    function abortForNavigation() {
      controller.abort();
    }
    window.addEventListener("pagehide", abortForNavigation);

    async function run() {
      let geo;
      try {
        const res = await fetch(GEO_URL, { signal: controller.signal });
        if (!res.ok) return;
        const raw = await res.json();
        // geojs serialises coordinates as strings ("12.9753") - normalise
        // here so the rest of this effect keeps its numeric contract.
        geo = raw && {
          latitude: Number(raw.latitude),
          longitude: Number(raw.longitude),
          timezone: raw.timezone,
        };
      } catch (err) {
        // offline, blocked, rate-limited, or aborted (navigation/unmount) -
        // no fallback needed, ThemeProvider already resolved a sensible
        // default. AbortError is the expected shape of an intentional
        // abort, not a failure - don't let it surface as a new error.
        if (err instanceof DOMException && err.name === "AbortError") return;
        return;
      }
      if (cancelled || !geo || !Number.isFinite(geo.latitude) || !Number.isFinite(geo.longitude)) return;

      // Day/night from the visitor's local time zone, applied immediately -
      // don't block this on the weather call below, which is a separate,
      // purely decorative enhancement.
      if (geo.timezone) {
        const isDay = localHourIsDay(geo.timezone);
        if (isDay !== null) setWeatherResolvedMode(isDay ? "light" : "dark");
      }

      try {
        const res = await fetch(WEATHER_URL(geo.latitude, geo.longitude), { signal: controller.signal });
        if (!res.ok) return;
        const data = await res.json();
        const current = data?.current_weather;
        if (cancelled || !current) return;

        // open-meteo's own is_day (real sunrise/sunset at these coordinates)
        // supersedes the timezone-hour estimate above once it arrives.
        if (typeof current.is_day === "number") {
          setWeatherResolvedMode(current.is_day === 1 ? "light" : "dark");
        }

        const classified = classifyWeatherCode(current.weathercode);
        if (classified) {
          setWeatherAccent(classified.accent);
          document.documentElement.setAttribute("data-weather", classified.label);
        }
      } catch (err) {
        // Weather call failed (or was aborted) - day/night from the
        // timezone above (if it succeeded) still stands; accent just stays
        // whatever it already was. AbortError is expected on navigation -
        // swallow it silently, same as above.
        if (err instanceof DOMException && err.name === "AbortError") return;
      }
    }

    // Deferred kickoff (performance-cwv audit, 2026-07-30): these two
    // fetches are purely cosmetic (accent color) and were firing the
    // instant this component mounted, competing for connections/bandwidth
    // with the actually-critical requests (JS chunks, first API calls)
    // during the page's critical rendering path - worse than usual for the
    // stated audience (Indian SMBs, often on slower/metered connections).
    // requestIdleCallback lets the browser run this once it has spare
    // capacity; the setTimeout fallback (Safari has no rIC) gives the same
    // "after the critical stuff" effect without needing the API.
    if (typeof window !== "undefined" && "requestIdleCallback" in window) {
      idleHandle = window.requestIdleCallback(
        () => {
          void run();
        },
        { timeout: 4000 }
      );
    } else {
      timeoutHandle = setTimeout(() => {
        void run();
      }, 2000);
    }
    return () => {
      cancelled = true;
      if (idleHandle !== undefined && "cancelIdleCallback" in window) {
        window.cancelIdleCallback(idleHandle);
      }
      if (timeoutHandle !== undefined) clearTimeout(timeoutHandle);
      window.removeEventListener("pagehide", abortForNavigation);
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return null;
}
