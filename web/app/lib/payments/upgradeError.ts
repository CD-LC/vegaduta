// Turns whatever the upgrade call threw into a headline plus one plain sentence, so the dialog never shows a raw
// browser string ("Load failed") or a bare "Something went wrong". Pure, so every branch is unit-tested.

export type UpgradeErrorView = {
  title: string;
  detail: string;
  /** Pressing the plan button again can plausibly succeed. */
  retryable: boolean;
};

const has = (text: string, ...needles: string[]) => needles.some((n) => text.includes(n));

export function describeUpgradeError(err: unknown): UpgradeErrorView {
  const e = (err ?? {}) as { message?: unknown; status?: unknown };
  const message = typeof e.message === "string" ? e.message.trim() : "";
  const status = typeof e.status === "number" ? e.status : null;
  const m = message.toLowerCase();

  if (status === 401) {
    return { title: "Please sign in again", detail: "Your session has expired. Sign in and choose the plan again.", retryable: false };
  }
  if (status === 403) {
    return { title: "Only an owner or admin can change the plan", detail: "Ask a workspace owner or admin to upgrade, or to raise your role.", retryable: false };
  }
  if (has(m, "load failed", "failed to fetch", "networkerror", "network error", "unreachable") || status === 0) {
    return {
      title: "We could not reach the payment service",
      detail: "The request did not get through, so you have not been charged. Check your connection and try again in a moment.",
      retryable: true,
    };
  }
  if (status === 502 || status === 503 || status === 504 || has(m, "timed out", "timeout", "temporarily unavailable")) {
    return { title: "The payment service is busy", detail: "Nothing was charged. Wait a few seconds and try again.", retryable: true };
  }
  if (has(m, "no razorpay plan", "no plan id", "not configured")) {
    return { title: "This plan is not available in that currency yet", detail: "Switch the currency to INR, or contact support to enable it. You have not been charged.", retryable: false };
  }
  if (message && !has(m, "something went wrong")) {
    return { title: "We could not start the upgrade", detail: message, retryable: status == null || status >= 500 };
  }
  return {
    title: "We could not start the upgrade",
    detail: "Something went wrong on our side and you have not been charged. Please try again, and contact support if it keeps happening.",
    retryable: true,
  };
}
