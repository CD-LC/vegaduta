// The LOCAL half of the Bluetooth device bridge.
//
// docs/BLUETOOTH-DEVICE-BRIDGE-DESIGN.md settles the architecture in its §1:
// the browser process owns the BLE radio, and no server process can see the
// person's Bluetooth adapter. That doc then designs a WebSocket relay so that a
// SERVER-side agent can borrow the browser's radio (§3.2, §3.3, §5).
//
// This module is the other half of that same fact, and the half that needs no
// server at all: when the model is ALREADY running in the page — tier 0's
// Prompt API or tier 1's WebLLM — the radio and the model are in the same
// process. There is nothing to relay. No WebSocket, no ticket, no BleDevice
// row, no tenant, no account. That is why these two tools exist here rather
// than waiting on core-3's piece: they are what an account-less person can have
// today, and they are unreachable from the server design above (this module has
// no WebSocket, no fetch, and is not imported by the bridge page).
//
// Everything the design doc decided that applies to the local half is honoured
// here and cited where it lands:
//   §2  Chromium-only, HTTPS-only, user-gesture-bound requestDevice(),
//       getDevices() for already-granted devices  → SUPPORT + PAIRING below
//   §4  device names and values are attacker-controlled text → sanitizeDeviceText
//   §4  no reading persistence (health data)      → PERSISTENCE note below
//   §5  values parsed browser-side against the SIG assigned numbers → PARSERS
//
// ...with ONE correction to the doc, stated here because this module had to be
// built around it. See the next section.
//
// ---------------------------------------------------------------------------
// READ THIS FIRST: getDevices() IS BEHIND A FLAG IN EVERY SHIPPING CHROMIUM
// ---------------------------------------------------------------------------
// The design doc (§2, "Per-origin, per-device permission") says the permission
// is "persisted by the browser. navigator.bluetooth.getDevices() returns
// previously-granted devices, so the bridge can auto-reconnect on page load
// without re-prompting", with no caveat. That is not true of any browser a
// person actually has. MDN's browser-compat-data for the Bluetooth API
// (api/Bluetooth.json on mdn/browser-compat-data main, fetched 2026-09-12) says:
//
//   requestDevice   chrome: version_added "56", no flags.  firefox/safari: false.
//   getAvailability chrome: version_added "78", no flags.  firefox/safari: false.
//   getDevices      chrome: version_added "85", flags: [{ type: "preference",
//                     name: "#enable-experimental-web-platform-features",
//                     value_to_set: "enabled" }]
//                   chrome_android / edge / opera / samsung: "mirror" (so the
//                     flag mirrors too).  firefox/safari: false.
//
// So on a default stable Chrome or Edge — the exact browser this whole surface
// targets — `navigator.bluetooth` exists, `requestDevice` works, and
// `getDevices` is simply not there. A module that reached devices ONLY through
// getDevices() would pair fine and then be unable to read anything, forever,
// on every browser in the world. An earlier draft of this file did exactly
// that, and told the person to update their browser — advice that cannot work,
// because no Chromium version ships getDevices() unflagged.
//
// Two consequences, both load-bearing below:
//
//   (a) PAIRING KEEPS THE HANDLE. pairBluetoothDevice() holds the
//       BluetoothDevice object requestDevice() returns, in SESSION_PAIRED, and
//       both tools read from that union'd with getDevices() when getDevices()
//       exists. requestDevice() needs no flag, so the ordinary path — click
//       pair in the Bluetooth panel, then ask the model to read it, a minute
//       later or twenty — works on a default Chrome. What does NOT work without
//       the flag is seeing devices paired in an EARLIER page load; the module
//       says so rather than reporting an empty list (NO_GET_DEVICES_DETAIL,
//       PARTIAL_ENUMERATION_NOTE).
//
//   (b) FEATURE DETECTION IS PER-API, NOT "bluetooth in navigator". The design
//       doc's suggested `"bluetooth" in navigator` probe is what produces the
//       failure above: it passes on the browser where the enumeration half is
//       missing. describeBluetoothSupport() checks the object and the secure
//       context; describeEnumerationSupport() checks getDevices specifically;
//       describePairingSupport() checks requestDevice specifically. Each caller
//       asks for the one it needs, and each gets a different sentence.
//
// ---------------------------------------------------------------------------
// THE FOUR PROPERTIES, AND HOW THIS MODULE HOLDS EACH
// ---------------------------------------------------------------------------
//
// 1. NO SPEND. This module's only import is `import type { LocalTool }`, which
//    TypeScript erases, so the emitted module imports nothing at runtime. There
//    is therefore no import path from here to toolBridge.ts, to
//    /api/edge/tools/execute, to the API client, or to anything holding a
//    bearer token. It has no notion of a tenant, a plan or a quota.
//    deviceTools.test.ts asserts this two ways: it reads this source and fails
//    on any value import or IP-network identifier, and it runs both handlers
//    with throwing stubs installed over fetch, XMLHttpRequest, WebSocket,
//    sendBeacon and EventSource.
//
// 2. NO SURPRISE DOWNLOAD. Nothing here references tier1.ts or
//    downloadManager.ts, by name or indirectly — same zero-runtime-import fact.
//    The structural weights guard (getTier1RunModel returning null unless the
//    weights are already cached) is untouched and unreferenced. The one
//    user-gesture-bound thing this module CAN open is the browser's own device
//    chooser, and a model cannot reach it: pairBluetoothDevice() is not a tool,
//    is not in LOCAL_DEVICE_TOOLS, and is not called by either handler — the
//    test installs a throwing requestDevice and drives every reachable tool
//    input past it.
//
// 3. NO SILENT NETWORK — and this is the property that made the two tools
//    declare DIFFERENT `network` values, which is worth being exact about
//    because the easy thing here is to write "none" twice and be wrong:
//
//      local_list_devices  → network: "none".
//        Its whole job is navigator.bluetooth.getDevices(), which per the Web
//        Bluetooth spec returns the devices this ORIGIN has already been
//        granted. It is a read of the browser's own permission store: it does
//        not scan, does not connect, and reaches no peer. Nothing is
//        transmitted, on IP or on the radio.
//
//      local_read_device   → network: { hosts: [BLUETOOTH_RADIO_PEER] }.
//        A GATT read transmits. Not to an internet host — this module contains
//        no fetch and no URL — but a read request does go out over the local
//        radio to the paired peripheral. Declaring "none" on that would be
//        exactly the kind of false claim this program keeps having to fix, so
//        it declares its peer instead. BLUETOOTH_RADIO_PEER is deliberately not
//        a resolvable hostname (see its comment): an allowlist that treats
//        `hosts` as DNS names fails it closed rather than passing it.
//
//      What CAN be transmitted is bounded by construction, not by review. The
//      only bytes this module ever puts on the radio are (a) a GATT connect and
//      (b) service/characteristic lookups whose UUIDs come from the frozen
//      BLE_METRICS table below. No tool argument becomes a UUID, no tool writes
//      a characteristic value, and there is no writeValue call in this file. So
//      no conversation text, no file content and no model output can leave
//      through here, whatever the model is persuaded to ask for. The test
//      proves the UUID half by fuzzing every reachable input and asserting the
//      set of UUIDs that reached the fake GATT is a subset of the table.
//
// 4. HONEST DEGRADATION. Bluetooth has more ways to be unavailable than
//    anything else on the edge surface, and each one gets its own sentence
//    rather than an empty list: no Web Bluetooth API at all (Firefox and Safari
//    have both declined it — design doc §2 — so this is permanent, not a
//    hiccup); an insecure context; an API without requestDevice(); an API
//    without getDevices(), named as the flag it actually is and NOT as an
//    out-of-date browser; a paired-in-this-tab list that is real but cannot be
//    complete because getDevices() is missing; an adapter that
//    is off or absent; nothing paired yet; a device that is out of range or
//    asleep; a device that does not carry the requested service; a
//    characteristic that is notify-only; a value too short to parse; and the
//    IEEE-11073 "not a number" the thermometer profile can legitimately report.
//    An empty device list means "nothing is paired", and NO_DEVICES_DETAIL says
//    so in those words — it never doubles as "I could not look", which is what
//    the availability probe in runListDevices exists to separate.
//
// ---------------------------------------------------------------------------
// PAIRING IS NOT A TOOL, AND CANNOT BE MADE ONE
// ---------------------------------------------------------------------------
// navigator.bluetooth.requestDevice() requires transient user activation
// (design doc §2). A model calling a tool mid-turn has none: the activation was
// consumed by whatever sent the message, and by the time a tool round runs the
// browser will reject the call. This is not a limitation to route around — it
// is the consent mechanism, and it is the reason a page can never enumerate or
// grab the person's devices. So the split is structural:
//
//   pairBluetoothDevice()   exported for a click handler in the UI. Not a tool.
//                           It is also where the device HANDLE enters this
//                           module — see SESSION_PAIRED — because on a default
//                           Chromium it is the only place a handle can come
//                           from at all.
//   connectPairedDevice()   exported for the UI too, and for the same reason
//                           spelled out on DEVICE_TOOL_BUDGET_MS: a first
//                           connect can take longer than a tool turn is allowed
//                           to last, so the UI is where it belongs.
//   the two tools           read what is already granted — the union of what
//                           this page paired (SESSION_PAIRED) and, where the
//                           browser has it, getDevices().
//
// Both refusals say this in the words the model will repeat, so the person is
// told "click the button" rather than being left with a tool that mysteriously
// does nothing.
//
// ---------------------------------------------------------------------------
// PERSISTENCE: NO READING IS EVER STORED (design doc §4)
// ---------------------------------------------------------------------------
// Heart rate is health data. This module stores no reading, anywhere: no
// localStorage, no sessionStorage, no IndexedDB, no value cache. A reading
// exists in the string a handler returns and nowhere else. The test asserts
// this by running both handlers with throwing stubs over localStorage,
// sessionStorage and indexedDB, and by grepping the module for those names.
//
// Be exact about what this module DOES hold, because it is not nothing and an
// earlier version of this comment claimed "no mutable module state except the
// injected-deps seam", which the code below now contradicts: SESSION_PAIRED
// holds BluetoothDevice OBJECTS — live browser handles, not data read from a
// device. Three things bound it and they are the reason it is acceptable:
//
//   * Nothing enters it except through pairBluetoothDevice(), i.e. only after
//     the person picked that device in the browser's own chooser. No tool can
//     add to it; no model can add to it.
//   * It is a plain in-memory array. Nothing is serialised, so nothing survives
//     a reload, a new tab, or the process — the browser's own permission store
//     is the only thing that persists a grant, which is precisely the thing
//     getDevices() would read and cannot here.
//   * It holds handles, never values. No battery percentage, heart rate or
//     temperature is ever written to it.
//
// The alternative was a module with no state that cannot work on any shipping
// browser. Stating what is held, and why, is the honest version of that trade.
//
// ---------------------------------------------------------------------------
// REFUSALS ARE RETURNED, NOT THROWN
// ---------------------------------------------------------------------------
// Same reason as localToolsFiles.ts: localTools.ts models a refusal as its
// private LocalToolRefusal class, which is not exported, so a thrown Error from
// here comes back as code "failed" — the label that tells the loop "a defect on
// our side, do not retry". For "Firefox has no Web Bluetooth" that label would
// be a lie. So every refusal below is a normal returned string, which reaches
// the model and through it the person.
//
// ---------------------------------------------------------------------------
// THE UNTRUSTED-DATA LABEL GOES FIRST, NOT LAST
// ---------------------------------------------------------------------------
// Every result that can contain device-controlled text opens with
// UNTRUSTED_NOTE and then a blank line. It used to be appended as a footer, and
// that was a real hole rather than a style preference: runLocalTool caps a
// result at MAX_RESULT_CHARS and replaces the TAIL with a character-count
// marker (localTools.ts capResult, read 2026-09-12: `text.slice(0, keep)` +
// a truncation marker, keep = MAX_RESULT_CHARS - 80). A footer is in the tail.
//
// Measured, not argued — the case is reachable with this module's OWN declared
// bounds: MAX_DEVICES_LISTED (12) devices each with a name at
// MAX_DEVICE_NAME_CHARS (48) and an id at MAX_DEVICE_ID_CHARS (64) produce a
// list longer than the 2000-char cap, and the footer was the part cut. The
// worst case for the label is exactly the case that pushes it off: a hostile
// peripheral padding its name to the cap. A safety label that the transport can
// delete is not a safety label. A header cannot be truncated while any of the
// result it labels survives, so that is where it lives. The test asserts this
// against the real runLocalTool at those bounds, and asserts the raw result
// really did exceed the cap, so it cannot pass by the case having gone away.

