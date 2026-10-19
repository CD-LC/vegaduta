// Test kit for deviceTools*.test.ts: UUID constants, a fake Web Bluetooth
// adapter and the helpers that drive the real LOCAL_DEVICE_TOOLS through
// runLocalTool. Not a test file itself.

import { readFileSync } from "node:fs";
import { join } from "node:path";

import {
  LOCAL_DEVICE_TOOLS,
  __setDeviceToolDepsForTests,
  type BluetoothApiLike,
  type BluetoothCharacteristicLike,
  type BluetoothDeviceLike,
  type DeviceToolDeps,
} from "./deviceTools";
import { runLocalTool, type LocalTool } from "./localTools";


// ---------------------------------------------------------------------------
// UUIDs, written out rather than imported, so a table edit that silently
// repointed a metric at a different characteristic fails here.
// ---------------------------------------------------------------------------

export const BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb";
export const BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb";
export const HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb";
export const HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb";
export const HEALTH_THERMOMETER_SERVICE = "00001809-0000-1000-8000-00805f9b34fb";
export const TEMPERATURE_MEASUREMENT = "00002a1c-0000-1000-8000-00805f9b34fb";
export const ENVIRONMENTAL_SENSING_SERVICE = "0000181a-0000-1000-8000-00805f9b34fb";
export const ENVIRONMENTAL_TEMPERATURE = "00002a6e-0000-1000-8000-00805f9b34fb";

// ---------------------------------------------------------------------------
// Fake adapter
// ---------------------------------------------------------------------------

export function bytes(...values: number[]): DataView {
  return new DataView(Uint8Array.from(values).buffer);
}

export interface FakeCharacteristicInit {
  value?: DataView;
  read?: boolean;
  notify?: boolean;
  /** Rejects readValue with a DOMException-shaped error of this name. */
  readRejects?: string;
  /** ms of simulated delay before readValue settles (real timers). */
  readDelayMs?: number;
  /** Notify path: value pushed after this delay; omit for "never notifies". */
  notifyAfterMs?: number;
}

export interface FakeDeviceInit {
  id: string;
  name?: string | null;
  connected?: boolean;
  /** service uuid -> characteristic uuid -> characteristic */
  services?: Record<string, Record<string, FakeCharacteristicInit>>;
  /** GATT absent entirely (a classic-Bluetooth peripheral). */
  noGatt?: boolean;
  connectRejects?: string;
  connectDelayMs?: number;
}

/** Everything the fake was asked for, so a test can assert on the bound. */
export interface CallLog {
  uuids: string[];
  connects: number;
  disconnects: number;
  requestDevice: number;
}

export function domError(name: string): Error {
  const err = new Error(name);
  (err as { name: string }).name = name;
  return err;
}

