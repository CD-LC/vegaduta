// The fixed metric table (the ONLY UUIDs the device tools can reach) and the
// Bluetooth SIG profile parsers. Split out of deviceTools.ts; see that file's
// header. Self-contained: no imports.



// ---------------------------------------------------------------------------
// The metric table — the ONLY UUIDs this module can ever reach
//
// Standard Bluetooth SIG assigned numbers only, written as the canonical
// 128-bit form Web Bluetooth normalises the 16-bit aliases to. A generic
// "read any characteristic you like" tool was considered and rejected: in a
// model's hands it is a pointable primitive at whatever the person happened to
// pair, and the browser's GATT blocklist (design doc §2) is a floor, not a
// policy. A fixed table is also what makes property 3's bound provable — the
// test fuzzes tool arguments and asserts nothing outside this table ever
// reaches getPrimaryService/getCharacteristic.
//
// Frozen at module load so a later bug (or a later contributor) cannot widen it
// at runtime; the test asserts a push to it throws.
// ---------------------------------------------------------------------------

export type BleMetric = "battery" | "heart_rate" | "temperature";

export interface MetricSource {
  /** Bluetooth SIG service UUID. */
  service: string;
  /** Bluetooth SIG characteristic UUID. */
  characteristic: string;
  /** Human name used in refusals, so "this device has no X" names something
   *  the person can look up. */
  label: string;
  parse: (view: DataView) => ParsedReading;
}

export interface MetricSpec {
  /** What the tool calls it. */
  metric: BleMetric;
  /** Tried in order; the first service the device actually exposes wins. */
  sources: readonly MetricSource[];
}

/** A parsed reading, or the profile's own "I have no number for you". */
export type ParsedReading = { ok: true; text: string } | { ok: false; why: string };

function sig(short: string): string {
  return `0000${short}-0000-1000-8000-00805f9b34fb`;
}

export const BLE_METRICS: readonly MetricSpec[] = Object.freeze([
  Object.freeze({
    metric: "battery" as const,
    sources: Object.freeze([
      Object.freeze({
        service: sig("180f"), // Battery Service
        characteristic: sig("2a19"), // Battery Level
        label: "Battery Service",
        parse: parseBatteryLevel,
      }),
    ]),
  }),
  Object.freeze({
    metric: "heart_rate" as const,
    sources: Object.freeze([
      Object.freeze({
        service: sig("180d"), // Heart Rate
        characteristic: sig("2a37"), // Heart Rate Measurement
        label: "Heart Rate Service",
        parse: parseHeartRateMeasurement,
      }),
    ]),
  }),
  Object.freeze({
    metric: "temperature" as const,
    // Two profiles carry a temperature and devices pick one or the other: a
    // clinical thermometer ships Health Thermometer, an ambient sensor ships
    // Environmental Sensing. Trying both is why "temperature" is one metric to
    // the model instead of two it would have to guess between.
    sources: Object.freeze([
      Object.freeze({
        service: sig("1809"), // Health Thermometer
        characteristic: sig("2a1c"), // Temperature Measurement
        label: "Health Thermometer Service",
        parse: parseTemperatureMeasurement,
      }),
      Object.freeze({
        service: sig("181a"), // Environmental Sensing
        characteristic: sig("2a6e"), // Temperature
        label: "Environmental Sensing Service",
        parse: parseEnvironmentalTemperature,
      }),
    ]),
  }),
]);

/** Every service UUID in the table, for pairBluetoothDevice's optionalServices
 *  and for the test's subset assertion. */
export const BLE_SERVICE_UUIDS: readonly string[] = Object.freeze(
  BLE_METRICS.flatMap((spec) => spec.sources.map((s) => s.service))
);

/** Every UUID the module may ever pass to GATT — services and characteristics
 *  together. Exported so the test can assert the fuzzed call log is a subset. */
export const BLE_ALLOWED_UUIDS: readonly string[] = Object.freeze(
  BLE_METRICS.flatMap((spec) => spec.sources.flatMap((s) => [s.service, s.characteristic]))
);