//
// The module family: deviceToolsCore.ts (support probes, sanitizer, session
// registry), deviceToolsMetrics.ts (the frozen BLE table and parsers),
// deviceToolsRead.ts (deadlines, device lookup, GATT reads) and
// deviceToolsPair.ts (the UI entry points). This file owns the two tools.

import type { LocalTool } from "./localTools";
import {
  DEVICE_TOOL_BUDGET_MS,
  CONNECT_BUDGET_MS,
  MAX_DEVICES_LISTED,
  BLUETOOTH_RADIO_PEER,
  type BluetoothCharacteristicLike,
  type BluetoothGattLike,
  PARTIAL_ENUMERATION_NOTE,
  ADAPTER_UNAVAILABLE_DETAIL,
  NO_DEVICES_DETAIL,
  NO_GATT_DETAIL,
  describeBluetoothSupport,
  sanitizeDeviceText,
  UNTRUSTED_NOTE,
  aboutDevice,
} from "./deviceToolsCore";
export {
  DEVICE_TOOL_BUDGET_MS,
  CONNECT_BUDGET_MS,
  NOTIFY_WAIT_MS,
  MAX_DEVICES_LISTED,
  MAX_DEVICE_NAME_CHARS,
  MAX_DEVICE_ID_CHARS,
  BLUETOOTH_RADIO_PEER,
  UNSUPPORTED_BROWSER_DETAIL,
  INSECURE_CONTEXT_DETAIL,
  NO_GET_DEVICES_DETAIL,
  PARTIAL_ENUMERATION_NOTE,
  NO_REQUEST_DEVICE_DETAIL,
  ADAPTER_UNAVAILABLE_DETAIL,
  NO_DEVICES_DETAIL,
  NO_GATT_DETAIL,
  __setDeviceToolDepsForTests,
  describeBluetoothSupport,
  describeEnumerationSupport,
  describePairingSupport,
  MAX_SESSION_PAIRED,
  sessionPairedCount,
  sanitizeDeviceText,
  UNTRUSTED_NOTE,
} from "./deviceToolsCore";
export type {
  BluetoothCharacteristicLike,
  BluetoothServiceLike,
  BluetoothGattLike,
  BluetoothDeviceLike,
  BluetoothApiLike,
  DeviceToolDeps,
  BluetoothSupport,
  EnumerationSupport,
  PairingSupport,
} from "./deviceToolsCore";
import { BLE_METRICS, findMetric } from "./deviceToolsMetrics";
export { BLE_METRICS, BLE_SERVICE_UUIDS, BLE_ALLOWED_UUIDS } from "./deviceToolsMetrics";
export type { BleMetric } from "./deviceToolsMetrics";
export { pairBluetoothDevice, connectPairedDevice, type PairResult, type ConnectResult } from "./deviceToolsPair";
import {
  DeviceRefusal,
  refuse,
  startDeadline,
  withDeadline,
  errorName,
  collectKnownDevices,
  resolveDevice,
  readCharacteristic,
} from "./deviceToolsRead";

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

