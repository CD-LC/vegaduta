// The NETWORKED half of the local tool surface.
//
// localTools.ts (phase 1) proved four tools could run with `network: "none"`:
// pure computation, no account, no spend, nothing leaving the page. This module
// is the opposite case handled honestly — three tools that DO reach the
// internet, each naming the exact third party it talks to, in its own
// description, before it talks to it.
//
// Registering these does NOT weaken the phase-1 properties; it makes the
// `network` field on LocalTool start carrying information:
//
//   1. NO SPEND — every host below is a public, key-less, anonymous endpoint.
//      None of them is a VegaDūta origin, so nothing here can reach
//      /api/edge/tools/execute, the entitlement system, or any billable path.
//      This module's only import is `import type`, which TypeScript erases, so
//      there is no import path from here to toolBridge, tier1 or the API base.
//      localToolsWeb.test.ts asserts both (the URL allowlist, and that every
//      request this module makes goes through one function).
//   2. NO DOWNLOAD — nothing here touches tier1.ts or downloadManager.ts. The
//      structural weights guard (getTier1RunModel returning null unless the
//      weights are already cached) is untouched and unreferenced.
//   3. NO SILENT NETWORK — the inverse of phase 1's promise. Each tool declares
//      `network: { hosts: [...] }` with the exact hosts it contacts, and each
//      description says in plain words that using it sends the person's words
//      to that named third party. Descriptions are what the model repeats, so
//      that sentence is how someone finds out BEFORE the request, not after.
//   4. HONEST DEGRADATION — offline, blocked, rate-limited, slow and malformed
//      are all normal here, not exceptional. Every one of them produces a
//      WebLookupError carrying a `kind` and a full sentence the model can relay
//      verbatim. No handler in this file ever lets a raw TypeError escape.
//
// WHY ONLY THREE, AND WHY THESE
// -----------------------------
// A page cannot fetch an arbitrary third-party URL. Same-origin policy means
// only hosts that choose to send `Access-Control-Allow-Origin` are reachable
// from browser JavaScript at all, so a general "browse the web" tool is not
// buildable on this side of the wire and is not attempted here. Each host below
// was checked by hand on 2026-09-12 with a cross-origin request
// (`curl -H "Origin: https://app.example.com"`), and shipped only because it
// answered 200 with `access-control-allow-origin: *`:
//
//   en.wikipedia.org               ACAO: *   (REST summary; and the action API
//                                             ONLY when the request carries
//                                             `origin=*` — the same request
//                                             without that parameter came back
//                                             200 with NO ACAO header at all,
//                                             which a browser would fail. That
//                                             parameter is MediaWiki's
//                                             anonymous-CORS marker, and it also
//                                             makes MediaWiki ignore any session
//                                             cookie, which matches the
//                                             credentials:"omit" below.)
//   geocoding-api.open-meteo.com   ACAO: *
//   api.open-meteo.com             ACAO: *
//   api.frankfurter.dev            ACAO: *
//
// One candidate was DROPPED on the same evidence: api.frankfurter.app answers
// 301 to a text/html page rather than serving JSON, so a tool pointed at it
// would fail for everyone. Only api.frankfurter.dev ships.
//
// THE TIMEOUT PROBLEM THIS MODULE CANNOT FIX ALONE  (read before shipping)
// -----------------------------------------------------------------------
// runLocalTool in localTools.ts races every handler against
// LOCAL_TOOL_TIMEOUT_MS, which is 2_000 (read 2026-09-12). That constant's own
// comment calls itself "three orders of magnitude above what these four
// actually take" — it was sized for pure computation, and it is NOT enough for
// a network round trip. Measured from this repo's machine on 2026-09-12
// (ten `curl` runs, full connect+TLS each, nothing warmed):
//
//   en.wikipedia.org summary   0.31 – 0.46 s
//   api.frankfurter.dev        0.28 – 0.62 s
//   geocoding-api.open-meteo   0.99 – 1.08 s
//   api.open-meteo.com         1.12 – 1.16 s
//
// local_weather needs the last two in sequence — ~2.1 s measured — so at the
// present ceiling runLocalTool abandons it before it can answer. That is still
// a typed, non-silent failure ("did not finish within 2000ms"), so nothing
// breaks unsafely; the tool is simply unusable. NETWORK_TOOL_BUDGET_MS below is
// therefore set from the measurements, not from the current ceiling, and
// raising that ceiling is a REQUIRED companion change to the registry merge —
// see needsOtherFiles. Nothing in this file can raise it: localTools.ts belongs
// to another track.

