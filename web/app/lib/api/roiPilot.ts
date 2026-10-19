import { apiFetch, jsonBody, toJson, triggerBrowserDownload } from "./core";
import { roiQuery, type Money, type RoiRange } from "./roi";

// Pilot kit (core/app common/roi, RoiController): the tenant-entered baseline, the pilot report and the demo marker.
// Reads need VIEW_USAGE, saving a baseline needs MANAGE_BILLING. Every baseline number is typed by the tenant; the
// platform never fills one in. Not spread into the `api` barrel: only the ROI page reads this.

export const JOB_TYPES = ["MISSED_CALL_RECOVERY", "APPOINTMENT_REMINDERS", "PAYMENT_FOLLOW_UP", "LEAD_FOLLOW_UP"] as const;
export type JobType = (typeof JOB_TYPES)[number];

export interface BaselineDto {
  agentId: string;
  jobType: string | null;
  baselinePeriod: string | null;
  monthlyVolume: Money;
  avgHandlingMinutes: Money;
  /** Fraction 0..1 (0.25 = 25%). */
  missedRate: Money;
  avgValuePerConversion: Money;
  valueCurrency: string | null;
  enteredBy: string | null;
  enteredAt: string | null;
  /** Always "tenant-entered". */
  source: string;
}

export interface BaselineRequestDto {
  jobType: string | null;
  baselinePeriod: string | null;
  monthlyVolume: number | null;
  avgHandlingMinutes: number | null;
  missedRate: number | null;
  avgValuePerConversion: number | null;
  valueCurrency: string | null;
}

export interface DemoStatusDto {
  demo: boolean;
  label: string | null;
}

const BASE = "/api/tenant/roi";
const agentPath = (agentId: string) => `${BASE}/agents/${encodeURIComponent(agentId)}`;

export type ReportFormat = "html" | "pdf";

export function reportUrl(agentId: string, format: ReportFormat, range: RoiRange = {}): string {
  const query = roiQuery(range);
  return `${agentPath(agentId)}/pilot-report${query ? `${query}&format=${format}` : `?format=${format}`}`;
}

export const roiPilotApi = {
  getBaseline: (agentId: string): Promise<BaselineDto> => apiFetch(`${agentPath(agentId)}/baseline`).then(toJson<BaselineDto>),
  setBaseline: (agentId: string, body: BaselineRequestDto): Promise<BaselineDto> =>
    apiFetch(`${agentPath(agentId)}/baseline`, { method: "PUT", ...jsonBody(body) }).then(toJson<BaselineDto>),
  getDemo: (): Promise<DemoStatusDto> => apiFetch(`${BASE}/demo`).then(toJson<DemoStatusDto>),
  /** Fetches the report with the session's credentials and hands it to the browser as a file (PDF) or a new tab (HTML). */
  openReport: async (agentId: string, format: ReportFormat, range?: RoiRange): Promise<void> => {
    const res = await apiFetch(reportUrl(agentId, format, range));
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    if (format === "pdf") {
      const name = /filename="([^"]+)"/.exec(res.headers.get("Content-Disposition") ?? "")?.[1] ?? "pilot-report.pdf";
      triggerBrowserDownload(blob, name);
      return;
    }
    const url = URL.createObjectURL(new Blob([blob], { type: "text/html" }));
    window.open(url, "_blank", "noopener");
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  },
};
