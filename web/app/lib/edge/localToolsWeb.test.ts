// E-2c phase 6 — the networked local tools.
//
// Every test here runs against a MOCKED fetch. Nothing in this file talks to
// en.wikipedia.org, open-meteo.com or frankfurter.dev: a suite that made real
// requests would be slow, flaky, and would quietly start passing or failing on
// someone else's deploy. The CORS verification those hosts needed is a
// different kind of check and was done by hand with curl — the header evidence
// and the date are recorded in localToolsWeb.ts's header, where the hosts are.
//
// Two kinds of test, as in localTools.test.ts:
//
//  * BEHAVIOUR — each tool's happy path plus the four failures that are NORMAL
//    for a network tool: a non-200, a timeout, a malformed body, and a body
//    too large to accept.
//  * PROPERTY — the promises the descriptions make. That the request carries no
//    credentials and no headers at all; that nothing but the query is in the
//    URL; that every host contacted is one of the declared ones; and that each
//    description actually names the third party it sends to.

import { afterEach, describe, expect, it, vi } from "vitest";

import {
  ALLOWED_HOSTS,
  MAX_RESPONSE_BYTES,
  NETWORK_TOOL_BUDGET_MS,
  PER_REQUEST_TIMEOUT_MS,
  WEB_LOCAL_TOOLS,
  WebLookupError,
} from "./localToolsWeb";
import { LOCAL_TOOL_PREFIX, LOCAL_TOOL_TIMEOUT_MS, runLocalTool, type LocalTool } from "./localTools";

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

function tool(name: string): LocalTool {
  const found = WEB_LOCAL_TOOLS.find((t) => t.name === name);
  if (!found) throw new Error(`no such web tool: ${name}`);
  return found;
}

interface FakeResponseInit {
  status?: number;
  /** Overrides the JSON body with raw text (for the malformed-body case). */
  text?: string;
  headers?: Record<string, string>;
  /** When set, the response exposes a streaming body of these byte chunks. */
  chunks?: Uint8Array[];
}

/** A Response-shaped object. Deliberately hand-built rather than a real
 * Response: these tests need to control content-length, the stream and the
 * status independently, including combinations a real server would not send. */
function fakeResponse(body: unknown, init?: FakeResponseInit): Response {
  const headers = init && init.headers ? init.headers : {};
  const text = init && typeof init.text === "string" ? init.text : JSON.stringify(body);
  const chunks = init && init.chunks ? init.chunks : null;
  let cancelled = false;
  let index = 0;
  const response = {
    status: init && typeof init.status === "number" ? init.status : 200,
    headers: {
      get: (name: string): string | null => {
        const key = Object.keys(headers).find((k) => k.toLowerCase() === name.toLowerCase());
        return key === undefined ? null : headers[key];
      },
    },
    body: chunks
      ? {
          getReader: () => ({
            read: async (): Promise<{ done: boolean; value?: Uint8Array }> => {
              if (index >= chunks.length) return { done: true };
              const value = chunks[index];
              index += 1;
              return { done: false, value };
            },
            cancel: async (): Promise<void> => {
              cancelled = true;
            },
          }),
        }
      : null,
    text: async (): Promise<string> => text,
    /** Test-only probe: was the oversized stream actually stopped? */
    __cancelled: (): boolean => cancelled,
  };
  return response as unknown as Response;
}

/** Installs a fetch mock that answers each call from `queue` in order. */
function stubFetch(queue: Array<Response | Error>): ReturnType<typeof vi.fn> {
  let call = 0;
  const mock = vi.fn(async (_url: string, _init: RequestInit) => {
    const next = queue[call];
    call += 1;
    if (next === undefined) throw new Error(`unexpected fetch call #${call} to ${_url}`);
    if (next instanceof Error) throw next;
    return next;
  });
  vi.stubGlobal("fetch", mock);
  return mock as unknown as ReturnType<typeof vi.fn>;
}

/** Runs a tool handler directly and returns the WebLookupError it threw.
 * Direct, not through runLocalTool, because runLocalTool's own 2s ceiling would
 * otherwise decide several of these outcomes before this module could — see the
 * "runner ceiling" test at the bottom, which is where that collision is
 * asserted on purpose rather than tripped over by accident. */
