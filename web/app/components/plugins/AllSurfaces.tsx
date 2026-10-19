// "Everywhere VegaDūta runs" - plugins, apps and wearables with their real
// availability. Server-renderable (no client state), so crawlers and AI
// engines read the same honest list people do. Data + the no-fiction notes
// live in ./surfaces.ts.

import Link from "next/link";
import { applyCatalog, surfacesByGroup, STATUS_LABEL, SURFACES, type SurfaceStatus } from "./surfaces";
import type { PluginCatalog } from "./serverFetch";
import { BUSINESS_EMAIL } from "../../lib/legalEntity";

const STATUS_CLASS: Record<SurfaceStatus, string> = {
  available: "border-primary/40 text-primary",
  beta: "border-border text-foreground",
  preview: "border-border text-muted-foreground",
  "in-development": "border-dashed border-border text-muted-foreground",
};

export default function AllSurfaces({ catalog = null }: { catalog?: PluginCatalog | null }) {
  const groups = surfacesByGroup(applyCatalog(SURFACES, catalog));
  // Empty when NEXT_PUBLIC_CONTACT_EMAIL is unset: no placeholder mailto (SE-46).
  const contactEmail = BUSINESS_EMAIL;
  return (
    <section aria-labelledby="all-surfaces-title" className="mt-12 border-t border-border pt-10">
      <h2 id="all-surfaces-title" className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl">
        Everywhere VegaDūta runs
      </h2>
      <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
        Plugins, apps and wearables, each with where it really stands today. “Available” means you can get it right now;
        everything else says plainly what is and is not ready.
      </p>

      {groups.map((g) => (
        <div key={g.group} className="mt-8">
          <h3 className="text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">{g.label}</h3>
          <ul className="mt-3 grid gap-3 sm:grid-cols-2">
            {g.items.map((s) => (
              <li key={s.id} className="rounded-lg border border-border bg-card p-4">
                <div className="flex items-start justify-between gap-3">
                  <p className="min-w-0 break-words text-sm font-semibold text-foreground">{s.name}</p>
                  <span
                    className={`shrink-0 rounded-full border px-2 py-0.5 text-[11px] font-medium ${STATUS_CLASS[s.status]}`}
                  >
                    {STATUS_LABEL[s.status]}
                  </span>
                </div>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{s.does}</p>
                <p className="mt-2 text-xs leading-relaxed text-foreground">
                  {s.href ? (
                    <Link href={s.href} className="underline underline-offset-2 hover:text-primary">
                      {s.where}
                    </Link>
                  ) : (
                    s.where
                  )}
                </p>
              </li>
            ))}
          </ul>
        </div>
      ))}

      <p className="mt-8 max-w-2xl text-sm leading-relaxed text-muted-foreground">
        Waiting for one of these, or need it on something we have not listed?{" "}
        {contactEmail ? (
          <>
            Write to{" "}
            <a
              href={`mailto:${contactEmail}`}
              className="font-medium text-foreground underline underline-offset-2"
            >
              {contactEmail}
            </a>{" "}
            and tell us what you use. It helps us decide what to finish first.
          </>
        ) : (
          <>Open an issue on the project repository and tell us what you use. It helps us decide what to finish first.</>
        )}
      </p>
    </section>
  );
}
