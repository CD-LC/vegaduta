// Track 8: the Bluetooth UI entry points (split out of deviceTools.ts, which
// re-exports them). See deviceTools.ts's header for why pairing is not a tool
// and why pairBluetoothDevice() keeps the device handle (SESSION_PAIRED).

import {
  DEVICE_TOOL_BUDGET_MS,
  MAX_DEVICE_NAME_CHARS,
  NO_GATT_DETAIL,
  describeBluetoothSupport,
  describePairingSupport,
  rememberPairedDevice,
  sanitizeDeviceText,
  sanitizeDeviceId,
} from "./deviceToolsCore";
import { BLE_SERVICE_UUIDS } from "./deviceToolsMetrics";
import { DeviceRefusal, startDeadline, errorName, collectKnownDevices, resolveDevice } from "./deviceToolsRead";

// ---------------------------------------------------------------------------
// UI entry points — NOT tools
//
// Both exist because the browser requires them to run outside a tool round, and
// both are deliberately absent from LOCAL_DEVICE_TOOLS. Neither handler above
// calls either one; the test proves it by installing a throwing requestDevice
// and driving every reachable tool input past it.
// ---------------------------------------------------------------------------

export type PairResult =
  | {
      ok: true;
      id: string;
      name: string;
      /** True when this module is now holding the device handle, i.e. the tools
       *  can read it for the life of this page. False would mean the pair
       *  succeeded but produced nothing readable; it is returned rather than
       *  assumed so the panel can say which happened. */
      readable: boolean;
    }
  | { ok: false; detail: string };

/**
 * Opens the browser's own device chooser, and KEEPS the device it returns.
 *
 * Keeping it is the fix for the defect that made this whole track inert: an
 * earlier version returned only { ok, id, name } and dropped the
 * BluetoothDevice on the floor. Since getDevices() is flag-gated in every
 * shipping Chromium (module header), that left the reading side with no way to
 * obtain a device at all — the person clicked pair, the chooser worked, the
 * grant was made, and the very next tool call said the browser had no
 * getDevices(). The handle goes into SESSION_PAIRED here, which is what makes
 * "pair now, ask the model to read it a minute later" work: the registry is
 * module-level and outlives any single tool round, so a later call finds it.
 *
 * MUST be called synchronously from a real user gesture (a click handler);
 * called anywhere else the browser rejects it with NotAllowedError, which is
 * reported here as such rather than as a generic failure.
 *
 * `optionalServices` is the complete BLE_SERVICE_UUIDS list because Web
 * Bluetooth grants access per-service: a service not named at pairing time
 * cannot be read later, no matter what permission the device has. Nothing
 * outside the table is requested, so pairing cannot widen what the tools can
 * reach beyond the three metrics.
 *
 * acceptAllDevices is used rather than a service filter on purpose: filtering
 * would hide from the chooser every device that does not advertise one of these
 * services in its advertisement packet — which many devices that DO have them
 * do not — and the person would conclude their device is unsupported. The
 * chooser is theirs to choose from; the reading side is where the narrowing
 * belongs.
 */
export async function pairBluetoothDevice(): Promise<PairResult> {
  // Feature-detected on requestDevice specifically, not on the bluetooth object
  // — and NOT on getDevices, which is a different capability this function does
  // not need and which is absent on the browsers where pairing works fine.
  const pairing = describePairingSupport();
  if (pairing.ok === false) return { ok: false, detail: pairing.detail };
  try {
    const device = await pairing.requestDevice({
      acceptAllDevices: true,
      optionalServices: [...BLE_SERVICE_UUIDS],
    });
    if (!device || typeof device !== "object") {
      return {
        ok: false,
        detail: "The browser's chooser returned something that is not a device, so there is nothing to pair with.",
      };
    }
    rememberPairedDevice(device);
    return {
      ok: true,
      id: sanitizeDeviceId(device.id),
      name: sanitizeDeviceText(device.name, MAX_DEVICE_NAME_CHARS) || "(no name)",
      readable: true,
    };
  } catch (err) {
    const name = errorName(err);
    if (name === "NotFoundError") {
      // The spec uses NotFoundError both for "no device chosen" and "no device
      // found". From the person's seat the common one is the cancel.
      return {
        ok: false,
        detail: "No device was picked — the chooser was dismissed, or nothing nearby was advertising.",
      };
    }
    if (name === "NotAllowedError") {
      return {
        ok: false,
        detail:
          "The browser would not open the Bluetooth chooser: it only opens it directly from a click or tap. " +
          "Press the pair button again.",
      };
    }
    if (name === "SecurityError") {
      return {
        ok: false,
        detail: "A permissions policy is blocking Bluetooth on this page, so the chooser cannot open here.",
      };
    }
    return { ok: false, detail: `The Bluetooth chooser could not be opened${name ? ` (${name})` : ""}.` };
  }
}

export type ConnectResult = { ok: true } | { ok: false; detail: string };

/**
 * Connects an already-paired device, for the Bluetooth panel to call from a
 * button. Unbudgeted on purpose: this is the slow step (see
 * DEVICE_TOOL_BUDGET_MS), and a button press can wait where a tool round
 * cannot. Takes an id or a name so the panel can use whatever it is holding.
 */
export async function connectPairedDevice(deviceIdOrName: string): Promise<ConnectResult> {
  const support = describeBluetoothSupport();
  if (support.ok === false) return { ok: false, detail: support.detail };
  try {
    // A generous deadline only so a wedged getDevices() cannot hang the button
    // forever; the connect itself below is not raced.
    const known = await collectKnownDevices(startDeadline(DEVICE_TOOL_BUDGET_MS), support.bluetooth);
    const target = resolveDevice(known.devices, deviceIdOrName);
    const gatt = target.device.gatt;
    if (!gatt || typeof gatt.connect !== "function") return { ok: false, detail: NO_GATT_DETAIL };
    if (gatt.connected === true) return { ok: true };
    await gatt.connect();
    return { ok: true };
  } catch (err) {
    if (err instanceof DeviceRefusal) return { ok: false, detail: err.message };
    return {
      ok: false,
      detail: `That device did not connect${errorName(err) ? ` (${errorName(err)})` : ""}. It may be out of range, switched off, or already connected to something else.`,
    };
  }
}