async function failureOf(name: string, args: Record<string, unknown>): Promise<WebLookupError> {
  try {
    await tool(name).run(args);
  } catch (err) {
    if (err instanceof WebLookupError) return err;
    throw err;
  }
  throw new Error(`${name} unexpectedly succeeded`);
}

const WIKI_SUMMARY = {
  type: "standard",
  title: "Ada Lovelace",
  description: "English mathematician (1815–1852)",
  extract: "Augusta Ada King, Countess of Lovelace, was an English mathematician and writer.",
  content_urls: { desktop: { page: "https://en.wikipedia.org/wiki/Ada_Lovelace" } },
};

const GEOCODE_BODY = {
  results: [
    {
      name: "Bengaluru",
      latitude: 12.97194,
      longitude: 77.59369,
      country: "India",
      admin1: "Karnataka",
      timezone: "Asia/Kolkata",
    },
  ],
};

const FORECAST_BODY = {
  current: { time: "2026-09-12T04:30", temperature_2m: 13.2, relative_humidity_2m: 75, wind_speed_10m: 3.6, weather_code: 3 },
  daily: {
    time: ["2026-09-12", "2026-09-13", "2026-09-14"],
    temperature_2m_max: [21.7, 18.6, 17.5],
    temperature_2m_min: [13.2, 15.4, 11.8],
    weather_code: [3, 80, 3],
  },
};

const FX_BODY = { amount: 1.0, base: "USD", date: "2026-09-11", rates: { INR: 95.56 } };

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// ---------------------------------------------------------------------------
// Registry shape and disclosure
// ---------------------------------------------------------------------------

describe("registry", () => {
  it("exports three namespaced, non-consequential tools", () => {
    expect(WEB_LOCAL_TOOLS.map((t) => t.name)).toEqual(["local_wikipedia", "local_weather", "local_exchange_rate"]);
    for (const t of WEB_LOCAL_TOOLS) {
      expect(t.name.startsWith(LOCAL_TOOL_PREFIX)).toBe(true);
      expect(t.consequential).toBe(false);
      expect(typeof t.run).toBe("function");
    }
  });

  it("declares its hosts, and declares only hosts that were verified", () => {
    for (const t of WEB_LOCAL_TOOLS) {
      // The whole point of phase 6: these are NOT "none".
      expect(t.network).not.toBe("none");
      const network = t.network;
      if (network === "none") throw new Error(`${t.name} still declares network:"none"`);
      expect(network.hosts.length).toBeGreaterThan(0);
      for (const host of network.hosts) {
        expect(ALLOWED_HOSTS).toContain(host);
      }
    }
  });

  it("tells the person, in the description, that their words leave the device", () => {
    for (const t of WEB_LOCAL_TOOLS) {
      const network = t.network;
      if (network === "none") throw new Error(`${t.name} still declares network:"none"`);
      // "SENDS" is the word the model repeats; the host is what makes it
      // checkable. A description that named neither would be the failure mode
      // this whole phase exists to avoid.
      expect(t.description).toContain("SENDS");
      for (const host of network.hosts) {
        expect(t.description).toContain(host);
      }
    }
  });
});

// ---------------------------------------------------------------------------
// local_wikipedia
// ---------------------------------------------------------------------------

