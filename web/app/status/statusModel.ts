// Pure presentation rules for the public /status page. No React, no DOM.
//
// The page shows readings from GET /api/public/status, which answers only
// component names and status codes. The rules here decide how those codes are
// worded and when the headline banner may say "all systems operational". The
// one that matters: a component the server could not read ("UNKNOWN") is NOT
// healthy, so it must never be hidden under a green banner. "NOT_CONFIGURED"
// is different - the component is intentionally absent from this deployment.

export type ComponentStatus = { name: string; status: string };

export type BannerTone = "ok" | "warn" | "bad";

export type Banner = {
  title: string;
  tone: BannerTone;
  /** Components that are neither UP nor intentionally absent (only counted when the roll-up says UP). */
  notReporting: number;
};

// The keys are the names PublicStatusController emits. "agents-runtime" is the
// Python sidecar (embeddings and document extraction) - not the agent runtime,
// which is the API itself - and "disk" is the API host's disk space, so the
// labels say what is actually probed.
export const COMPONENT_LABELS: Record<string, string> = {
  api: "API",
  database: "Database",
  redis: "Cache (Redis)",
  events: "Event bus",
  disk: "Disk space (API host)",
  "agents-runtime": "Embeddings & document service",
};

export function componentLabel(name: string): string {
  return COMPONENT_LABELS[name] ?? name;
}

/** "NOT_CONFIGURED" -> "Not deployed", "OUT_OF_SERVICE" -> "Out of service", "UP" -> "Up". */
export function statusLabel(status: string): string {
  switch (status) {
    case "NOT_CONFIGURED":
      return "Not deployed";
    case "UNKNOWN":
      return "Unknown";
    default: {
      const words = status.replace(/_/g, " ").toLowerCase();
      return words.charAt(0).toUpperCase() + words.slice(1);
    }
  }
}

export type StatusIconKind = "up" | "down" | "off" | "warn";

export function statusIconKind(status: string): StatusIconKind {
  switch (status) {
    case "UP":
      return "up";
    case "DOWN":
      return "down";
    case "NOT_CONFIGURED":
      return "off";
    default:
      return "warn";
  }
}

/** True for a component that is neither healthy nor intentionally absent. */
export function isNotReporting(status: string): boolean {
  return status !== "UP" && status !== "NOT_CONFIGURED";
}

/**
 * The headline. `overall` is the server's roll-up (UP / DEGRADED / DOWN) or one
 * of the page's own pseudo-states (UNREACHABLE, CHECKING). A roll-up of UP is
 * only reported as "All systems operational" when every component is UP or
 * intentionally absent; otherwise the banner says how many are not reporting.
 */
export function describeBanner(overall: string, components: ComponentStatus[]): Banner {
  switch (overall) {
    case "UNREACHABLE":
      return { title: "Status unreachable", tone: "bad", notReporting: 0 };
    case "CHECKING":
      return { title: "Checking…", tone: "warn", notReporting: 0 };
    case "DOWN":
      return { title: "Major outage", tone: "bad", notReporting: 0 };
    case "DEGRADED":
      return { title: "Partial degradation", tone: "warn", notReporting: 0 };
    case "UP": {
      const n = components.filter((c) => isNotReporting(c.status)).length;
      if (n === 0) return { title: "All systems operational", tone: "ok", notReporting: 0 };
      return {
        title: `Operational, but ${n} ${n === 1 ? "check is" : "checks are"} not reporting`,
        tone: "warn",
        notReporting: n,
      };
    }
    default:
      return { title: "Status unknown", tone: "warn", notReporting: 0 };
  }
}
