"use client";

// /ui-kit/console-preview/contacts - the real /admin/contacts route rendered
// against sample data (see ../preview-harness.tsx). `admin` so the
// MANAGE_CHANNELS scope guard lets the page render.
import ConsolePreview from "../preview-harness";
import AdminContactsPage from "../../../admin/contacts/page";

export default function Preview() {
  return (
    <ConsolePreview label="/admin/contacts" admin>
      <AdminContactsPage />
    </ConsolePreview>
  );
}