describe("local_wikipedia", () => {
  it("answers from the exact-title summary in one request", async () => {
    const mock = stubFetch([fakeResponse(WIKI_SUMMARY)]);
    const result = await tool("local_wikipedia").run({ query: "Ada Lovelace" });

    expect(mock).toHaveBeenCalledTimes(1);
    expect(mock.mock.calls[0][0]).toBe("https://en.wikipedia.org/api/rest_v1/page/summary/Ada_Lovelace");
    expect(result).toContain("Ada Lovelace — English mathematician");
    expect(result).toContain("Countess of Lovelace");
    expect(result).toContain("https://en.wikipedia.org/wiki/Ada_Lovelace");
    expect(result).toContain("your query was sent there");
  });

  it("falls back to search when the exact title 404s, and says it did", async () => {
    const mock = stubFetch([
      fakeResponse({ title: "Not found" }, { status: 404 }),
      fakeResponse({ query: { search: [{ title: "Ada Lovelace" }] } }),
      fakeResponse(WIKI_SUMMARY),
    ]);
    const result = await tool("local_wikipedia").run({ query: "Lovelace, Ada" });

    expect(mock).toHaveBeenCalledTimes(3);
    // origin=* is what makes the action API answer a browser at all.
    expect(String(mock.mock.calls[1][0])).toContain("origin=*");
    expect(result).toContain("no exact article called");
    expect(result).toContain("Ada Lovelace");
  });

  it("says so plainly when nothing matches", async () => {
    stubFetch([
      fakeResponse({}, { status: 404 }),
      fakeResponse({ query: { search: [] } }),
    ]);
    const err = await failureOf("local_wikipedia", { query: "qqqzzz not a topic" });
    expect(err.kind).toBe("not_found");
    expect(err.message).toContain("no article matching");
  });

  it("reports a non-200 as the source's problem, not the device's", async () => {
    stubFetch([fakeResponse({}, { status: 503 })]);
    const err = await failureOf("local_wikipedia", { query: "Ada Lovelace" });
    expect(err.kind).toBe("http");
    expect(err.message).toContain("HTTP 503");
    expect(err.message).toContain("their side");
  });

  it("reports a 429 as something worth retrying later", async () => {
    stubFetch([fakeResponse({}, { status: 429 })]);
    const err = await failureOf("local_wikipedia", { query: "Ada Lovelace" });
    expect(err.kind).toBe("rate_limited");
    expect(err.message).toContain("429");
  });

  it("reports a body that is not JSON as malformed, not as a crash", async () => {
    stubFetch([fakeResponse(null, { text: "<html>maintenance</html>" })]);
    const err = await failureOf("local_wikipedia", { query: "Ada Lovelace" });
    expect(err.kind).toBe("malformed");
    expect(err.message).toContain("not the data this tool expects");
  });

  it("treats a JSON body missing the fields it needs as a miss, then searches", async () => {
    const mock = stubFetch([
      fakeResponse({ type: "standard" }),
      fakeResponse({ query: { search: [{ title: "Ada Lovelace" }] } }),
      fakeResponse(WIKI_SUMMARY),
    ]);
    const result = await tool("local_wikipedia").run({ query: "Ada Lovelace" });
    expect(mock).toHaveBeenCalledTimes(3);
    expect(result).toContain("Ada Lovelace");
  });

  it("refuses an over-long query before sending anything", async () => {
    const mock = stubFetch([]);
    const err = await failureOf("local_wikipedia", { query: "x".repeat(500) });
    expect(err.kind).toBe("arguments");
    expect(mock).not.toHaveBeenCalled();
  });

  it("refuses a non-string query before sending anything", async () => {
    const mock = stubFetch([]);
    const err = await failureOf("local_wikipedia", { query: 42 });
    expect(err.kind).toBe("arguments");
    expect(mock).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// local_weather
// ---------------------------------------------------------------------------

describe("local_weather", () => {
  it("geocodes then forecasts, and reports both", async () => {
    const mock = stubFetch([fakeResponse(GEOCODE_BODY), fakeResponse(FORECAST_BODY)]);
    const result = await tool("local_weather").run({ place: "Bengaluru", days: 3 });

    expect(mock).toHaveBeenCalledTimes(2);
    expect(String(mock.mock.calls[0][0])).toContain("geocoding-api.open-meteo.com");
    expect(String(mock.mock.calls[1][0])).toContain("api.open-meteo.com");
    expect(result).toContain("Bengaluru, Karnataka, India");
    expect(result).toContain("13.2°C");
    expect(result).toContain("overcast");
    expect(result).toContain("2026-09-13: 15.4°C to 18.6°C, slight rain showers");
    expect(result).toContain("your query was sent there");
  });

  it("sends the coordinates, not the place name, on the second hop", async () => {
    const mock = stubFetch([fakeResponse(GEOCODE_BODY), fakeResponse(FORECAST_BODY)]);
    await tool("local_weather").run({ place: "Bengaluru" });
    const forecastUrl = String(mock.mock.calls[1][0]);
    expect(forecastUrl).toContain("latitude=12.97194");
    expect(forecastUrl.toLowerCase()).not.toContain("bengaluru");
  });

  it("switches units on request", async () => {
    const mock = stubFetch([fakeResponse(GEOCODE_BODY), fakeResponse(FORECAST_BODY)]);
    const result = await tool("local_weather").run({ place: "Bengaluru", units: "fahrenheit" });
    expect(String(mock.mock.calls[1][0])).toContain("temperature_unit=fahrenheit");
    expect(result).toContain("13.2°F");
  });

  it("explains an unfindable place instead of returning nothing", async () => {
    stubFetch([fakeResponse({ generationtime_ms: 0.4 })]);
    const err = await failureOf("local_weather", { place: "Nowhereville" });
    expect(err.kind).toBe("not_found");
    expect(err.message).toContain("Adding the country");
  });

  it("explains a forecast reply it cannot read", async () => {
    stubFetch([fakeResponse(GEOCODE_BODY), fakeResponse([1, 2, 3])]);
    const err = await failureOf("local_weather", { place: "Bengaluru" });
    expect(err.kind).toBe("malformed");
  });

  it("refuses an out-of-range day count before sending anything", async () => {
    const mock = stubFetch([]);
    const err = await failureOf("local_weather", { place: "Bengaluru", days: 30 });
    expect(err.kind).toBe("arguments");
    expect(err.message).toContain("1 to 7");
    expect(mock).not.toHaveBeenCalled();
  });

  it("says the device could not reach the host when fetch itself fails", async () => {
    stubFetch([new TypeError("Failed to fetch")]);
    const err = await failureOf("local_weather", { place: "Bengaluru" });
    expect(err.kind).toBe("offline");
    expect(err.message).toContain("offline or something on the network is blocking it");
  });
});

// ---------------------------------------------------------------------------
// local_exchange_rate
// ---------------------------------------------------------------------------

describe("local_exchange_rate", () => {
  it("converts at the published rate and says the rate is not a market price", async () => {
    const mock = stubFetch([fakeResponse(FX_BODY)]);
    const result = await tool("local_exchange_rate").run({ from: "usd", to: "inr", amount: 10 });

    expect(mock).toHaveBeenCalledTimes(1);
    expect(result).toContain("10 USD = 955.6 INR");
    expect(result).toContain("1 USD = 95.56 INR");
    expect(result).toContain("published 2026-09-11");
    expect(result).toContain("not a live market or retail price");
  });

  it("sends nothing at all when the two currencies are the same", async () => {
    const mock = stubFetch([]);
    const result = await tool("local_exchange_rate").run({ from: "EUR", to: "EUR", amount: 5 });
    expect(mock).not.toHaveBeenCalled();
    expect(result).toContain("nothing was sent to any third party");
  });

  it("refuses an unpublished currency on this device, before any request", async () => {
    const mock = stubFetch([]);
    const err = await failureOf("local_exchange_rate", { from: "USD", to: "XBT" });
    expect(err.kind).toBe("arguments");
    expect(err.message).toContain("currencies this source publishes");
    expect(mock).not.toHaveBeenCalled();
  });

  it("refuses something that is not a currency code", async () => {
    const mock = stubFetch([]);
    const err = await failureOf("local_exchange_rate", { from: "dollars", to: "INR" });
    expect(err.kind).toBe("arguments");
    expect(mock).not.toHaveBeenCalled();
  });

  it("reports a 404 from the source as a missing pair", async () => {
    stubFetch([fakeResponse({ message: "not found" }, { status: 404 })]);
    const err = await failureOf("local_exchange_rate", { from: "USD", to: "INR" });
    expect(err.kind).toBe("not_found");
    expect(err.message).toContain("USD→INR");
  });

  it("reports a rates table with no rate in it as malformed", async () => {
    stubFetch([fakeResponse({ base: "USD", date: "2026-09-11", rates: {} })]);
    const err = await failureOf("local_exchange_rate", { from: "USD", to: "INR" });
    expect(err.kind).toBe("malformed");
  });
});

// ---------------------------------------------------------------------------
// PROPERTY — what the request does and does not carry
// ---------------------------------------------------------------------------

describe("property: no credentials, no identifiers, no extra hosts", () => {
  it("attaches no cookies and no credentials of any kind", async () => {
    const mock = stubFetch([fakeResponse(GEOCODE_BODY), fakeResponse(FORECAST_BODY)]);
    await tool("local_weather").run({ place: "Bengaluru" });

    expect(mock).toHaveBeenCalledTimes(2);
    for (const call of mock.mock.calls) {
      const init = call[1] as RequestInit & { referrerPolicy?: string };
      // credentials:"omit" is THE option that keeps cookies, HTTP auth and
      // client certs off the request, and keeps a Set-Cookie from being stored.
      expect(init.credentials).toBe("omit");
      // No header object at all: nothing can be carrying an Authorization, a
      // session id or a device id if there is no place to put one.
      expect(init.headers).toBeUndefined();
      // The page URL is not the third party's business.
      expect(init.referrerPolicy).toBe("no-referrer");
      // A redirect would take the query to a host nobody verified.
      expect(init.redirect).toBe("error");
      expect(init.mode).toBe("cors");
      expect(init.cache).toBe("no-store");
      expect(init.method).toBe("GET");
    }
  });

  it("puts nothing but the query in the URL", async () => {
    const mock = stubFetch([fakeResponse(GEOCODE_BODY), fakeResponse(FORECAST_BODY)]);
    await tool("local_weather").run({ place: "Bengaluru", days: 2 });

    const geocode = new URL(String(mock.mock.calls[0][0]));
    expect([...geocode.searchParams.keys()].sort()).toEqual(["count", "format", "language", "name"]);
    expect(geocode.searchParams.get("name")).toBe("Bengaluru");

    const forecast = new URL(String(mock.mock.calls[1][0]));
    expect([...forecast.searchParams.keys()].sort()).toEqual([
      "current",
      "daily",
      "forecast_days",
      "latitude",
      "longitude",
      "timezone",
    ]);
  });

  it("contacts only the hosts it declared, across every tool", async () => {
    const seen: string[] = [];
    const record = (url: string): void => {
      seen.push(new URL(url).hostname);
    };

    const wikiMock = stubFetch([fakeResponse(WIKI_SUMMARY)]);
    await tool("local_wikipedia").run({ query: "Ada Lovelace" });
    for (const call of wikiMock.mock.calls) record(String(call[0]));
    vi.unstubAllGlobals();

    const weatherMock = stubFetch([fakeResponse(GEOCODE_BODY), fakeResponse(FORECAST_BODY)]);
    await tool("local_weather").run({ place: "Bengaluru" });
    for (const call of weatherMock.mock.calls) record(String(call[0]));
    vi.unstubAllGlobals();

    const fxMock = stubFetch([fakeResponse(FX_BODY)]);
    await tool("local_exchange_rate").run({ from: "USD", to: "INR" });
    for (const call of fxMock.mock.calls) record(String(call[0]));

    expect(seen.length).toBeGreaterThan(0);
    for (const host of seen) expect(ALLOWED_HOSTS).toContain(host);
  });
});

// ---------------------------------------------------------------------------
// PROPERTY — bounded time and bounded bytes
// ---------------------------------------------------------------------------

describe("property: bounded", () => {
  it("refuses a response that declares itself over the size cap, without reading it", async () => {
    const oversized = fakeResponse({}, { headers: { "content-length": String(MAX_RESPONSE_BYTES + 1) } });
    const textSpy = vi.spyOn(oversized as unknown as { text: () => Promise<string> }, "text");
    stubFetch([oversized]);

    const err = await failureOf("local_exchange_rate", { from: "USD", to: "INR" });
    expect(err.kind).toBe("too_large");
    expect(err.message).toContain("nothing was downloaded");
    expect(textSpy).not.toHaveBeenCalled();
  });

  it("stops a chunked response the moment it crosses the cap", async () => {
    // No content-length, like api.open-meteo.com's chunked forecast replies.
    const chunk = new Uint8Array(MAX_RESPONSE_BYTES / 2 + 1);
    const streamed = fakeResponse({}, { chunks: [chunk, chunk, chunk] });
    stubFetch([streamed]);

    const err = await failureOf("local_exchange_rate", { from: "USD", to: "INR" });
    expect(err.kind).toBe("too_large");
    expect((streamed as unknown as { __cancelled: () => boolean }).__cancelled()).toBe(true);
  });

  it("reads a chunked response that stays under the cap", async () => {
    const encoded = new TextEncoder().encode(JSON.stringify(FX_BODY));
    const half = Math.floor(encoded.length / 2);
    const streamed = fakeResponse({}, { chunks: [encoded.subarray(0, half), encoded.subarray(half)] });
    stubFetch([streamed]);

    const result = await tool("local_exchange_rate").run({ from: "USD", to: "INR" });
    expect(result).toContain("1 USD = 95.56 INR");
  });

  it("gives up on a host that never answers, with a sentence about retrying", async () => {
    vi.useFakeTimers();
    // Rejects only when the AbortController fires — exactly what a browser does
    // to an aborted fetch, and the only way a stalled request ever ends here.
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_url: string, init: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
          })
      )
    );

    const pending = tool("local_exchange_rate").run({ from: "USD", to: "INR" });
    const settled = pending.then(
      (): unknown => null,
      (err: unknown) => err
    );
    await vi.advanceTimersByTimeAsync(PER_REQUEST_TIMEOUT_MS + 100);
    const err = await settled;

    expect(err).toBeInstanceOf(WebLookupError);
    expect((err as WebLookupError).kind).toBe("timeout");
    expect((err as WebLookupError).message).toContain("Trying again later may work");
  });

  it("keeps the per-request ceiling inside the whole-call budget", () => {
    expect(PER_REQUEST_TIMEOUT_MS).toBeLessThanOrEqual(NETWORK_TOOL_BUDGET_MS);
  });
});

