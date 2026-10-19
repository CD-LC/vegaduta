// Wiring text -> schema objects (secrets, MCP servers, tools). Split out of blueprint.ts.
import type { BriefTool } from "../../components/brief/types";


const normSecret = (s: string) =>
  s.trim().toUpperCase().replace(/[^A-Z0-9]/g, "_").replace(/^_+|_+$/g, "").slice(0, 64);
export const SECRET_NAME_RE = /^[A-Z][A-Z0-9_]{2,63}$/;

/** "NAME - what it unlocks" per line. Names that miss the schema pattern are
 *  kept - validate() warns, and the apply engine reports them honestly. */
export function parseSecrets(text: string) {
  return text
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .slice(0, 10)
    .map((l, i) => {
      const [rawName, ...why] = l.split(/\s*-\s+/);
      return { ref: `s${i + 1}`, name: normSecret(rawName), scope: "TENANT" as const, why: why.join(" - ").trim() || "Declared in the blueprint." };
    });
}

export function parseMcps(text: string) {
  return text
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .slice(0, 6)
    .map((l, i) => {
      const [id, ...rest] = l.split(/\s+/);
      return { ref: `m${i + 1}`, registryId: id, name: rest.join(" ") || id };
    });
}

export function parseTools(text: string, secrets: { ref: string; name: string }[]): BriefTool[] {
  return text
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .slice(0, 8)
    .map((l, i) => {
      const url = l.match(/https?:\/\/\S+/)?.[0];
      const keyMatch = l.match(/key:\s*([A-Za-z0-9_]+)/i);
      const keySecret = keyMatch ? secrets.find((s) => s.name === normSecret(keyMatch[1])) : undefined;
      const name = l.split(/\s*-\s+|\s+https?:/)[0].trim().slice(0, 60) || `tool${i + 1}`;
      const base: BriefTool = {
        ref: `t${i + 1}`,
        kind: url ? "OPENAPI" : "CODE",
        name,
      };
      if (url) base.specUrl = url;
      else base.codeDescription = l.replace(/key:\s*[A-Za-z0-9_]+/i, "").trim();
      if (keySecret) base.auth = { type: "API_KEY_HEADER", secretRef: keySecret.ref };
      return base;
    });
}

