import { permanentRedirect } from "next/navigation";

// ---------------------------------------------------------------------------
// /ai/model-context-protocol → /ai/mcp (2026-08-14). The long form is a real
// search phrase worth owning, but serving the same article on two URLs would
// split ranking signal — so the long form 301s to the canonical short slug.
// A static segment here beats the [slug] dynamic sibling by Next's routing
// precedence. Deliberately NOT in sitemap.ts (a sitemap must not advertise a
// permanent redirect — same rule that removed /explore).
// ---------------------------------------------------------------------------

export default function ModelContextProtocolRedirect() {
  permanentRedirect("/ai/mcp");
}
