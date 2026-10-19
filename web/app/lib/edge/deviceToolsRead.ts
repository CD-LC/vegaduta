// Deadline plumbing, device enumeration and the characteristic read path.
// Split out of deviceTools.ts; see that file's header for the guarantees.
import {
  NOTIFY_WAIT_MS,
  MAX_DEVICES_LISTED,
  MAX_DEVICE_NAME_CHARS,
  MAX_DEVICE_ID_CHARS,
  type BluetoothCharacteristicLike,
  type BluetoothDeviceLike,
  type BluetoothApiLike,
  NO_DEVICES_DETAIL,
  getDeps,
  pairedHandles,
  describeEnumerationSupport,
  sanitizeDeviceText,
  sanitizeDeviceId,
  aboutDevice,
} from "./deviceToolsCore";


// ---------------------------------------------------------------------------
// Deadline plumbing
// ---------------------------------------------------------------------------

/** An expected "can't", carrying the sentence the person should see. Never
 *  escapes a handler — see REFUSALS ARE RETURNED, NOT THROWN. */
export class DeviceRefusal extends Error {}

export function refuse(message: string): never {
  throw new DeviceRefusal(message);
}

export interface Deadline {
  /** Milliseconds left, never negative. */
  remaining(): number;
}

export function startDeadline(budgetMs: number): Deadline {
  const start = getDeps().now();
  return {
    remaining: (): number => Math.max(0, budgetMs - (getDeps().now() - start)),
  };
}

/**
 * Races a promise against a slice of the remaining budget.
 *
 * HONEST LIMITATION, the same one runLocalTool documents about itself: this
 * bounds the WAIT, not the work. Web Bluetooth's connect(), getPrimaryService()
 * and readValue() take no AbortSignal in the API this module targets, so a
 * request that has already gone out to the radio keeps going after the wait is
 * abandoned. What the timeout guarantees is that the person gets a named answer
 * on time — not that the radio went quiet. The one cleanup that IS possible,
 * dropping a connection this module opened, is done in runReadDevice's finally.
 */
export async function withDeadline<T>(work: Promise<T>, deadline: Deadline, capMs: number, whatFailed: string): Promise<T> {
  const budget = Math.min(capMs, deadline.remaining());
  if (budget <= 0) refuse(whatFailed);
  const TIMED_OUT = Symbol("ble-timeout");
  let timer: ReturnType<typeof setTimeout> | null = null;
  try {
    const timeout = new Promise<typeof TIMED_OUT>((resolve) => {
      timer = setTimeout(() => resolve(TIMED_OUT), budget);
    });
    const outcome = await Promise.race([work, timeout]);
    if (outcome === TIMED_OUT) refuse(whatFailed);
    return outcome as T;
  } finally {
    if (timer !== null) clearTimeout(timer);
  }
}

/** Web Bluetooth rejections are DOMExceptions whose `name` is the useful part;
 *  the message is often empty or browser-specific. */
export function errorName(err: unknown): string {
  if (err && typeof err === "object" && typeof (err as { name?: unknown }).name === "string") {
    return (err as { name: string }).name;
  }
  return "";
}

// ---------------------------------------------------------------------------
// Device resolution
// ---------------------------------------------------------------------------

export interface ListedDevice {
  device: BluetoothDeviceLike;
  id: string;
  name: string;
  connected: boolean;
}

/**
 * What a lookup found, and — the part that matters — whether the lookup could
 * have been complete.
 *
 * `enumerated: false` means getDevices() was unavailable or failed, so this
 * list is exactly the handles this page paired itself and nothing more. Every
 * caller that prints a count has to carry that distinction into what it says,
 * which is what PARTIAL_ENUMERATION_NOTE is for. Collapsing the two into a bare
 * array is how "3 devices are paired" gets asserted about a browser that was
 * never asked.
 */
export interface KnownDevices {
  devices: ListedDevice[];
  enumerated: boolean;
}

export function describeDevices(devices: BluetoothDeviceLike[]): ListedDevice[] {
  const listed: ListedDevice[] = [];
  for (const device of devices) {
    if (!device || typeof device !== "object") continue;
    listed.push({
      device,
      id: sanitizeDeviceId(device.id),
      name: sanitizeDeviceText(device.name, MAX_DEVICE_NAME_CHARS) || "(no name)",
      connected: device.gatt !== null && device.gatt !== undefined && device.gatt.connected === true,
    });
  }
  return listed;
}

