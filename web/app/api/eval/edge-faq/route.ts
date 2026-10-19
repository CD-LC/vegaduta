import { NextResponse } from "next/server";
import { readFileSync, existsSync } from "node:fs";
import path from "node:path";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Serves the canonical edge-FAQ eval dataset to the in-browser runner at
 * /eval/edge-faq. The dataset lives in core/quality (with the eval harness,
 * merged from the old core/eval module) so the SAME file feeds both the
 * JVM EvalRunner (hosted / OpenAI-compatible backends) and this route (the
 * on-device edge tiers) — never duplicate it.
 *
 * Only resolvable when the web server runs from the repo checkout (dev
 * server / local eval). A deployed container without the repo returns
 * 404, which the runner surfaces as an aborted report rather than a
 * misleading empty pass.
 */
export function GET() {
  const candidates = [
    // cwd = web/ (npm run dev / next start from the web package)
    path.resolve(process.cwd(), "..", "core", "quality", "src", "main", "resources", "edge-faq-dataset.json"),
    // cwd = repo root
    path.resolve(process.cwd(), "core", "quality", "src", "main", "resources", "edge-faq-dataset.json"),
  ];
  const file = candidates.find(existsSync);
  if (!file) {
    return NextResponse.json(
      { error: "edge-faq-dataset.json not found — run the web server from a repo checkout" },
      { status: 404 },
    );
  }
  return NextResponse.json(JSON.parse(readFileSync(file, "utf8")));
}