export function findMetric(name: string): MetricSpec | null {
  for (const spec of BLE_METRICS) {
    if (spec.metric === name) return spec;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Parsers — Bluetooth SIG profiles, parsed in the page (design doc §5)
// ---------------------------------------------------------------------------

/** Battery Level 0x2A19: one uint8, a percentage. */
function parseBatteryLevel(view: DataView): ParsedReading {
  if (view.byteLength < 1) return { ok: false, why: "the device sent an empty battery value" };
  const percent = view.getUint8(0);
  if (percent > 100) {
    // The profile says 0-100. Reporting the raw number and saying it is out of
    // range beats either clamping it (inventing data) or refusing (hiding a
    // real answer from a slightly non-conformant device).
    return { ok: true, text: `Battery: ${percent} (the profile defines 0-100, so this device is reporting out of range)` };
  }
  return { ok: true, text: `Battery: ${percent}%` };
}

/** Heart Rate Measurement 0x2A37: a flags byte, then the rate as uint8 or
 *  uint16 depending on flag bit 0. Bits 1-2 carry sensor contact. */
function parseHeartRateMeasurement(view: DataView): ParsedReading {
  if (view.byteLength < 2) return { ok: false, why: "the device sent a heart-rate packet too short to contain a value" };
  const flags = view.getUint8(0);
  const sixteenBit = (flags & 0x01) === 0x01;
  if (sixteenBit && view.byteLength < 3) {
    return { ok: false, why: "the device flagged a 16-bit heart rate but sent only one byte for it" };
  }
  const bpm = sixteenBit ? view.getUint16(1, true) : view.getUint8(1);
  const contactSupported = (flags & 0x04) === 0x04;
  const contactDetected = (flags & 0x02) === 0x02;
  let text = `Heart rate: ${bpm} bpm`;
  if (contactSupported && !contactDetected) {
    // Worth surfacing: a strap that has lost skin contact reports a number
    // anyway, and it is meaningless. Silence here would hand the model a
    // confident wrong reading.
    text += " (the sensor reports it is NOT in contact with skin, so this number is unreliable)";
  }
  return { ok: true, text };
}

/**
 * IEEE-11073 32-bit FLOAT, little-endian: a signed 24-bit mantissa and a signed
 * 8-bit base-10 exponent. The profile defines five reserved patterns at
 * exponent 0 that are NOT numbers, and a thermometer legitimately sends NaN
 * when it has no reading — turning that into a number would be the worst
 * possible failure for a value someone might act on.
 */
function parseSfloat32(view: DataView, offset: number): { ok: true; value: number } | { ok: false; why: string } {
  const raw = view.getUint32(offset, true);
  const rawMantissa = raw & 0x00ffffff;
  let exponent = raw >>> 24;
  if (exponent > 127) exponent -= 256;
  if (exponent === 0) {
    if (rawMantissa === 0x007fffff) return { ok: false, why: "the device reported 'not a number' — it has no valid reading right now" };
    if (rawMantissa === 0x00800000) return { ok: false, why: "the device reported 'not at this resolution' rather than a value" };
    if (rawMantissa === 0x007ffffe) return { ok: false, why: "the device reported a value above the range it can measure" };
    if (rawMantissa === 0x00800002) return { ok: false, why: "the device reported a value below the range it can measure" };
    if (rawMantissa === 0x00800001) return { ok: false, why: "the device sent a reserved value, not a reading" };
  }
  const mantissa = (rawMantissa & 0x00800000) === 0x00800000 ? rawMantissa - 0x01000000 : rawMantissa;
  return { ok: true, value: mantissa * Math.pow(10, exponent) };
}

/** Temperature Measurement 0x2A1C: flags byte (bit 0 = Fahrenheit), then an
 *  IEEE-11073 FLOAT. Later optional fields (timestamp, type) are ignored. */
function parseTemperatureMeasurement(view: DataView): ParsedReading {
  if (view.byteLength < 5) return { ok: false, why: "the device sent a temperature packet too short to contain a value" };
  const flags = view.getUint8(0);
  const fahrenheit = (flags & 0x01) === 0x01;
  const parsed = parseSfloat32(view, 1);
  if (parsed.ok === false) return { ok: false, why: parsed.why };
  return { ok: true, text: `Temperature: ${formatNumber(parsed.value)} ${fahrenheit ? "°F" : "°C"}` };
}

/** Environmental Sensing Temperature 0x2A6E: sint16, hundredths of a degree
 *  Celsius. No flags, no alternate unit. */
function parseEnvironmentalTemperature(view: DataView): ParsedReading {
  if (view.byteLength < 2) return { ok: false, why: "the device sent a temperature packet too short to contain a value" };
  return { ok: true, text: `Temperature: ${formatNumber(view.getInt16(0, true) / 100)} °C` };
}

/** Trims float noise without lying about magnitude — same approach
 *  localTools.ts takes with its own formatNumber, restated here rather than
 *  imported so this module keeps its zero-runtime-import property. */
export function formatNumber(value: number): string {
  if (!Number.isFinite(value)) return String(value);
  if (Number.isInteger(value)) return String(value);
  return String(Number.parseFloat(value.toPrecision(10)));
}

