// The pure-compute LocalTool registry entries (calculate, datetime, convert, transform). Split out of ../localTools.ts.
// Pure computation: runtime imports are limited to sibling pure modules.

import type { LocalTool } from "../localTools";
import { MAX_EXPRESSION_CHARS, MAX_TEXT_CHARS, formatNumber, refuse, requireString } from "./common";
import { evaluateExpression } from "./calc";
import { assertKnownTimeZone, formatInZone, localTimeZone, shiftInstant } from "./datetime";
import { TEMPERATURE_UNITS, type UnitFamily, familyOf, fromCelsius, normalizeUnit, supportedUnitsMessage, toCelsius } from "./units";
import { TRANSFORM_OPS, base64Decode, base64Encode, parseJsonOrRefuse, readJsonPath, splitLines } from "./transform";

// ---------------------------------------------------------------------------
// The registry
//
// Schemas are DELIBERATELY SMALL. Every advertised tool's whole schema is
// pasted into the tier-1 system prompt, out of the same ~2744-token budget the
// conversation lives in (see MAX_RESULT_CHARS), so a friendly paragraph of
// per-property prose here is paid for by the person's context window. One line
// each, no titles, no $schema, no examples block. localTools.test.ts asserts
// the serialised total stays under its cap.
// ---------------------------------------------------------------------------

