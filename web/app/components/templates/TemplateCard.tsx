"use client";

import Link from "next/link";
import { Download } from "lucide-react";
import { Card, CardContent } from "../ui/card";
import CategoryChip from "./CategoryChip";
import { categoryMeta, kindMeta } from "./categories";
import type { CommunityTemplateSummary } from "../../lib/api";

// One gallery card. Category chip (icon + tint) + title + summary + the real
// import count from the API. Deliberately shows NO configuration (system
// prompt / model / tools) - the list stays scannable; the design is on the
// detail page. The whole card is a link to that detail page.
//
// Honesty rule: the ONLY number asserted here is `importCount`, which is real
// API data. Nothing else claims a count, rating or testimonial.
export default function TemplateCard({ template }: { template: CommunityTemplateSummary }) {
  const cat = categoryMeta(template.category);
  const kind = kindMeta(template.kind);
  const KindIcon = kind.icon;
  const count = typeof template.importCount === "number" ? template.importCount : 0;

  return (
    <Card interactive cat={cat.cat} elevation="base" className="gap-0 p-0">
      <CardContent className="p-0">
        <Link
          href={`/templates/${encodeURIComponent(template.id)}`}
          className="flex h-full flex-col gap-3 rounded-xl p-5 outline-none focus-visible:ring-2 focus-visible:ring-[var(--cat)]"
        >
          <div className="flex items-start justify-between gap-3">
            <CategoryChip categoryId={template.category} size="md" />
            <span
              className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground"
              title={`${kind.label.replace(/s$/, "")} template`}
            >
              <KindIcon size={13} strokeWidth={2} aria-hidden="true" />
              {kind.label.replace(/s$/, "")}
            </span>
          </div>

          <div className="space-y-1">
            <h3 className="text-sm font-semibold leading-snug text-foreground">{template.title}</h3>
            <p className="line-clamp-3 text-sm leading-relaxed text-muted-foreground">{template.summary}</p>
          </div>

          {template.tags && template.tags.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {template.tags.slice(0, 3).map((tag) => (
                <span
                  key={tag}
                  className="rounded-full border border-border/70 px-2 py-0.5 text-[0.6875rem] font-medium text-muted-foreground"
                >
                  {tag}
                </span>
              ))}
            </div>
          )}

          <div className="mt-auto flex items-center gap-1.5 pt-1 text-xs text-muted-foreground">
            <Download size={13} strokeWidth={2} aria-hidden="true" />
            {count > 0 ? (
              <span>
                {count.toLocaleString()} {count === 1 ? "import" : "imports"}
              </span>
            ) : (
              <span>No imports yet</span>
            )}
          </div>
        </Link>
      </CardContent>
    </Card>
  );
}
