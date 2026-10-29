"use client";

// One comment inside a thread card: author, time, body with highlighted
// @mentions, and the author's own edit/delete actions.
import { useMemo, useState } from "react";
import { Pencil, Trash2 } from "lucide-react";
import { isEdited, mentionLabels, personLabel, relativeTime, splitMentions, type CommentPerson, type CommentView } from "./comments";
import { fullTime, initials, type MentionPerson } from "./commentCardShared";
import { CommentComposer } from "./CommentComposer";
import { useTranslations } from "../../lib/i18n";

interface CommentItemProps {
  comment: CommentView;
  isRoot: boolean;
  now: number;
  people: MentionPerson[];
  peopleById: Map<string, MentionPerson>;
  editing: boolean;
  busy: boolean;
  onEdit: () => void;
  onCancelEdit: () => void;
  onSaveEdit: (body: string, ids: string[], mentioned: CommentPerson[], meta: { pickedDuringEdit: boolean }) => Promise<boolean>;
  onDelete: () => void;
}

/**
 * Prefer the people-list name (what the author picked and typed) over core's
 * displayName, which comes from the user's latest comment and may differ.
 */
function withNames(list: CommentPerson[], byId: Map<string, MentionPerson>): CommentPerson[] {
  return list.map((p) => ({ id: p.id, displayName: byId.get(p.id)?.displayName || p.displayName }));
}

/** Every label variant of each mention, so highlighting matches whichever name was typed. */
function mentionVariants(list: CommentPerson[], byId: Map<string, MentionPerson>): CommentPerson[] {
  const out: CommentPerson[] = [];
  for (const p of list) {
    for (const label of mentionLabels(p.id, [byId.get(p.id)?.displayName, p.displayName])) {
      out.push({ id: p.id, displayName: label });
    }
  }
  return out;
}

export default function CommentItem({
  comment: c, isRoot, now, people, peopleById, editing, busy, onEdit, onCancelEdit, onSaveEdit, onDelete,
}: CommentItemProps) {
    const tHC = useTranslations("hcComponentsWorkflowCommentItem");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const author = c.author.displayName ? c.author : { id: c.author.id, displayName: peopleById.get(c.author.id)?.displayName ?? null };
  const who = personLabel(author);
  const mentions = withNames(c.mentions, peopleById);
  const highlight = useMemo(() => mentionVariants(c.mentions, peopleById), [c.mentions, peopleById]);
  const edited = isEdited(c);
  const canAct = c.canEdit && !c.pending && !c.deleted;

  if (c.deleted) {
    return (
      <li className="wfc-item" data-deleted="true">
        <p className="wfc-deleted">{isRoot ? "The first comment in this thread was deleted." : "This comment was deleted."}</p>
      </li>
    );
  }

  return (
    <li className="wfc-item" data-pending={c.pending || undefined} aria-busy={c.pending || undefined}>
      <div className="wfc-item-head">
        <span className="wfc-avatar" aria-hidden="true">{initials(who)}</span>
        <span className="wfc-author">{who}</span>
        <time className="wfc-time" dateTime={c.createdAt} title={fullTime(c.createdAt)}>
          {c.pending ? "Sending…" : relativeTime(c.createdAt, now)}
        </time>
        {edited && (
          <span className="wfc-edited" title={fullTime(c.updatedAt)}>
            (edited)
          </span>
        )}
        {canAct && !editing && !confirmDelete && (
          <span className="wfc-item-actions">
            <button type="button" className="wfc-icon" aria-label={`Edit comment by ${who}`} onClick={onEdit} disabled={busy}>
              <Pencil size={12} aria-hidden="true" />
            </button>
            <button type="button" className="wfc-icon" aria-label={`Delete comment by ${who}`} onClick={() => setConfirmDelete(true)} disabled={busy}>
              <Trash2 size={12} aria-hidden="true" />
            </button>
          </span>
        )}
      </div>

      {editing ? (
        <CommentComposer
          label={tHC("hcComponentsWorkflowCommentItem.s3")}
          people={people}
          initialText={c.body ?? ""}
          initialMentions={mentions}
          submitLabel="Save"
          autoFocus
          keepOnSubmit
          busy={busy}
          onCancel={onCancelEdit}
          onSubmit={onSaveEdit}
        />
      ) : (
        <p className="wfc-body">
          {splitMentions(c.body ?? "", highlight).map((seg, i) =>
            seg.mention ? (
              <span key={i} className="wfc-mention">{seg.text}</span>
            ) : (
              <span key={i}>{seg.text}</span>
            ),
          )}
        </p>
      )}

      {confirmDelete && (
        <div className="wfc-confirm" role="group" aria-label={tHC("hcComponentsWorkflowCommentItem.s2")}>
          <span>{tHC("hcComponentsWorkflowCommentItem.s1")}</span>
          <button
            type="button"
            className="wfc-btn"
            data-danger="true"
            autoFocus
            onClick={() => {
              setConfirmDelete(false);
              onDelete();
            }}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                e.preventDefault();
                e.stopPropagation();
                setConfirmDelete(false);
              }
            }}
          >
            Delete
          </button>
          <button type="button" className="wfc-btn" onClick={() => setConfirmDelete(false)}>
            Cancel
          </button>
        </div>
      )}
    </li>
  );
}
