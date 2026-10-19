// local_convert: unit tables and conversion. Split out of ../localTools.ts.
// Pure computation: runtime imports are limited to sibling pure modules.



// ---------------------------------------------------------------------------
// local_convert
//
// Small and honest beats broad and approximate. Every factor below is exact by
// definition (the imperial units are defined against the metre and the
// kilogram, not measured), so a conversion here is a rounding question, never a
// correctness one. Volume, area, energy, speed and currency are ABSENT on
// purpose: currency needs a live rate this tool may not fetch, and the rest are
// the units where "cup", "ton" and "gallon" mean different things in different
// places - a tool that picked one silently would be wrong for half its users.
// ---------------------------------------------------------------------------

export interface UnitFamily {
  name: string;
  base: string;
  units: Record<string, number>;
}

export const UNIT_FAMILIES: UnitFamily[] = [
  {
    name: "length",
    base: "m",
    units: { m: 1, km: 1000, cm: 0.01, mm: 0.001, um: 1e-6, mi: 1609.344, yd: 0.9144, ft: 0.3048, in: 0.0254, nmi: 1852 },
  },
  {
    name: "mass",
    base: "g",
    units: { g: 1, kg: 1000, mg: 0.001, t: 1e6, lb: 453.59237, oz: 28.349523125, st: 6350.29318 },
  },
  {
    name: "data",
    base: "byte",
    units: {
      byte: 1,
      bit: 0.125,
      kb: 1e3,
      mb: 1e6,
      gb: 1e9,
      tb: 1e12,
      kib: 1024,
      mib: 1048576,
      gib: 1073741824,
      tib: 1099511627776,
    },
  },
  {
    name: "duration",
    base: "s",
    units: { s: 1, ms: 0.001, min: 60, h: 3600, d: 86400, wk: 604800 },
  },
];

export const TEMPERATURE_UNITS = ["c", "f", "k"];

/** Curated, exact aliases — a lookup table, never fuzzy matching. Two omissions
 * are deliberate: bare "b" (byte in storage, bit in networking) and "ton" (US
 * short, UK long and metric all differ). Both refuse and say what to write. */
export const UNIT_ALIASES: Record<string, string> = {
  // length
  meter: "m", meters: "m", metre: "m", metres: "m",
  kilometer: "km", kilometers: "km", kilometre: "km", kilometres: "km",
  centimeter: "cm", centimeters: "cm", centimetre: "cm", centimetres: "cm",
  millimeter: "mm", millimeters: "mm", millimetre: "mm", millimetres: "mm",
  micrometer: "um", micrometre: "um", micron: "um",
  mile: "mi", miles: "mi",
  yard: "yd", yards: "yd",
  foot: "ft", feet: "ft",
  inch: "in", inches: "in",
  nauticalmile: "nmi", nauticalmiles: "nmi",
  // mass
  gram: "g", grams: "g", gramme: "g", grammes: "g",
  kilogram: "kg", kilograms: "kg", kilo: "kg", kilos: "kg",
  milligram: "mg", milligrams: "mg",
  tonne: "t", tonnes: "t", metricton: "t", metrictonne: "t",
  pound: "lb", pounds: "lb", lbs: "lb",
  ounce: "oz", ounces: "oz",
  stone: "st", stones: "st",
  // data
  bytes: "byte", bits: "bit",
  kilobyte: "kb", kilobytes: "kb",
  megabyte: "mb", megabytes: "mb",
  gigabyte: "gb", gigabytes: "gb",
  terabyte: "tb", terabytes: "tb",
  kibibyte: "kib", kibibytes: "kib",
  mebibyte: "mib", mebibytes: "mib",
  gibibyte: "gib", gibibytes: "gib",
  tebibyte: "tib", tebibytes: "tib",
  // duration
  millisecond: "ms", milliseconds: "ms", msec: "ms",
  second: "s", seconds: "s", sec: "s", secs: "s",
  minute: "min", minutes: "min", mins: "min",
  hour: "h", hours: "h", hr: "h", hrs: "h",
  day: "d", days: "d",
  week: "wk", weeks: "wk",
  // temperature
  celsius: "c", centigrade: "c",
  fahrenheit: "f",
  kelvin: "k",
};

export function normalizeUnit(raw: string): string {
  const cleaned = raw.trim().toLowerCase().replace(/°/g, "");
  return UNIT_ALIASES[cleaned] ?? cleaned;
}

export function familyOf(unit: string): UnitFamily | null {
  for (const family of UNIT_FAMILIES) {
    if (unit in family.units) return family;
  }
  return null;
}

export function supportedUnitsMessage(): string {
  const lines = UNIT_FAMILIES.map((f) => `${f.name}: ${Object.keys(f.units).join(", ")}`);
  lines.push(`temperature: ${TEMPERATURE_UNITS.join(", ")}`);
  return lines.join("; ");
}

export function toCelsius(value: number, unit: string): number {
  if (unit === "c") return value;
  if (unit === "f") return (value - 32) * (5 / 9);
  return value - 273.15;
}

export function fromCelsius(value: number, unit: string): number {
  if (unit === "c") return value;
  if (unit === "f") return value * (9 / 5) + 32;
  return value + 273.15;
}