// ---------------------------------------------------------------------------
// The runner ceiling — an honest record of a collision this module cannot fix
// ---------------------------------------------------------------------------

describe("runLocalTool integration", () => {
  it("turns a network failure into a typed result, never an exception", async () => {
    stubFetch([new TypeError("Failed to fetch")]);
    const result = await runLocalTool(tool("local_exchange_rate"), { from: "USD", to: "INR" });
    if (result.ok === true) throw new Error(`expected a failure, got: ${result.result}`);
    expect(result.code).toBe("failed");
    expect(result.detail).toContain("offline or something on the network is blocking it");
  });

  it("turns a success into a capped ok result", async () => {
    stubFetch([fakeResponse(FX_BODY)]);
    const result = await runLocalTool(tool("local_exchange_rate"), { from: "USD", to: "INR" });
    if (result.ok === false) throw new Error(`expected ok, got ${result.code}: ${result.detail}`);
    expect(result.result).toContain("1 USD = 95.56 INR");
  });

  it("is abandoned by the runner's pure-compute ceiling before its own budget can run", async () => {
    // This test documents a REAL, unfixed collision rather than asserting a
    // desirable behaviour: LOCAL_TOOL_TIMEOUT_MS in localTools.ts is 2_000 and
    // was sized for arithmetic, while a single measured round trip to
    // api.open-meteo.com took 1.12-1.16s and local_weather needs two of them.
    // The outcome is at least honest — a typed "did not finish" failure, not
    // silence — but the tool is unusable until that ceiling is raised, which is
    // localTools.ts's track to do. When it is raised past
    // NETWORK_TOOL_BUDGET_MS, this test's expectation flips to the timeout
    // sentence from this module instead, and the comment can go.
    expect(LOCAL_TOOL_TIMEOUT_MS).toBeLessThan(NETWORK_TOOL_BUDGET_MS);

    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_url: string, init: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
          })
      )
    );

    const pending = runLocalTool(tool("local_exchange_rate"), { from: "USD", to: "INR" });
    await vi.advanceTimersByTimeAsync(LOCAL_TOOL_TIMEOUT_MS + 50);
    const result = await pending;

    if (result.ok === true) throw new Error(`expected a failure, got: ${result.result}`);
    expect(result.code).toBe("failed");
    expect(result.detail).toContain(`did not finish within ${LOCAL_TOOL_TIMEOUT_MS}ms`);
  });
});
