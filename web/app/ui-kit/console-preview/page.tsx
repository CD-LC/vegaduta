// Index of the console previews (dev only; each child page guards itself).
import Link from "next/link";
import { notFound } from "next/navigation";

const PAGES = ["agents", "workflows", "tools", "knowledge", "admin", "admin-billing", "quick-task", "playground", "usage", "providers", "local-model", "contacts", "agent-lab", "agent-playground", "assistant"];

export default function ConsolePreviewIndex() {
  // Sample-data preview: never reachable in a production build.
  if (process.env.NODE_ENV === "production") notFound();
  return (
    <main style={{ padding: 32, maxWidth: 640, margin: "0 auto" }}>
      <p className="eyebrow">Console previews</p>
      <h1 style={{ fontSize: 24, marginBottom: 16 }}>Authenticated screens, sample data</h1>
      <ul style={{ display: "grid", gap: 8, padding: 0, listStyle: "none" }}>
        <li><Link href="/ui-kit/dashboard-preview">dashboard-preview</Link></li>
        {PAGES.map((p) => (
          <li key={p}><Link href={`/ui-kit/console-preview/${p}`}>{p}</Link></li>
        ))}
      </ul>
    </main>
  );
}