function requireStringArg(args: Record<string, unknown>, key: string): string {
  const raw = args[key];
  if (typeof raw !== "string" || raw.trim() === "") {
    refuse(`"${key}" must be a non-empty string. Call local_list_devices first to see what is paired.`);
  }
  return raw;
}

async function runListDevices(): Promise<string> {
  try {
    const support = describeBluetoothSupport();
    if (support.ok === false) return support.detail;
    const deadline = startDeadline(DEVICE_TOOL_BUDGET_MS);
    const known = await collectKnownDevices(deadline, support.bluetooth);
    const listed = known.devices;

    if (listed.length === 0) {
      // THE distinction this whole branch exists for: an empty list is an
      // answer only if we know we were able to look. getAvailability() reports
      // whether the machine has a usable adapter at all, so when it says no,
      // the honest result is "I could not look" — not "nothing is paired".
      if (typeof support.bluetooth.getAvailability === "function") {
        try {
          const available = await withDeadline(
            Promise.resolve(support.bluetooth.getAvailability()),
            deadline,
            deadline.remaining(),
            ""
          );
          if (available === false) return ADAPTER_UNAVAILABLE_DETAIL;
        } catch {
          // A browser that has the method and fails it tells us nothing either
          // way, so fall through to the paired-devices answer, which is the
          // claim we actually verified.
        }
      }
      return NO_DEVICES_DETAIL;
    }

    const shown = listed.slice(0, MAX_DEVICES_LISTED);
    // The label is line one, before any device text, because everything after
    // it is truncatable — see THE UNTRUSTED-DATA LABEL GOES FIRST.
    const lines = [
      UNTRUSTED_NOTE,
      "",
      `${listed.length} Bluetooth device${listed.length === 1 ? " is" : "s are"} ` +
        (known.enumerated ? "paired with this page " : "paired with this page from this tab ") +
        "(the person granted each one through the browser's own chooser):",
      "",
    ];
    for (const d of shown) {
      lines.push(`- "${d.name}" — ${d.connected ? "connected" : "not connected"} — id ${d.id || "(none reported)"}`);
    }
    if (listed.length > shown.length) {
      lines.push(`- …and ${listed.length - shown.length} more, not listed here.`);
    }
    lines.push("");
    if (known.enumerated === false) lines.push(PARTIAL_ENUMERATION_NOTE);
    lines.push(
      "To read one, call local_read_device with that name or id and a metric of battery, heart_rate or temperature. " +
        "Whether a device actually has a given metric is only known once it is read."
    );
    return lines.join("\n");
  } catch (err) {
    if (err instanceof DeviceRefusal) return err.message;
    return `I could not read the list of paired Bluetooth devices${errorName(err) ? ` (${errorName(err)})` : ""}, so I do not know what is paired.`;
  }
}