export function later<T>(value: T, ms: number | undefined): Promise<T> {
  if (!ms) return Promise.resolve(value);
  return new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

export function makeCharacteristic(init: FakeCharacteristicInit, log: CallLog): BluetoothCharacteristicLike {
  const listeners: ((event: unknown) => void)[] = [];
  const characteristic: BluetoothCharacteristicLike = {
    properties: { read: init.read !== false, notify: init.notify === true },
    value: null,
    readValue: async (): Promise<DataView> => {
      if (init.readRejects) throw domError(init.readRejects);
      await later(null, init.readDelayMs);
      return init.value || bytes(0);
    },
    startNotifications: async (): Promise<unknown> => {
      if (typeof init.notifyAfterMs === "number") {
        setTimeout(() => {
          const view = init.value || bytes(0);
          characteristic.value = view;
          for (const l of listeners.slice()) l({ target: { value: view } });
        }, init.notifyAfterMs);
      }
      return characteristic;
    },
    stopNotifications: async (): Promise<unknown> => characteristic,
    addEventListener: (_type: string, listener: (event: unknown) => void): void => {
      listeners.push(listener);
    },
    removeEventListener: (_type: string, listener: (event: unknown) => void): void => {
      const i = listeners.indexOf(listener);
      if (i >= 0) listeners.splice(i, 1);
    },
  };
  void log;
  return characteristic;
}

export function makeDevice(init: FakeDeviceInit, log: CallLog): BluetoothDeviceLike {
  if (init.noGatt === true) return { id: init.id, name: init.name, gatt: null };
  let connected = init.connected === true;
  const services = init.services || {};
  return {
    id: init.id,
    name: init.name,
    gatt: {
      get connected(): boolean {
        return connected;
      },
      connect: async (): Promise<unknown> => {
        log.connects += 1;
        if (init.connectRejects) throw domError(init.connectRejects);
        await later(null, init.connectDelayMs);
        connected = true;
        return {};
      },
      disconnect: (): void => {
        log.disconnects += 1;
        connected = false;
      },
      getPrimaryService: async (uuid: string) => {
        log.uuids.push(uuid);
        const chars = services[uuid];
        if (!chars) throw domError("NotFoundError");
        return {
          getCharacteristic: async (cuuid: string): Promise<BluetoothCharacteristicLike> => {
            log.uuids.push(cuuid);
            const c = chars[cuuid];
            if (!c) throw domError("NotFoundError");
            return makeCharacteristic(c, log);
          },
        };
      },
    },
  };
}

export interface HarnessInit {
  devices?: FakeDeviceInit[];
  secure?: boolean;
  /** Omit navigator.bluetooth entirely (Firefox / Safari). */
  noApi?: boolean;
  /** Have the API but not getDevices. NOT an "older Chromium": per MDN's
   *  browser-compat-data this is what EVERY shipping Chromium looks like unless
   *  #enable-experimental-web-platform-features is on, so this flag is the
   *  DEFAULT-BROWSER case, not an exotic one. */
  noGetDevices?: boolean;
  /** Have the API but not requestDevice (no browser ships this; the module
   *  still has to say something truthful if one did). */
  noRequestDevice?: boolean;
  /** Devices the chooser will hand back, in order, on successive
   *  pairBluetoothDevice() calls. Built with the same fake GATT machinery as
   *  `devices`, so a paired handle is as readable as an enumerated one. */
  pairable?: FakeDeviceInit[];
  availability?: boolean;
  availabilityThrows?: boolean;
  getDevicesRejects?: string;
  /** requestDevice resolves with this; default is a throwing stub, because no
   *  TOOL is ever allowed to reach it. */
  requestDevice?: () => Promise<BluetoothDeviceLike>;
}

export function install(init: HarnessInit = {}): CallLog {
  const log: CallLog = { uuids: [], connects: 0, disconnects: 0, requestDevice: 0 };
  const devices = (init.devices || []).map((d) => makeDevice(d, log));
  const pairQueue = (init.pairable || []).map((d) => makeDevice(d, log));
  const api: BluetoothApiLike = {
    requestDevice: async (): Promise<BluetoothDeviceLike> => {
      log.requestDevice += 1;
      if (init.requestDevice) return init.requestDevice();
      const next = pairQueue.shift();
      if (next) return next;
      throw new Error("requestDevice must never be reached from a tool: pairing is user-gesture-bound");
    },
    getAvailability:
      typeof init.availability === "boolean" || init.availabilityThrows === true
        ? async (): Promise<boolean> => {
            if (init.availabilityThrows === true) throw domError("NotSupportedError");
            return init.availability === true;
          }
        : undefined,
  };
  if (init.noRequestDevice === true) delete api.requestDevice;
  if (init.noGetDevices !== true) {
    api.getDevices = async (): Promise<BluetoothDeviceLike[]> => {
      if (init.getDevicesRejects) throw domError(init.getDevicesRejects);
      return devices;
    };
  }
  const deps: DeviceToolDeps = {
    isSecureContext: (): boolean => init.secure !== false,
    getBluetooth: (): BluetoothApiLike | null => (init.noApi === true ? null : api),
    now: (): number => Date.now(),
  };
  __setDeviceToolDepsForTests(deps);
  return log;
}

/** Source with whole-line comments removed, so a property check can look at
 *  what the module DOES rather than at what it says about itself. */
export function codeLinesOf(source: string): string {
  return source
    .split("\n")
    .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
    .join("\n");
}

export function tool(name: string): LocalTool {
  const found = LOCAL_DEVICE_TOOLS.find((t) => t.name === name);
  if (!found) throw new Error(`no such device tool: ${name}`);
  return found;
}

/** Runs a tool through the REAL runLocalTool, so every assertion below is made
 *  against what the tier-1 loop would actually see, ceiling included. */
export async function call(name: string, args?: Record<string, unknown>): Promise<string> {
  const outcome = await runLocalTool(tool(name), args || {});
  if (outcome.ok === true) return outcome.result;
  // These handlers return refusals as strings by design (see the module
  // header), so a non-ok outcome here is a defect, and surfacing its detail is
  // what makes that defect readable in the failure message.
  return `[NOT-OK ${outcome.code}] ${outcome.detail}`;
}

export const HEART_RATE_BAND: FakeDeviceInit = {
  id: "band1",
  name: "Polar H10",
  connected: true,
  services: {
    [HEART_RATE_SERVICE]: {
      // Conformant: notify-only, no read. flags=0x06 (uint8, contact supported
      // + detected), bpm=72.
      [HEART_RATE_MEASUREMENT]: { read: false, notify: true, notifyAfterMs: 5, value: bytes(0x06, 72) },
    },
    [BATTERY_SERVICE]: { [BATTERY_LEVEL]: { value: bytes(88) } },
  },
};

/** Every source file of the device-tool module family, deviceTools.ts first.
 *  Read from the vitest root (web/) for the reason localTools.test.ts records:
 *  under jsdom import.meta.url is an http:// URL. A wrong path throws ENOENT, so
 *  this cannot silently scan nothing. */
export function deviceToolsFamilySources(): { name: string; text: string }[] {
  return ["deviceTools", "deviceToolsCore", "deviceToolsMetrics", "deviceToolsRead", "deviceToolsPair"].map((name) => ({
    name,
    text: readFileSync(join(process.cwd(), `app/lib/edge/${name}.ts`), "utf8"),
  }));
}
