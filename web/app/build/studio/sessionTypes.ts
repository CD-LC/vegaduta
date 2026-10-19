/** Shared types and small pure helpers of the studio workspace (split out of StudioSurface). */
import { timeAgo as sharedTimeAgo, type TimeAgoOptions } from "../../lib/format";
import { type EdgeStatus } from "../../lib/build/edgeDraft";
import { type CloudRoute, type DeviceEngineInfo, type EngineKind } from "./engine";
import { type StudioConnection, type VersionSource } from "./store";
import { aliasOf } from "./payload";

export type EnginePref = "auto" | EngineKind;
export type Device = "phone" | "tablet" | "desktop";
export type SidePanel = "design" | "element" | "share" | "connect" | "code" | "check" | "data" | "team";

export interface LogEntry {
  id: number;
  kind: "user" | "result" | "error";
  text: string;
  engine?: EngineKind | null;
  model?: string | null;
  ms?: number;
  /** Set on errors when a cloud retry could still land the change - renders
   * as a one-click "use the cloud for this" action on the log entry. */
  cloudRetry?: { req: string; target?: string | null };
}


export const DEVICE_SIZES: Record<Device, { w: number; h: number }> = {
  phone: { w: 390, h: 780 },
  tablet: { w: 640, h: 860 },
  desktop: { w: 1024, h: 700 },
};

export const STUDIO_TIME_AGO: TimeAgoOptions = {
  rounding: "round",
  maxDays: 0,
  labels: { justNow: "just now", minutes: (n) => `${n} min ago`, hours: (n) => `${n} h ago` },
};
export const timeAgo = (at: number): string => sharedTimeAgo(at, STUDIO_TIME_AGO);

/** Appended to the retry turn when the model's first reply wasn't usable
 * edit blocks or a valid document - small on-device models regularly need
 * the format restated this bluntly on the second ask. */
export const FORMAT_REMINDER =
  "Your last reply was not in the required format. Reply ONLY with edit blocks, each exactly: <<<<<<< SEARCH, the existing lines copied verbatim, =======, the new lines, >>>>>>> REPLACE. No commentary, no markdown fences.";

export const SOURCE_LABEL: Record<VersionSource, string> = {
  template: "Starter",
  device: "On-device AI",
  cloud: "Cloud AI",
  design: "Design",
  manual: "Code edit",
  restore: "Restored",
  connect: "Connected",
  publish: "Published",
};

export interface EngineState {
  device: DeviceEngineInfo | null;
  deviceStatus: EdgeStatus | null;
  cloud: CloudRoute | null;
  pref: EnginePref;
}

export interface PlannedEngine {
  kind: EngineKind | "none";
  /** One line, e.g. "On your device · WebLLM · WebGPU · Qwen2.5-1.5B". */
  label: string;
  cost: string;
}

/** What the NEXT request will run on, given the preference and what is
 * actually available - the same order runModel() tries them in. */
export function plannedEngine(e: EngineState, prefer: EnginePref = e.pref): PlannedEngine {
  const cloudLabel = e.cloud?.label || "Cloud AI";
  const cloudCost = e.cloud?.kind === "byok" ? "billed by your provider" : "uses your AI tokens";
  if (prefer !== "cloud" && e.device) {
    return { kind: "device", label: `On your device · ${e.device.runtime} · ${e.device.modelName}`, cost: "free · private" };
  }
  if (prefer === "device") return { kind: "none", label: "On your device (not set up)", cost: "" };
  return { kind: "cloud", label: `Cloud · ${cloudLabel}`, cost: cloudCost };
}

/** Give every connection a unique alias (the vd.call() name AND the server-side
 * call path). Existing aliases win; missing ones are slugified from the name. */
export function withAliases(list: StudioConnection[]): StudioConnection[] {
  const taken = new Set<string>(list.map((c) => c.alias).filter(Boolean) as string[]);
  return list.map((c) => {
    if (c.alias) return c;
    const alias = aliasOf(c.name, taken);
    taken.add(alias);
    return { ...c, alias };
  });
}