async function runReadDevice(args: Record<string, unknown>): Promise<string> {
  let openedByUs: BluetoothGattLike | null = null;
  try {
    const support = describeBluetoothSupport();
    if (support.ok === false) return support.detail;

    const wanted = requireStringArg(args, "device");
    const metricName = sanitizeDeviceText(requireStringArg(args, "metric"), 32)
      .toLowerCase()
      .replace(/[\s-]+/g, "_");
    const spec = findMetric(metricName);
    if (spec === null) {
      refuse(
        `"${metricName}" is not a metric I can read. Supported: ${BLE_METRICS.map((m) => m.metric).join(", ")}. ` +
          "Only these standard Bluetooth profiles are readable from here — there is deliberately no way to ask for an arbitrary characteristic."
      );
    }

    const deadline = startDeadline(DEVICE_TOOL_BUDGET_MS);
    const known = await collectKnownDevices(deadline, support.bluetooth);
    const target = resolveDevice(known.devices, wanted);

    const gatt = target.device.gatt;
    if (!gatt || typeof gatt.connect !== "function" || typeof gatt.getPrimaryService !== "function") {
      refuse(NO_GATT_DETAIL);
    }

    if (gatt.connected !== true) {
      try {
        await withDeadline(
          Promise.resolve(gatt.connect()),
          deadline,
          CONNECT_BUDGET_MS,
          aboutDevice(
            `"${target.name}" did not connect within ${CONNECT_BUDGET_MS}ms. It is probably out of range, switched off, or asleep. ` +
              "A first connect can take longer than a tool call is allowed to run — connecting it from the Bluetooth panel first, then asking me again, works."
          )
        );
      } catch (err) {
        if (err instanceof DeviceRefusal) throw err;
        refuse(
          aboutDevice(
            `"${target.name}" refused the connection${errorName(err) ? ` (${errorName(err)})` : ""}. ` +
              "A Bluetooth device usually allows only one connection at a time, so another app or another tab may already hold it."
          )
        );
      }
      // We opened it, so we close it — see the finally below.
      openedByUs = gatt;
    }

    // Try each source profile for this metric in table order. A device that
    // lacks the service throws NotFoundError; that is a normal miss, not a
    // failure, so it moves to the next candidate and only the exhausted list
    // produces a refusal.
    const tried: string[] = [];
    for (const source of spec.sources) {
      tried.push(source.label);
      let characteristic: BluetoothCharacteristicLike;
      try {
        const service = await withDeadline(
          Promise.resolve(gatt.getPrimaryService(source.service)),
          deadline,
          deadline.remaining(),
          aboutDevice(`"${target.name}" did not answer in time while looking for its ${source.label}.`)
        );
        characteristic = await withDeadline(
          Promise.resolve(service.getCharacteristic(source.characteristic)),
          deadline,
          deadline.remaining(),
          aboutDevice(`"${target.name}" did not answer in time while opening its ${source.label}.`)
        );
      } catch (err) {
        if (err instanceof DeviceRefusal) throw err;
        continue; // this profile is absent on this device; try the next
      }

      const view = await readCharacteristic(characteristic, deadline, `"${target.name}"'s ${source.label}`);
      const parsed = source.parse(view);
      if (parsed.ok === false) {
        return aboutDevice(`"${target.name}" answered, but ${parsed.why}. Nothing was stored.`);
      }
      return aboutDevice(
        `"${target.name}" — ${parsed.text}\n` +
          `Read live over Bluetooth from the device just now, from its ${source.label}. No copy was kept.`
      );
    }

    return refuse(
      aboutDevice(
        `"${target.name}" is paired and reachable but does not expose ${spec.metric.replace(/_/g, " ")} over a standard profile ` +
          `(looked for: ${tried.join(", ")}). Many devices keep values like this behind a vendor-specific service, which this tool deliberately cannot read.`
      )
    );
  } catch (err) {
    if (err instanceof DeviceRefusal) return err.message;
    return `I could not read that Bluetooth device${errorName(err) ? ` (${errorName(err)})` : ""}. Nothing was stored.`;
  } finally {
    if (openedByUs !== null && typeof openedByUs.disconnect === "function") {
      // Symmetry, stated as a rule so it is predictable: a connection this tool
      // opened, this tool closes. A connection the person opened from the panel
      // is left alone — dropping it would break whatever they opened it for.
      try {
        openedByUs.disconnect();
      } catch {
        /* nothing useful to do; the read has already answered */
      }
    }
  }
}