//
// localToolsWebHttp.ts holds the budgets, failure vocabulary and the single
// requestJson chokepoint; this file holds the three sources and the tools.
import type { LocalTool } from "./localTools";
import {
  fail,
  type Deadline,
  startDeadline,
  isObject,
  requestJson,
  requireQuery,
  trimProse,
  asNumber,
  asString,
  sourceLine,
  NETWORK_TOOL_BUDGET_MS,
  WebLookupError,
} from "./localToolsWebHttp";
export {
  ALLOWED_HOSTS,
  MAX_RESPONSE_BYTES,
  NETWORK_TOOL_BUDGET_MS,
  PER_REQUEST_TIMEOUT_MS,
  WebLookupError,
  type WebLookupFailureKind,
} from "./localToolsWebHttp";

// ---------------------------------------------------------------------------
// local_wikipedia
// ---------------------------------------------------------------------------

const WIKIPEDIA_HOST = "en.wikipedia.org";

interface WikipediaSummary {
  title: string;
  description: string;
  extract: string;
  url: string;
}

function readWikipediaSummary(body: unknown): WikipediaSummary | null {
  if (!isObject(body)) return null;
  const title = asString(body.title);
  const extract = asString(body.extract);
  if (title === null || extract === null) return null;
  // A disambiguation page's "extract" is a list of unrelated meanings; saying
  // which it is beats handing the model a paragraph that looks like an answer.
  const type = asString(body.type);
  const description = asString(body.description) || (type === "disambiguation" ? "disambiguation page" : "");
  let url = "";
  const contentUrls = body.content_urls;
  if (isObject(contentUrls) && isObject(contentUrls.desktop)) {
    url = asString((contentUrls.desktop as Record<string, unknown>).page) || "";
  }
  return { title, description, extract, url };
}

async function fetchWikipediaSummary(title: string, deadline: Deadline): Promise<WikipediaSummary | null> {
  const path = encodeURIComponent(title.replace(/\s+/g, "_"));
  let body: unknown;
  try {
    body = await requestJson({
      url: `https://${WIKIPEDIA_HOST}/api/rest_v1/page/summary/${path}`,
      deadline,
      who: "Wikipedia",
      notFoundMessage: "__no_such_page__",
    });
  } catch (err) {
    if (err instanceof WebLookupError && err.kind === "not_found") return null;
    throw err;
  }
  return readWikipediaSummary(body);
}

/**
 * Title search, used only when the exact title misses. The `origin=*` parameter
 * is load-bearing, not decorative: the identical request without it came back
 * 200 with no `access-control-allow-origin` header at all (verified
 * 2026-09-12), which a browser would refuse to let the page read. It is also
 * MediaWiki's "treat this as anonymous" marker, which is the behaviour
 * credentials:"omit" already asks for on our side.
 */
async function searchWikipediaTitle(query: string, deadline: Deadline): Promise<string | null> {
  const params = new URLSearchParams({
    action: "query",
    list: "search",
    srsearch: query,
    srlimit: "1",
    format: "json",
    origin: "*",
  });
  const body = await requestJson({
    url: `https://${WIKIPEDIA_HOST}/w/api.php?${params.toString()}`,
    deadline,
    who: "Wikipedia",
  });
  if (!isObject(body) || !isObject(body.query)) return null;
  const hits = (body.query as Record<string, unknown>).search;
  if (!Array.isArray(hits) || hits.length === 0) return null;
  const first = hits[0];
  return isObject(first) ? asString(first.title) : null;
}

// ---------------------------------------------------------------------------
// local_weather
// ---------------------------------------------------------------------------

const GEOCODE_HOST = "geocoding-api.open-meteo.com";
const FORECAST_HOST = "api.open-meteo.com";

/** WMO 4677 present-weather codes, the vocabulary Open-Meteo's `weather_code`
 * uses. Only the codes that service documents as emitted are listed; anything
 * else is reported as its number rather than guessed at. */