export const COMPUTE_LOCAL_TOOLS: LocalTool[] = [
  {
    name: "local_calculate",
    description: "Evaluate an arithmetic expression exactly, on this device.",
    inputSchema: {
      type: "object",
      properties: {
        expression: { type: "string", description: "e.g. (2+3)*4^2, sqrt(9)/2, max(3,7)%4; pi and e are known" },
      },
      required: ["expression"],
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const expression = requireString(args, "expression", MAX_EXPRESSION_CHARS);
      const trimmed = expression.trim();
      if (!trimmed) refuse("The expression is empty.");
      return `${trimmed} = ${formatNumber(evaluateExpression(trimmed))}`;
    },
  },
  {
    name: "local_datetime",
    description: "The current date and time, optionally in a time zone and shifted by an offset.",
    inputSchema: {
      type: "object",
      properties: {
        timezone: { type: "string", description: 'IANA zone id, e.g. "Asia/Kolkata"; default: this device\'s zone' },
        offset: { type: "number", description: "Signed shift; omit for now" },
        unit: { type: "string", enum: ["minutes", "hours", "days", "weeks"], description: "Default days" },
      },
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const rawZone = args.timezone;
      if (rawZone !== undefined && rawZone !== null && typeof rawZone !== "string") {
        refuse('"timezone" must be a string IANA zone id.');
      }
      const zone = typeof rawZone === "string" && rawZone.trim() ? rawZone.trim() : localTimeZone();
      assertKnownTimeZone(zone);
      const nowMs = Date.now();
      const targetMs = shiftInstant(nowMs, args);
      const body = formatInZone(new Date(targetMs), zone);
      return targetMs === nowMs ? body : `${body}\n(shifted from now by exact elapsed time, not calendar months)`;
    },
  },
  {
    name: "local_convert",
    description: "Convert a value between units of length, mass, temperature, data size or duration.",
    inputSchema: {
      type: "object",
      properties: {
        value: { type: "number", description: "The amount to convert" },
        from: { type: "string", description: 'Source unit, e.g. "km", "lb", "celsius", "gib", "min"' },
        to: { type: "string", description: "Target unit, same family" },
      },
      required: ["value", "from", "to"],
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const value = args.value;
      if (typeof value !== "number" || !Number.isFinite(value)) {
        refuse('"value" must be a finite number.');
      }
      const fromRaw = requireString(args, "from", 40);
      const toRaw = requireString(args, "to", 40);
      const from = normalizeUnit(fromRaw);
      const to = normalizeUnit(toRaw);

      const fromIsTemp = TEMPERATURE_UNITS.includes(from);
      const toIsTemp = TEMPERATURE_UNITS.includes(to);
      if (fromIsTemp && toIsTemp) {
        const converted = fromCelsius(toCelsius(value, from), to);
        return `${formatNumber(value)} ${from.toUpperCase()} = ${formatNumber(converted)} ${to.toUpperCase()}`;
      }

      const fromFamily = fromIsTemp ? null : familyOf(from);
      const toFamily = toIsTemp ? null : familyOf(to);
      if (!fromIsTemp && !fromFamily) {
        refuse(`"${fromRaw}" is not a unit this tool knows. Supported — ${supportedUnitsMessage()}.`);
      }
      if (!toIsTemp && !toFamily) {
        refuse(`"${toRaw}" is not a unit this tool knows. Supported — ${supportedUnitsMessage()}.`);
      }
      if (fromIsTemp !== toIsTemp || (fromFamily && toFamily && fromFamily.name !== toFamily.name)) {
        const fromName = fromIsTemp ? "temperature" : fromFamily ? fromFamily.name : "?";
        const toName = toIsTemp ? "temperature" : toFamily ? toFamily.name : "?";
        refuse(`Cannot convert ${fromName} (${from}) to ${toName} (${to}) — they measure different things.`);
      }
      // Both in the same non-temperature family by here.
      const family = fromFamily as UnitFamily;
      const converted = (value * family.units[from]) / family.units[to];
      const note = family.name === "data" ? "  [kb/mb/gb are decimal (1000); kib/mib/gib are binary (1024)]" : "";
      return `${formatNumber(value)} ${from} = ${formatNumber(converted)} ${to}${note}`;
    },
  },
  {
    name: "local_transform",
    description: "Count, sort, dedupe, base64 or query JSON in a block of text, exactly.",
    inputSchema: {
      type: "object",
      properties: {
        op: { type: "string", enum: [...TRANSFORM_OPS] },
        text: { type: "string", description: "Input text; JSON text for json_path/json_pretty" },
        path: { type: "string", description: 'json_path only, e.g. "user.addresses[0].city"' },
        descending: { type: "boolean", description: "sort_lines only" },
      },
      required: ["op", "text"],
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: async (args: Record<string, unknown>): Promise<string> => {
      const op = args.op;
      if (typeof op !== "string" || !(TRANSFORM_OPS as readonly string[]).includes(op)) {
        refuse(`"op" must be one of: ${TRANSFORM_OPS.join(", ")}.`);
      }
      const text = requireString(args, "text", MAX_TEXT_CHARS);

      if (op === "count") {
        // Both character definitions are reported because they disagree on
        // emoji and CJK, and "how many characters" has no single right answer.
        const codePoints = Array.from(text).length;
        const words = text.trim() ? text.trim().split(/\s+/).length : 0;
        const bytes = typeof TextEncoder === "undefined" ? null : new TextEncoder().encode(text).length;
        return [
          `words: ${words}`,
          `characters (Unicode code points): ${codePoints}`,
          `characters (UTF-16 units, JS .length): ${text.length}`,
          `lines: ${splitLines(text).length}`,
          bytes === null ? "bytes (UTF-8): unavailable in this browser" : `bytes (UTF-8): ${bytes}`,
        ].join("\n");
      }

      if (op === "sort_lines") {
        const descending = args.descending === true;
        // localeCompare, not code-unit order: "Zebra" before "apple" is the
        // machine's answer, not the reader's.
        const sorted = splitLines(text).sort((a, b) => a.localeCompare(b));
        if (descending) sorted.reverse();
        return sorted.join("\n");
      }

      if (op === "dedupe_lines") {
        const lines = splitLines(text);
        const seen = new Set<string>();
        const kept: string[] = [];
        for (const line of lines) {
          if (seen.has(line)) continue;
          seen.add(line);
          kept.push(line);
        }
        return `${kept.join("\n")}\n[${lines.length} lines in, ${kept.length} unique, ${lines.length - kept.length} removed]`;
      }

      if (op === "json_pretty") {
        return JSON.stringify(parseJsonOrRefuse(text), null, 2);
      }

      if (op === "json_path") {
        const path = requireString(args, "path", 200);
        if (!path.trim()) refuse('"path" is required for json_path, e.g. "user.name".');
        const found = readJsonPath(parseJsonOrRefuse(text), path.trim());
        if (found === undefined) return `${path.trim()} = undefined`;
        return `${path.trim()} = ${typeof found === "string" ? found : JSON.stringify(found, null, 2)}`;
      }

      if (op === "base64_encode") return base64Encode(text);
      return base64Decode(text);
    },
  },
];