// ---------------------------------------------------------------------------
// The tools
// ---------------------------------------------------------------------------

// NO CAPABILITY GATE, deliberately. localTools.ts has a CAPABILITY_GATES table
// that could hide both tools on Firefox and Safari and save their schemas out
// of every system prompt there. The design doc already settled this for its own
// ble_* tools in §3.3 — "tools always registered, 'no connected Bluetooth
// device' comes back as a tool RESULT" — and the same reasoning holds harder
// here: a hidden tool cannot tell anyone why it is missing. On Firefox the
// person asking "what's my strap's battery?" should be told that this browser
// has no Web Bluetooth and never will, which is UNSUPPORTED_BROWSER_DETAIL's
// whole job. A gate would trade that sentence for silence.
export const LOCAL_DEVICE_TOOLS: LocalTool[] = [
  {
    name: "local_list_devices",
    description:
      "List the Bluetooth devices the person already paired with this page and whether each is connected. Reads what this page paired plus the browser's permission list where the browser exposes it: it cannot open the pairing chooser and contacts no device.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
    // See property 3 in the header: getDevices() is a permission-store read. It
    // does not scan, connect, or reach any peer.
    network: "none",
    consequential: false,
    run: async (): Promise<string> => runListDevices(),
  },
  {
    name: "local_read_device",
    description:
      "Read one standard value — battery, heart_rate or temperature — from a Bluetooth device the person already paired. SENDS a read request over the local Bluetooth radio to that device; no internet, no server, nothing stored.",
    inputSchema: {
      type: "object",
      properties: {
        device: { type: "string", description: "Name or id from local_list_devices" },
        metric: {
          type: "string",
          enum: ["battery", "heart_rate", "temperature"],
          description: "Which standard reading",
        },
      },
      required: ["device", "metric"],
      additionalProperties: false,
    },
    network: { hosts: [BLUETOOTH_RADIO_PEER] },
    // consequential:true, and the reasoning is worth keeping because the field's
    // own comment in localTools.ts says "writes to disk, clipboard or storage",
    // which this does not do. It is true anyway for a different, real side
    // effect: a GATT connection is exclusive on most peripherals, so opening one
    // can take the device away from another app or tab that was using it, and it
    // keeps the device's radio awake. That is a change to the person's hardware
    // made on a model's say-so.
    //
    // WHAT THIS BUYS TODAY: nothing, and the previous version of this comment
    // implied otherwise ("`consequential` is the only hook the tool loop has to
    // gate it"). Measured 2026-09-12: grep across web/app for a read of
    // `.consequential` outside tests returns zero hits — tier1.ts, tierRouter.ts
    // and toolBridge.ts never look at it, and localTools.ts's own comment says
    // the field "exists because phase 2 will not be, and the loop needs
    // somewhere to hang an approval gate". So the exclusive-connection side
    // effect happens today with no approval step anywhere. The declaration is
    // still the right one to make — it is correct, and it is what a gate would
    // read when one exists — but it is a declaration, not a control.
    consequential: true,
    run: runReadDevice,
  },
];

