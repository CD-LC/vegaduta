"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Loader2, X } from "lucide-react";
import { api } from "../../lib/api";
import type { CommunityTemplateEditPayload, CommunityTemplateSubmission } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { Label } from "../ui/label";
import { Select } from "../ui/select";
import { TEMPLATE_CATEGORIES } from "./categories";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import { useTranslations } from "../../lib/i18n";

// Edit the descriptive metadata of a draft/rejected submission (title,
// summary, category, tags). The underlying agent/workflow design is not edited
// here - that stays in the source object; this only changes how the template
// is described in the gallery. Open when `submission` is non-null.
export default function EditSubmissionModal({
  submission,
  onClose,
  onSaved,
}: {
  submission: CommunityTemplateSubmission | null;
  onClose: () => void;
  onSaved: () => void;
}) {
    const tHC = useTranslations("hcComponentsTemplatesEditSubmissionModal");
  const [title, setTitle] = useState("");
  const [summary, setSummary] = useState("");
  const [category, setCategory] = useState(TEMPLATE_CATEGORIES[0].id);
  const [tagsInput, setTagsInput] = useState("");
  const [saving, setSaving] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  // Focus in on open, Tab trap, Escape closes, focus back to the opener on close (FE-041).
  useDialogFocus(dialogRef, null, onClose, { enabled: !!submission });

  useEffect(() => {
    if (!submission) return;
    setTitle(submission.title || "");
    setSummary(submission.summary || "");
    setCategory(submission.category || TEMPLATE_CATEGORIES[0].id);
    setTagsInput((submission.tags || []).join(", "));
  }, [submission]);

  const tags = useMemo(
    () =>
      tagsInput
        .split(",")
        .map((t) => t.trim())
        .filter(Boolean)
        .slice(0, 8),
    [tagsInput]
  );

  if (!submission) return null;

  async function handleSave() {
    if (!submission) return;
    if (!title.trim() || !summary.trim()) {
      toast.error("Add a title and a short summary before saving.");
      return;
    }
    setSaving(true);
    try {
      const payload: CommunityTemplateEditPayload = {
        title: title.trim(),
        summary: summary.trim(),
        category,
        tags,
      };
      await api.updateTemplate(submission.id, payload);
      toast.success("Changes saved.");
      onSaved();
    } catch (e: any) {
      toast.error(e?.message || "Couldn't save your changes. Please try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/60 p-4 backdrop-blur-sm sm:p-8"
      role="presentation"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={tHC("s3")}
        className="elev-raised w-full max-w-xl rounded-2xl p-6 shadow-xl"
      >
        <div className="mb-5 flex items-start justify-between gap-4">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">{tHC("s3")}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-[var(--surface-hover)] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <X size={18} strokeWidth={2} aria-hidden="true" />
          </button>
        </div>

        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="edit-title">Title</Label>
            <Input id="edit-title" value={title} maxLength={80} onChange={(e) => setTitle(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="edit-summary">Summary</Label>
            <Textarea
              id="edit-summary"
              value={summary}
              maxLength={280}
              rows={3}
              onChange={(e) => setSummary(e.target.value)}
            />
            <p className="text-end text-[0.6875rem] text-muted-foreground">{summary.length}/280</p>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="edit-category">Category</Label>
              <Select
                id="edit-category"
                value={category}
                onValueChange={setCategory}
                options={TEMPLATE_CATEGORIES.map((c) => ({ value: c.id, label: c.label }))}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="edit-tags">Tags</Label>
              <Input
                id="edit-tags"
                value={tagsInput}
                onChange={(e) => setTagsInput(e.target.value)}
                placeholder={tHC("s2")}
              />
            </div>
          </div>
        </div>

        <div className="mt-6 flex items-center justify-end gap-2">
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={saving}>
            {saving ? <Loader2 size={15} className="animate-spin" aria-hidden="true" /> : null}
            {tHC("s1")}
          </Button>
        </div>
      </div>
    </div>
  );
}