/**
 * Every device this page can reach right now: getDevices() where the browser
 * has it, UNION the handles pairBluetoothDevice() kept.
 *
 * The union is not belt-and-braces. On a default Chromium the first source does
 * not exist and the second is the only one; with the experimental-web-platform
 * flag on, the first is a superset of the second for devices paired in earlier
 * visits but the second still carries this visit's handles, and the same device
 * can legitimately appear in both. Dedupe is by object identity first (the same
 * BluetoothDevice really is the same device) and then by raw id.
 *
 * A getDevices() that is present but FAILS is treated like one that is absent —
 * downgraded to the session handles with enumerated:false — rather than
 * refusing outright, EXCEPT when there is nothing to fall back to: with an
 * empty session registry the failure is all we know, so it is reported as the
 * failure it is. "I could not look" must never be dressed up as "nothing is
 * paired", in either direction.
 */
export async function collectKnownDevices(deadline: Deadline, bt: BluetoothApiLike): Promise<KnownDevices> {
  const session = pairedHandles().slice();
  const enumeration = describeEnumerationSupport(bt);

  if (enumeration.ok === false) {
    if (session.length === 0) refuse(enumeration.detail);
    return { devices: describeDevices(session), enumerated: false };
  }

  let raw: BluetoothDeviceLike[];
  try {
    raw = await withDeadline(
      Promise.resolve(enumeration.getDevices()),
      deadline,
      deadline.remaining(),
      "The browser did not answer with its list of paired Bluetooth devices in time. Trying again usually works."
    );
    if (!Array.isArray(raw)) {
      refuse("The browser returned something other than a list of devices, so I could not read what is paired.");
    }
  } catch (err) {
    if (session.length > 0) {
      // The handles are still real and still readable; only the completeness
      // claim is lost.
      return { devices: describeDevices(session), enumerated: false };
    }
    if (err instanceof DeviceRefusal) throw err;
    if (errorName(err) === "SecurityError") {
      refuse(
        "The browser refused to list paired Bluetooth devices for this page — a permissions policy is blocking Bluetooth here " +
          "(this happens when the app is embedded in a frame that was not granted it). I could not look; this is not 'no devices'."
      );
    }
    refuse(
      `The browser failed to list paired Bluetooth devices${errorName(err) ? ` (${errorName(err)})` : ""}. ` +
        "I could not look, so I cannot tell you whether anything is paired."
    );
  }

  const merged = raw.slice();
  for (const held of session) {
    const alreadyThere = merged.some(
      (d) =>
        d === held ||
        (typeof held.id === "string" && held.id !== "" && d !== null && typeof d === "object" && d.id === held.id)
    );
    if (!alreadyThere) merged.push(held);
  }
  return { devices: describeDevices(merged), enumerated: true };
}

/**
 * Matches the model's `device` argument against the already-granted list.
 * Exact id first, then exact name, then a case-insensitive name match —
 * and never a substring match, because "read the battery on 'watch'" picking
 * one of two watches silently is precisely the confident-wrong-answer failure
 * the rest of this module is written to avoid. An ambiguous name refuses and
 * asks for the id.
 */
export function resolveDevice(listed: ListedDevice[], wanted: string): ListedDevice {
  // Capped at the LARGER of the two bounds on purpose: this one string is
  // matched against both names (capped at MAX_DEVICE_NAME_CHARS) and ids
  // (MAX_DEVICE_ID_CHARS). Capping it at the name bound would silently truncate
  // an id longer than that and then report "no paired device matches" for a
  // device sitting in the list — the exact confident-wrong-answer this function
  // exists to prevent.
  const needle = sanitizeDeviceText(wanted, Math.max(MAX_DEVICE_NAME_CHARS, MAX_DEVICE_ID_CHARS));
  if (!needle) {
    refuse('The "device" argument was empty. Use local_list_devices first and pass one of the names or ids it returns.');
  }
  const byId = listed.filter((d) => d.id === needle);
  if (byId.length === 1) return byId[0];

  const exact = listed.filter((d) => d.name === needle);
  if (exact.length === 1) return exact[0];
  if (exact.length > 1) {
    refuse(
      aboutDevice(
        `${exact.length} paired devices are called "${needle}", so I will not guess which one you mean. ` +
          "Pass the id instead — local_list_devices lists them."
      )
    );
  }

  const lower = needle.toLowerCase();
  const insensitive = listed.filter((d) => d.name.toLowerCase() === lower);
  if (insensitive.length === 1) return insensitive[0];
  if (insensitive.length > 1) {
    refuse(
      aboutDevice(
        `${insensitive.length} paired devices have that name apart from capitalisation, so I will not guess. Pass the id instead.`
      )
    );
  }

  const names = listed.slice(0, MAX_DEVICES_LISTED).map((d) => `"${d.name}"`).join(", ");
  refuse(
    aboutDevice(
      `No paired device matches "${needle}". ` +
        (listed.length === 0
          ? NO_DEVICES_DETAIL
          : `What I can see paired: ${names}${listed.length > MAX_DEVICES_LISTED ? ", and more" : ""}.`)
    )
  );
}

