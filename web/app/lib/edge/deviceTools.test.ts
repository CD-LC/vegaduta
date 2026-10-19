// Track 8 — the local Bluetooth device tools.
//
// Every test here runs against a FAKE Web Bluetooth adapter injected through
// __setDeviceToolDepsForTests. Nothing in this file touches a real radio: jsdom
// has no navigator.bluetooth at all, and a suite that needed one would be
// unrunnable in CI and on every machine without a paired peripheral. What the
// fake lets us test is the part that is actually ours — the degradation
// branches, the parsers, the sanitizer, and the bounds on what can reach GATT.
//
// Three kinds of test:
//
//  * BEHAVIOUR — the happy path for each metric, plus the ways Bluetooth is
//    normally unavailable. For this surface the failures ARE the feature.
//  * PARSER — the Bluetooth SIG profiles, including the packets a conformant
//    device sends that are not numbers (IEEE-11073 NaN, out-of-range).
//  * PROPERTY — the four the program is built on: no spend, no surprise
//    download, no silent network beyond the declared radio peer, and honest
//    degradation. These are the ones written to be hard to pass by accident.
//
// Split by area: this file holds the registry shape and the four properties;
// deviceTools.parsing.test.ts the lifecycle, sanitizer and parsers;
// deviceTools.pairing.test.ts the UI entry points and default-Chromium paths.

import { describe, expect, it, vi, afterEach } from "vitest";

import {
  ADAPTER_UNAVAILABLE_DETAIL,
  BLE_ALLOWED_UUIDS,
  BLE_METRICS,
  BLE_SERVICE_UUIDS,
  BLUETOOTH_RADIO_PEER,
  CONNECT_BUDGET_MS,
  DEVICE_TOOL_BUDGET_MS,
  INSECURE_CONTEXT_DETAIL,
  LOCAL_DEVICE_TOOLS,
  MAX_DEVICE_NAME_CHARS,
  NO_DEVICES_DETAIL,
  NO_GATT_DETAIL,
  NO_GET_DEVICES_DETAIL,
  UNSUPPORTED_BROWSER_DETAIL,
  __setDeviceToolDepsForTests,
  describeBluetoothSupport,
} from "./deviceTools";
import {
  BATTERY_LEVEL,
  BATTERY_SERVICE,
  ENVIRONMENTAL_SENSING_SERVICE,
  HEALTH_THERMOMETER_SERVICE,
  HEART_RATE_BAND,
  HEART_RATE_MEASUREMENT,
  HEART_RATE_SERVICE,
  bytes,
  call,
  codeLinesOf,
  deviceToolsFamilySources,
  install,
  tool,
} from "./deviceTools.testkit";
import { LOCAL_TOOL_PREFIX, LOCAL_TOOL_TIMEOUT_MS, runLocalTool } from "./localTools";

