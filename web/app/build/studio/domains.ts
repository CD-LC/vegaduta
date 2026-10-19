// Pure helpers for the custom-domains panel (mirrors core StudioAppDomainRules).

export type DomainStatus = "PENDING" | "VERIFIED" | "FAILED";

export interface DnsInstructions {
  txtName: string;
  txtValue: string;
  cnameTarget: string | null;
  aTarget: string | null;
}

export interface DomainView {
  id: string;
  hostname: string;
  status: DomainStatus;
  lastError: string | null;
  dns: DnsInstructions;
}

export interface DnsRecordRow {
  type: "TXT" | "CNAME" | "A";
  name: string;
  value: string;
  purpose: string;
}

const LABEL = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const RESERVED = ["localhost", "local", "internal", "test", "example", "invalid", "onion"];

/** Client-side mirror of the server rules; returns a normalised host or an error message. */
export function checkHostname(raw: string): { host: string } | { error: string } {
  let h = (raw || "").trim().toLowerCase();
  if (h.endsWith(".")) h = h.slice(0, -1);
  if (!h) return { error: "Enter a domain, e.g. app.yourcompany.com" };
  if (h.includes("*")) return { error: "Wildcard domains are not supported." };
  if (h.length > 128) return { error: "That domain is too long." };
  const labels = h.split(".");
  if (labels.length < 2 || !labels.every((l) => LABEL.test(l))) return { error: "That does not look like a valid domain." };
  if (/^[0-9]+$/.test(labels[labels.length - 1])) return { error: "IP addresses are not allowed - use a domain name." };
  if (RESERVED.some((r) => h === r || h.endsWith("." + r))) return { error: "That domain cannot be used." };
  return { host: h };
}

/** The DNS records the owner must create, in the order to create them. */
export function dnsRecordRows(d: DomainView): DnsRecordRow[] {
  const rows: DnsRecordRow[] = [];
  if (d.dns.cnameTarget) {
    rows.push({ type: "CNAME", name: d.hostname, value: d.dns.cnameTarget, purpose: "Points your domain at VegaDuta" });
  }
  if (d.dns.aTarget) {
    rows.push({ type: "A", name: d.hostname, value: d.dns.aTarget, purpose: "Use instead of CNAME for a root domain" });
  }
  rows.push({ type: "TXT", name: d.dns.txtName, value: d.dns.txtValue, purpose: "Proves you own the domain" });
  return rows;
}

const ERRORS: Record<string, string> = {
  txt_not_found: "The TXT record was not found yet. DNS changes can take a few minutes to spread.",
  txt_mismatch: "A TXT record exists but its value does not match. Copy the value exactly.",
  dns_lookup_failed: "DNS lookup failed or timed out. Try again shortly.",
  hostname_claimed: "This domain is already verified by another account.",
  hostname_in_use: "You already added this domain.",
  reserved_hostname: "That domain cannot be used.",
  too_many_domains: "This app has reached its domain limit.",
};

export function domainErrorMessage(code: string | null | undefined): string {
  if (!code) return "";
  return ERRORS[code] || "Something went wrong (" + code + ").";
}

/** Text for a failed domains call. core answers with a machine code
 * (`txt_mismatch`, `hostname_claimed`...) as the error message; map known
 * codes to a sentence, pass any other message through, else the fallback. */
export function domainErrorText(e: unknown, fallback: string): string {
  const m = e instanceof Error ? e.message.trim() : "";
  if (!m) return fallback;
  return /^[a-z][a-z0-9_]*$/.test(m) ? domainErrorMessage(m) : m;
}