const WEATHER_CODES: Record<number, string> = {
  0: "clear sky",
  1: "mainly clear",
  2: "partly cloudy",
  3: "overcast",
  45: "fog",
  48: "depositing rime fog",
  51: "light drizzle",
  53: "moderate drizzle",
  55: "dense drizzle",
  56: "light freezing drizzle",
  57: "dense freezing drizzle",
  61: "slight rain",
  63: "moderate rain",
  65: "heavy rain",
  66: "light freezing rain",
  67: "heavy freezing rain",
  71: "slight snowfall",
  73: "moderate snowfall",
  75: "heavy snowfall",
  77: "snow grains",
  80: "slight rain showers",
  81: "moderate rain showers",
  82: "violent rain showers",
  85: "slight snow showers",
  86: "heavy snow showers",
  95: "thunderstorm",
  96: "thunderstorm with slight hail",
  99: "thunderstorm with heavy hail",
};

function describeWeatherCode(code: unknown): string {
  const value = asNumber(code);
  if (value === null) return "unknown conditions";
  const known = WEATHER_CODES[value];
  return known || `WMO code ${value}`;
}

interface GeocodedPlace {
  name: string;
  latitude: number;
  longitude: number;
}

function readGeocode(body: unknown): GeocodedPlace | null {
  if (!isObject(body)) return null;
  const results = body.results;
  if (!Array.isArray(results) || results.length === 0) return null;
  const first = results[0];
  if (!isObject(first)) return null;
  const latitude = asNumber(first.latitude);
  const longitude = asNumber(first.longitude);
  const name = asString(first.name);
  if (latitude === null || longitude === null || name === null) return null;
  // Admin area and country disambiguate the many same-named towns, so the
  // person can see the tool picked the wrong Springfield.
  const parts = [name, asString(first.admin1), asString(first.country)].filter((p): p is string => p !== null);
  return { name: parts.join(", "), latitude, longitude };
}

function readDailyLine(daily: Record<string, unknown>, index: number, unitSuffix: string): string | null {
  const days = daily.time;
  const highs = daily.temperature_2m_max;
  const lows = daily.temperature_2m_min;
  const codes = daily.weather_code;
  if (!Array.isArray(days) || index >= days.length) return null;
  const day = asString(days[index]);
  if (day === null) return null;
  const high = Array.isArray(highs) ? asNumber(highs[index]) : null;
  const low = Array.isArray(lows) ? asNumber(lows[index]) : null;
  const code = Array.isArray(codes) ? codes[index] : null;
  const range =
    high !== null && low !== null ? `${low}${unitSuffix} to ${high}${unitSuffix}` : "temperature unavailable";
  return `${day}: ${range}, ${describeWeatherCode(code)}`;
}

// ---------------------------------------------------------------------------
// local_exchange_rate
// ---------------------------------------------------------------------------

const FX_HOST = "api.frankfurter.dev";

/**
 * The currency codes this source publishes, so an unsupported code is refused
 * on this device INSTEAD of being sent to a third party — the cheapest privacy
 * win available and a faster answer besides.
 *
 * Read from that service's own /v1/currencies endpoint on 2026-09-12: 30 codes,
 * the European Central Bank daily reference set. It can drift, which is why a
 * 404 from the rates call is still handled below rather than treated as
 * impossible.
 */
const FX_CURRENCIES: readonly string[] = [
  "AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD",
  "HUF", "IDR", "ILS", "INR", "ISK", "JPY", "KRW", "MXN", "MYR", "NOK",
  "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "USD", "ZAR",
];

function requireCurrency(args: Record<string, unknown>, key: string): string {
  const raw = args[key];
  if (typeof raw !== "string") {
    fail("arguments", `"${key}" must be a three-letter currency code such as "USD".`);
  }
  const code = raw.trim().toUpperCase();
  if (!/^[A-Z]{3}$/.test(code)) {
    fail("arguments", `"${raw}" is not a three-letter currency code (expected something like "USD" or "INR").`);
  }
  if (!FX_CURRENCIES.includes(code)) {
    fail(
      "arguments",
      `${code} is not one of the ${FX_CURRENCIES.length} currencies this source publishes. It carries: ${FX_CURRENCIES.join(", ")}.`
    );
  }
  return code;
}

/** 12 significant digits, then trailing zeros dropped: enough for a rate like
 * 0.86266 and for a large amount, without printing 1234.5600000000001. */
function formatMoney(value: number): string {
  return String(Number.parseFloat(value.toPrecision(12)));
}

// ---------------------------------------------------------------------------
// The tools
//
// Each description says the third party's name AND that the query is sent
// there. These strings are pasted verbatim into the tier-1 system prompt out of
// the same budget the conversation lives in, so they stay one line — but the
// disclosure is not the part that gets cut. A shorter description that hid the
// network would be the wrong saving.
// ---------------------------------------------------------------------------

