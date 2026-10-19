"use client";

// /ui-kit/console-preview/knowledge - the real /knowledge route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import KnowledgePage from "../../../knowledge/page";

export default function Preview() {
  return (
    <ConsolePreview label="/knowledge">
      <KnowledgePage />
    </ConsolePreview>
  );
}