afterEach(() => {
  __setDeviceToolDepsForTests(null);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// Registry shape
// ---------------------------------------------------------------------------

describe("registry shape", () => {
  it("exports exactly the two namespaced tools this track owns", () => {
    expect(LOCAL_DEVICE_TOOLS.map((t) => t.name)).toEqual(["local_list_devices", "local_read_device"]);
    for (const t of LOCAL_DEVICE_TOOLS) {
      expect(t.name.startsWith(LOCAL_TOOL_PREFIX)).toBe(true);
      expect(typeof t.run).toBe("function");
      expect(t.description.length).toBeGreaterThan(0);
      // A wrapped paragraph is paid for out of the person's context window on
      // every turn — same rule the existing local tools are held to.
      expect(t.description).not.toContain("\n");
    }
  });

  it("keeps the handler budget strictly under runLocalTool's ceiling", () => {
    // The RELATIONSHIP is the property, not either number: if this module's
    // budget ever reached the ceiling, runLocalTool's anonymous "did not finish"
    // would replace every named Bluetooth refusal.
    expect(DEVICE_TOOL_BUDGET_MS).toBeLessThan(LOCAL_TOOL_TIMEOUT_MS);
    expect(CONNECT_BUDGET_MS).toBeLessThan(DEVICE_TOOL_BUDGET_MS);
  });
});

// ---------------------------------------------------------------------------
// PROPERTY 1 & 2 — no spend, no surprise download
// ---------------------------------------------------------------------------

describe("property: no spend, no download", () => {
  // The module is split across deviceTools.ts and its deviceTools{Core,Metrics,Read,Pair}.ts
  // siblings; the properties below hold for the family as a whole.
  const sources = deviceToolsFamilySources();
  const source = sources.map((f) => f.text).join("\n");

  it("imports nothing at runtime outside its own family", () => {
    for (const file of sources) {
      const statements = file.text.match(/^(?:import|export)\s[^;]*?from\s+"[^"]+";/gm) || [];
      for (const statement of statements) {
        const target = /from\s+"([^"]+)"/.exec(statement)?.[1] ?? "";
        const typeOnly = /^(?:import|export)\s+type\s/.test(statement);
        const sibling = /^\.\/deviceTools(?:Core|Metrics|Read|Pair)?$/.test(target);
        expect(typeOnly || sibling, `${file.name}: ${statement}`).toBe(true);
      }
      // A dynamic import would defeat the statement check above.
      expect(/[^.\w]import\s*\(/.test(file.text)).toBe(false);
      expect(file.text.includes("require(")).toBe(false);
    }
    // deviceTools.ts itself still imports its LocalTool type, so the scan is live.
    expect(sources[0].text).toContain('import type { LocalTool } from "./localTools";');
  });

  it("contains no IP-network call site and no platform endpoint", () => {
    // Comments talk ABOUT fetch and about /api paths, so the check is against
    // code lines only.
    const code = source
      .split("\n")
      .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
      .join("\n");
    for (const forbidden of [
      "fetch(",
      "XMLHttpRequest",
      "new WebSocket",
      "sendBeacon",
      "EventSource",
      "/api/",
      "http://",
      "https://",
      "toolBridge",
      "downloadManager",
      "tier1",
    ]) {
      expect(code.includes(forbidden)).toBe(false);
    }
  });

  it("writes nothing to storage while running either tool", async () => {
    install({ devices: [HEART_RATE_BAND] });
    const boom = (): never => {
      throw new Error("storage must not be touched: readings are never persisted");
    };
    vi.stubGlobal("localStorage", { getItem: boom, setItem: boom, removeItem: boom, clear: boom });
    vi.stubGlobal("sessionStorage", { getItem: boom, setItem: boom, removeItem: boom, clear: boom });
    vi.stubGlobal("indexedDB", { open: boom, deleteDatabase: boom });

    expect(await call("local_list_devices")).toContain("Polar H10");
    expect(await call("local_read_device", { device: "Polar H10", metric: "battery" })).toContain("88%");
  });

  it("never reaches the pairing chooser from a tool, whatever it is asked", async () => {
    // requestDevice on the fake throws if called at all; this drives the
    // reachable input space past it — including the arguments a model would try
    // if it were told to "pair a new device".
    const log = install({ devices: [HEART_RATE_BAND] });
    const attempts: [string, Record<string, unknown>][] = [
      ["local_list_devices", {}],
      ["local_list_devices", { pair: true, requestDevice: true }],
      ["local_read_device", { device: "Polar H10", metric: "battery" }],
      ["local_read_device", { device: "new device", metric: "battery" }],
      ["local_read_device", { device: "Polar H10", metric: "pair" }],
    ];
    for (const [name, args] of attempts) await call(name, args);
    expect(log.requestDevice).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// PROPERTY 3 — no silent network
// ---------------------------------------------------------------------------

describe("property: no silent network", () => {
  it("declares the radio peer on the tool that transmits and none on the one that does not", () => {
    expect(tool("local_list_devices").network).toBe("none");
    const read = tool("local_read_device").network;
    expect(read).not.toBe("none");
    expect(read).toEqual({ hosts: [BLUETOOTH_RADIO_PEER] });
    // Whatever the peer string is, it must not be mistakable for a host an
    // allowlist would let through.
    expect(BLUETOOTH_RADIO_PEER.startsWith("bluetooth")).toBe(true);
    expect(/^[a-z0-9.-]+$/i.test(BLUETOOTH_RADIO_PEER)).toBe(false);
  });

  it("says so in the words the model repeats, on the tool that transmits", () => {
    expect(tool("local_read_device").description.toLowerCase()).toMatch(/send/);
    expect(tool("local_read_device").description.toLowerCase()).toContain("bluetooth");
    // And the one that does NOT transmit says that too, rather than being silent
    // about it.
    expect(tool("local_list_devices").description.toLowerCase()).toContain("contacts no device");
  });

  it("makes no IP-network call while running either tool", async () => {
    install({ devices: [HEART_RATE_BAND] });
    const boom = (): never => {
      throw new Error("no local device tool may touch the IP network");
    };
    vi.stubGlobal("fetch", boom);
    vi.stubGlobal("XMLHttpRequest", boom);
    vi.stubGlobal("WebSocket", boom);
    vi.stubGlobal("EventSource", boom);
    const beacon = vi.fn();
    vi.stubGlobal("navigator", { sendBeacon: beacon });

    expect(await call("local_list_devices")).toContain("Polar H10");
    expect(await call("local_read_device", { device: "band1", metric: "heart_rate" })).toContain("72 bpm");
    expect(beacon).not.toHaveBeenCalled();
  });

  it("can only ever reach UUIDs from the frozen metric table, however it is fuzzed", async () => {
    const log = install({ devices: [HEART_RATE_BAND, { id: "t1", name: "Thermo", connected: true, services: {} }] });
    const hostile = [
      "0000ffe0-0000-1000-8000-00805f9b34fb",
      "device_information",
      "../../battery_service",
      "0000fd6f-0000-1000-8000-00805f9b34fb",
      "battery",
      "heart_rate",
      "temperature",
      "BATTERY",
      "heart rate",
      "heart-rate",
      "",
      "__proto__",
      "constructor",
    ];
    for (const metric of hostile) {
      for (const device of ["Polar H10", "band1", "t1", "Thermo", "\u202Eevil"]) {
        await call("local_read_device", { device, metric });
      }
    }
    // Arguments that are not strings at all.
    await call("local_read_device", { device: { toString: () => "band1" }, metric: ["battery"] });
    await call("local_read_device", { device: 42, metric: 7 });

    expect(log.uuids.length).toBeGreaterThan(0);
    for (const uuid of log.uuids) expect(BLE_ALLOWED_UUIDS).toContain(uuid);
  });

  it("freezes the metric table so it cannot be widened at runtime", () => {
    expect(() => (BLE_METRICS as unknown as unknown[]).push({})).toThrow();
    expect(BLE_SERVICE_UUIDS).toContain(BATTERY_SERVICE);
    expect(BLE_SERVICE_UUIDS).toContain(HEART_RATE_SERVICE);
    expect(BLE_SERVICE_UUIDS).toContain(HEALTH_THERMOMETER_SERVICE);
    expect(BLE_SERVICE_UUIDS).toContain(ENVIRONMENTAL_SENSING_SERVICE);
  });

  it("has no characteristic-write call site anywhere in the module", () => {
    // Comment lines are stripped for the same reason as in the no-spend test:
    // the header DISCUSSES writeValue (to state that there is none), and a
    // check that could not tell prose from code would make the module unable
    // to document its own guarantee.
    const code = codeLinesOf(
      deviceToolsFamilySources()
        .map((f) => f.text)
        .join("\n")
    );
    expect(code.includes("writeValue")).toBe(false);
    expect(code.includes("writeValueWithResponse")).toBe(false);
    expect(code.includes("startNotifications")).toBe(true); // the read path IS present
  });
});

// ---------------------------------------------------------------------------
// PROPERTY 4 — honest degradation
// ---------------------------------------------------------------------------

describe("property: honest degradation", () => {
  it("explains an absent API rather than reporting no devices", async () => {
    install({ noApi: true });
    const listed = await call("local_list_devices");
    expect(listed).toBe(UNSUPPORTED_BROWSER_DETAIL);
    expect(listed).not.toContain("No Bluetooth device is paired");
    expect(await call("local_read_device", { device: "x", metric: "battery" })).toBe(UNSUPPORTED_BROWSER_DETAIL);
    expect(describeBluetoothSupport().ok).toBe(false);
  });

  it("blames the insecure page, not the browser, when that is the cause", async () => {
    install({ noApi: true, secure: false });
    expect(await call("local_list_devices")).toBe(INSECURE_CONTEXT_DETAIL);
    // And when the API IS present but the page is not secure, same answer.
    install({ devices: [HEART_RATE_BAND], secure: false });
    expect(await call("local_list_devices")).toBe(INSECURE_CONTEXT_DETAIL);
  });

  it("distinguishes 'nothing paired' from 'I could not look'", async () => {
    install({ devices: [] });
    expect(await call("local_list_devices")).toBe(NO_DEVICES_DETAIL);

    install({ devices: [], availability: false });
    const blocked = await call("local_list_devices");
    expect(blocked).toBe(ADAPTER_UNAVAILABLE_DETAIL);
    expect(blocked).toContain("I could not look");

    // A getAvailability that itself fails tells us nothing, so the answer falls
    // back to the claim we did verify — the paired list was genuinely empty.
    install({ devices: [], availabilityThrows: true });
    expect(await call("local_list_devices")).toBe(NO_DEVICES_DETAIL);
  });

  it("names the missing getDevices rather than claiming an empty list", async () => {
    install({ noGetDevices: true });
    const out = await call("local_list_devices");
    expect(out).toBe(NO_GET_DEVICES_DETAIL);
    expect(out).toContain("I am NOT reporting that nothing is");
  });

  it("says a permissions policy blocked the lookup", async () => {
    install({ getDevicesRejects: "SecurityError" });
    const out = await call("local_list_devices");
    expect(out).toContain("permissions policy");
    expect(out).toContain("this is not 'no devices'");
  });

  it("tells the person pairing needs their click", async () => {
    install({ devices: [] });
    const out = await call("local_read_device", { device: "anything", metric: "battery" });
    expect(out).toContain("cannot open the pairing chooser myself");
    expect(out).toContain("button");
  });

  it("refuses an unknown metric by naming the ones that exist", async () => {
    install({ devices: [HEART_RATE_BAND] });
    const out = await call("local_read_device", { device: "band1", metric: "blood_pressure" });
    expect(out).toContain("battery");
    expect(out).toContain("heart_rate");
    expect(out).toContain("temperature");
    expect(out).toContain("arbitrary characteristic");
  });

  it("refuses an ambiguous name instead of guessing a device", async () => {
    install({
      devices: [
        {
          id: "a",
          name: "Watch",
          connected: true,
          services: { [BATTERY_SERVICE]: { [BATTERY_LEVEL]: { value: bytes(10) } } },
        },
        {
          id: "b",
          name: "Watch",
          connected: true,
          services: { [BATTERY_SERVICE]: { [BATTERY_LEVEL]: { value: bytes(90) } } },
        },
      ],
    });
    const out = await call("local_read_device", { device: "Watch", metric: "battery" });
    expect(out).toContain("will not guess");
    expect(out).not.toContain("10%");
    expect(out).not.toContain("90%");
    // The id disambiguates.
    expect(await call("local_read_device", { device: "b", metric: "battery" })).toContain("90%");
  });

  it("matches an id longer than the device-name cap", async () => {
    // Regression: the argument is matched against names AND ids, and the name
    // cap is the smaller of the two bounds. Capping the needle at it truncated
    // a long id and reported "no paired device matches" for a device that was
    // right there in the list.
    const longId = "A".repeat(MAX_DEVICE_NAME_CHARS + 8);
    install({
      devices: [
        {
          id: longId,
          name: "Sensor",
          connected: true,
          services: { [BATTERY_SERVICE]: { [BATTERY_LEVEL]: { value: bytes(33) } } },
        },
      ],
    });
    expect(await call("local_read_device", { device: longId, metric: "battery" })).toContain("33%");
  });

  it("does not substring-match a device name", async () => {
    install({
      devices: [
        {
          id: "a",
          name: "Polar H10",
          connected: true,
          services: { [BATTERY_SERVICE]: { [BATTERY_LEVEL]: { value: bytes(50) } } },
        },
      ],
    });
    const out = await call("local_read_device", { device: "Polar", metric: "battery" });
    expect(out).toContain("No paired device matches");
    expect(out).not.toContain("50%");
  });

  it("explains a device with no GATT server", async () => {
    install({ devices: [{ id: "hs", name: "Headset", noGatt: true }] });
    expect(await call("local_read_device", { device: "hs", metric: "battery" })).toBe(NO_GATT_DETAIL);
  });

  it("explains a device that has no such profile, and says what it looked for", async () => {
    install({ devices: [{ id: "p", name: "Plug", connected: true, services: {} }] });
    const out = await call("local_read_device", { device: "p", metric: "temperature" });
    expect(out).toContain("Health Thermometer Service");
    expect(out).toContain("Environmental Sensing Service");
    expect(out).toContain("vendor-specific");
  });

  it("beats runLocalTool's anonymous ceiling with a named refusal when a connect hangs", async () => {
    install({
      devices: [
        {
          id: "slow",
          name: "Sleepy",
          connected: false,
          connectDelayMs: 5_000,
          services: { [BATTERY_SERVICE]: { [BATTERY_LEVEL]: { value: bytes(5) } } },
        },
      ],
    });
    const outcome = await runLocalTool(tool("local_read_device"), { device: "slow", metric: "battery" });
    // ok:true is the point — the refusal is a normal result, not a "failed".
    expect(outcome.ok).toBe(true);
    if (outcome.ok === true) {
      expect(outcome.result).toContain("did not connect");
      expect(outcome.result).toContain("out of range");
      expect(outcome.result).not.toContain("was abandoned");
    }
  });

  it("explains a refused connection as the exclusive-connection problem it usually is", async () => {
    install({
      devices: [{ id: "busy", name: "Busy", connected: false, connectRejects: "NetworkError", services: {} }],
    });
    const out = await call("local_read_device", { device: "busy", metric: "battery" });
    expect(out).toContain("NetworkError");
    expect(out).toContain("one connection at a time");
  });

  it("explains a notify-only characteristic that never sends", async () => {
    install({
      devices: [
        {
          id: "q",
          name: "Quiet Strap",
          connected: true,
          services: {
            [HEART_RATE_SERVICE]: { [HEART_RATE_MEASUREMENT]: { read: false, notify: true, value: bytes(0x00, 60) } },
          },
        },
      ],
    });
    const out = await call("local_read_device", { device: "q", metric: "heart_rate" });
    expect(out).toContain("notify-only");
    expect(out).toContain("not being worn");
  });

  it("explains a characteristic that supports neither read nor notify", async () => {
    install({
      devices: [
        {
          id: "w",
          name: "Weird",
          connected: true,
          services: { [BATTERY_SERVICE]: { [BATTERY_LEVEL]: { read: false, notify: false } } },
        },
      ],
    });
    const out = await call("local_read_device", { device: "w", metric: "battery" });
    expect(out).toContain("neither a plain read nor notifications");
  });
});
