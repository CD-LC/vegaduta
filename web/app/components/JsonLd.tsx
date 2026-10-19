// Structured data (<script type="application/ld+json">) with the same escaping
// as the window.__ENV script (lib/env.ts scriptSafeJson): a value containing
// "</script>" or "<!--" cannot break out of the element (security review
// FE-004 / SEC-054). Use this for every JSON-LD block instead of hand-writing
// dangerouslySetInnerHTML={{ __html: JSON.stringify(...) }}.
//
// A JSON-LD block is a data block, not a script, so the CSP never executes or
// blocks it and it needs no nonce. Works in server and client components.
import { scriptSafeJson } from "../lib/env";

export default function JsonLd({ data, id }: { data: unknown; id?: string }) {
  return <script type="application/ld+json" id={id} dangerouslySetInnerHTML={{ __html: scriptSafeJson(data) }} />;
}