// ---------------------------------------------------------------------------
// Reading a characteristic
// ---------------------------------------------------------------------------

/**
 * Read, or take exactly one notification.
 *
 * The notify path is not an optimisation — it is the only way heart rate works.
 * The Heart Rate Measurement characteristic (0x2A37) is defined by the SIG as
 * Notify, and a conformant strap does not support Read at all, so a read-only
 * implementation would ship a heart-rate tool that fails on conformant
 * hardware. So: read when the characteristic says it can be read, otherwise
 * subscribe, take the FIRST value, and unsubscribe. One value, nothing kept —
 * this module holds no stream and no cache (see PERSISTENCE).
 */
export async function readCharacteristic(
  characteristic: BluetoothCharacteristicLike,
  deadline: Deadline,
  label: string
): Promise<DataView> {
  const props = characteristic.properties;
  const canRead = typeof characteristic.readValue === "function" && (!props || props.read !== false);
  if (canRead && typeof characteristic.readValue === "function") {
    try {
      return await withDeadline(
        Promise.resolve(characteristic.readValue()),
        deadline,
        deadline.remaining(),
        aboutDevice(`${label} did not answer the read in time. The device may have gone out of range or back to sleep.`)
      );
    } catch (err) {
      if (err instanceof DeviceRefusal) throw err;
      // NotSupportedError here means the characteristic advertised read and
      // then refused it — fall through to the notify path rather than give up.
      if (errorName(err) !== "NotSupportedError") {
        refuse(aboutDevice(`${label} refused the read${errorName(err) ? ` (${errorName(err)})` : ""}.`));
      }
    }
  }

  const canNotify =
    typeof characteristic.startNotifications === "function" &&
    typeof characteristic.addEventListener === "function" &&
    (!props || props.notify !== false);
  if (!canNotify) {
    refuse(
      aboutDevice(
        `${label} on this device supports neither a plain read nor notifications, so there is no standard way to get a value from it.`
      )
    );
  }

  let listener: ((event: unknown) => void) | null = null;
  try {
    const first = new Promise<DataView>((resolve) => {
      listener = (event: unknown): void => {
        const fromEvent =
          event && typeof event === "object"
            ? ((event as { target?: { value?: DataView | null } }).target || {}).value
            : null;
        const value = fromEvent || characteristic.value;
        if (value) resolve(value);
      };
      if (typeof characteristic.addEventListener === "function") {
        characteristic.addEventListener("characteristicvaluechanged", listener);
      }
    });
    // Subscribe AFTER the listener is attached, so a device that pushes
    // immediately on subscribe cannot deliver its only value into the gap.
    await withDeadline(
      Promise.resolve((characteristic.startNotifications as () => Promise<unknown>)()),
      deadline,
      deadline.remaining(),
      aboutDevice(`${label} did not accept a subscription in time. The device may be out of range or asleep.`)
    );
    return await withDeadline(
      first,
      deadline,
      NOTIFY_WAIT_MS,
      aboutDevice(
        `${label} is notify-only and sent no update within ${NOTIFY_WAIT_MS}ms. ` +
          "Devices like heart-rate straps only send a value when they have one — if it is not being worn, or has just woken up, there is nothing to send yet."
      )
    );
  } finally {
    // Best effort both ways: leaving a subscription running would keep the
    // radio busy and the device's battery draining for a tool call that has
    // already returned.
    try {
      if (listener !== null && typeof characteristic.removeEventListener === "function") {
        characteristic.removeEventListener("characteristicvaluechanged", listener);
      }
    } catch {
      /* a double-removal is not worth failing a completed read over */
    }
    try {
      if (typeof characteristic.stopNotifications === "function") {
        void Promise.resolve(characteristic.stopNotifications()).catch((): undefined => undefined);
      }
    } catch {
      /* same */
    }
  }
}