// ---------------------------------------------------------------------------
// REGISTRY MERGE (applied by hand, NOT from this file)
//
// localTools.ts owns the advertised array and several tracks would collide on
// it this wave, so the one-line edit belongs in the merge:
//
//   import { LOCAL_DEVICE_TOOLS } from "./deviceTools";
//   ...
//   const tools = [...LOCAL_TOOLS, ...LOCAL_FILE_TOOLS, ...LOCAL_DEVICE_TOOLS];
//
// It does NOT land green on its own. localTools.test.ts currently asserts, of
// every advertised tool, that `network === "none"` and `consequential === false`
// (and enumerates the exact name list and its length). local_read_device is
// honestly neither — see its comment above. The exact assertion edits are in
// this change's needsOtherFiles; this note is here so whoever applies the merge
// finds out from the file rather than from a red suite. Note for whoever does
// it: two of those edits WEAKEN an existing exfiltration-default assertion by
// name-exempting local_read_device. That is defensible (this module has no
// fetch and no way to put text on the radio — the UUID bound above is the
// proof) but it should be done knowingly, not as a green-the-suite reflex.
//
// SEPARATELY, and outside this track's scope:
// docs/BLUETOOTH-DEVICE-BRIDGE-DESIGN.md §2 says getDevices() "returns
// previously-granted devices, so the bridge can auto-reconnect on page load
// without re-prompting", with no flag caveat, and tells the bridge page to
// feature-detect with `"bluetooth" in navigator`. Both are wrong in the way
// this module's header documents, and the SERVER-side bridge inherits the same
// bug: a bridge page that relies on getDevices() to auto-reconnect will not
// reconnect on a default Chromium either. That doc needs the correction.
// ---------------------------------------------------------------------------