export const WEB_LOCAL_TOOLS: LocalTool[] = [
  {
    name: "local_wikipedia",
    description:
      "Look up a topic summary on Wikipedia. SENDS the search words over the internet to Wikipedia (en.wikipedia.org), a third party — tell the person that before using it.",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Topic or article title, e.g. \"Ada Lovelace\"" },
      },
      required: ["query"],
      additionalProperties: false,
    },
    network: { hosts: [WIKIPEDIA_HOST] },
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const query = requireQuery(args, "query");
      const deadline = startDeadline(NETWORK_TOOL_BUDGET_MS);

      // Exact title first: it is one request in the common case, and the REST
      // summary endpoint already normalises capitalisation and spacing (verified
      // 2026-09-12: "Ada_lovelace" answered 200). The search hop only runs when
      // that misses, e.g. "Lovelace,_Ada", which answered 404.
      let summary = await fetchWikipediaSummary(query, deadline);
      let viaSearch = false;
      if (summary === null) {
        const found = await searchWikipediaTitle(query, deadline);
        if (found === null) {
          fail(
            "not_found",
            `Wikipedia has no article matching "${query}". A different spelling or a more specific name usually finds it.`
          );
        }
        viaSearch = true;
        summary = await fetchWikipediaSummary(found, deadline);
        if (summary === null) {
          fail(
            "not_found",
            `Wikipedia listed "${found}" for "${query}" but then had no summary for it. This is a gap on their side.`
          );
        }
      }

      const lines = [`${summary.title}${summary.description ? ` — ${summary.description}` : ""}`];
      if (viaSearch) lines.push(`(no exact article called "${query}"; this is the closest match Wikipedia returned)`);
      lines.push("", trimProse(summary.extract));
      if (summary.url) lines.push("", summary.url);
      lines.push(sourceLine("Wikipedia", WIKIPEDIA_HOST));
      return lines.join("\n");
    },
  },
  {
    name: "local_weather",
    description:
      "Current weather and a short forecast for a place. SENDS the place name over the internet to Open-Meteo (geocoding-api.open-meteo.com, then api.open-meteo.com), a third party — tell the person that before using it.",
    inputSchema: {
      type: "object",
      properties: {
        place: { type: "string", description: "Town or city, e.g. \"Bengaluru\" or \"Berlin, Germany\"" },
        days: { type: "number", description: "Forecast days, 1-7; default 3" },
        units: { type: "string", enum: ["celsius", "fahrenheit"], description: "Default celsius" },
      },
      required: ["place"],
      additionalProperties: false,
    },
    network: { hosts: [GEOCODE_HOST, FORECAST_HOST] },
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const place = requireQuery(args, "place");

      const rawDays = args.days;
      let days = 3;
      if (rawDays !== undefined && rawDays !== null) {
        const parsed = asNumber(rawDays);
        if (parsed === null || !Number.isInteger(parsed) || parsed < 1 || parsed > 7) {
          fail("arguments", '"days" must be a whole number from 1 to 7.');
        }
        days = parsed;
      }

      const rawUnits = args.units;
      let fahrenheit = false;
      if (rawUnits !== undefined && rawUnits !== null) {
        if (rawUnits !== "celsius" && rawUnits !== "fahrenheit") {
          fail("arguments", '"units" must be "celsius" or "fahrenheit".');
        }
        fahrenheit = rawUnits === "fahrenheit";
      }
      const unitSuffix = fahrenheit ? "°F" : "°C";

      const deadline = startDeadline(NETWORK_TOOL_BUDGET_MS);

      // Hop 1: place name → coordinates. Only the place name is sent.
      const geocodeParams = new URLSearchParams({ name: place, count: "1", language: "en", format: "json" });
      const geocodeBody = await requestJson({
        url: `https://${GEOCODE_HOST}/v1/search?${geocodeParams.toString()}`,
        deadline,
        who: "Open-Meteo",
        notFoundMessage: `Open-Meteo could not look up "${place}".`,
      });
      const located = readGeocode(geocodeBody);
      if (located === null) {
        fail(
          "not_found",
          `No place called "${place}" was found. Adding the country ("Springfield, United States") usually resolves it.`
        );
      }

      // Hop 2: coordinates → forecast. The place NAME is not sent again, and
      // nothing else is: no device id, no page, no account.
      const forecastParams = new URLSearchParams({
        latitude: String(located.latitude),
        longitude: String(located.longitude),
        current: "temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code",
        daily: "temperature_2m_max,temperature_2m_min,weather_code",
        forecast_days: String(days),
        timezone: "auto",
      });
      if (fahrenheit) forecastParams.set("temperature_unit", "fahrenheit");
      const forecastBody = await requestJson({
        url: `https://${FORECAST_HOST}/v1/forecast?${forecastParams.toString()}`,
        deadline,
        who: "Open-Meteo",
      });
      if (!isObject(forecastBody)) {
        fail("malformed", "Open-Meteo's forecast reply was not in the shape this tool reads, so there is no weather to report.");
      }

      const lines: string[] = [located.name];
      const current = forecastBody.current;
      if (isObject(current)) {
        const temp = asNumber(current.temperature_2m);
        const humidity = asNumber(current.relative_humidity_2m);
        const wind = asNumber(current.wind_speed_10m);
        const observedAt = asString(current.time);
        lines.push(
          `Now${observedAt ? ` (${observedAt}, local time there)` : ""}: ` +
            `${temp === null ? "temperature unavailable" : `${temp}${unitSuffix}`}, ` +
            `${describeWeatherCode(current.weather_code)}` +
            `${humidity === null ? "" : `, humidity ${humidity}%`}` +
            `${wind === null ? "" : `, wind ${wind} km/h`}`
        );
      } else {
        lines.push("Current conditions were not included in the reply.");
      }

      const daily = forecastBody.daily;
      if (isObject(daily)) {
        const forecastLines: string[] = [];
        for (let i = 0; i < days; i += 1) {
          const line = readDailyLine(daily, i, unitSuffix);
          if (line !== null) forecastLines.push(line);
        }
        if (forecastLines.length > 0) {
          lines.push("", "Forecast:", ...forecastLines);
        }
      }
      lines.push(sourceLine("Open-Meteo", `${GEOCODE_HOST} + ${FORECAST_HOST}`));
      return lines.join("\n");
    },
  },
  {
    name: "local_exchange_rate",
    description:
      "Convert an amount between currencies at the latest published rate. SENDS the currency pair over the internet to Frankfurter (api.frankfurter.dev), a third party serving European Central Bank reference rates — tell the person that before using it.",
    inputSchema: {
      type: "object",
      properties: {
        from: { type: "string", description: "Source currency code, e.g. \"USD\"" },
        to: { type: "string", description: "Target currency code, e.g. \"INR\"" },
        amount: { type: "number", description: "Amount to convert; default 1" },
      },
      required: ["from", "to"],
      additionalProperties: false,
    },
    network: { hosts: [FX_HOST] },
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const from = requireCurrency(args, "from");
      const to = requireCurrency(args, "to");

      const rawAmount = args.amount;
      let amount = 1;
      if (rawAmount !== undefined && rawAmount !== null) {
        const parsed = asNumber(rawAmount);
        if (parsed === null) fail("arguments", '"amount" must be a finite number.');
        amount = parsed;
      }

      if (from === to) {
        // No request at all: there is nothing to look up, so nothing is sent.
        return `${formatMoney(amount)} ${from} = ${formatMoney(amount)} ${to} (same currency; nothing was sent to any third party)`;
      }

      const deadline = startDeadline(NETWORK_TOOL_BUDGET_MS);
      const params = new URLSearchParams({ base: from, symbols: to });
      const body = await requestJson({
        url: `https://${FX_HOST}/v1/latest?${params.toString()}`,
        deadline,
        who: "Frankfurter",
        notFoundMessage: `Frankfurter does not publish a ${from}→${to} rate.`,
      });

      if (!isObject(body) || !isObject(body.rates)) {
        fail("malformed", "Frankfurter's reply did not contain a rates table, so there is no rate to report.");
      }
      const rate = asNumber((body.rates as Record<string, unknown>)[to]);
      if (rate === null) {
        fail("malformed", `Frankfurter's reply did not include a ${to} rate, so there is nothing to convert with.`);
      }
      const asOf = asString(body.date);

      return [
        `${formatMoney(amount)} ${from} = ${formatMoney(amount * rate)} ${to}`,
        `rate: 1 ${from} = ${formatMoney(rate)} ${to}${asOf ? ` (published ${asOf})` : ""}`,
        // Not a live market price, and a model that says "the current rate" from
        // a daily reference figure is wrong in a way people act on.
        "These are daily reference rates, not a live market or retail price; a bank or card will give a different number.",
        sourceLine("Frankfurter", FX_HOST),
      ].join("\n");
    },
  },
];
