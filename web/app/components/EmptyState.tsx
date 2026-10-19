"use client";

import { Inbox } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

interface EmptyStateProps {
  icon?: LucideIcon;
  title: ReactNode;
  hint?: ReactNode;
  action?: ReactNode;
}

/**
 * Shared "nothing here yet" panel (visual polish pass, task #191) - replaces
 * bare `<p className="muted">No X yet.</p>` lines scattered across the
 * console with a consistent, friendlier well: an icon, a short title, an
 * optional one-line hint, and room for an inline action (e.g. re-surfacing
 * the "create one above" nudge as a real affordance instead of prose).
 * Deliberately tiny/prop-driven rather than per-panel bespoke markup, so
 * every empty list in the app reads the same way.
 */
export default function EmptyState({ icon: Icon = Inbox, title, hint, action = null }: EmptyStateProps) {
  return (
    <div className="empty-state">
      <span className="empty-state-icon">
        <Icon size={20} strokeWidth={2} />
      </span>
      <p className="empty-state-title">{title}</p>
      {hint && <p className="empty-state-hint">{hint}</p>}
      {action}
    </div>
  );
}
