"use client";

// Team panel for one studio project: who has access (owner invites tenant
// members by email as viewer/editor) and a comment thread with resolve.
//
// Mount it from StudioSurface next to the project's other panels, e.g.
//   <CollabPanel projectId={p.id} role={p.role} versions={p.versions} />
// It only talks to the server projects mirror, so a project that has never
// synced (no server copy yet) shows a friendly "not on the server yet" note.

import { useCallback, useEffect, useState } from "react";
import { Check, MessageSquare, Trash2, UserPlus, X } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Textarea } from "../../components/ui/textarea";
import { auth } from "../../lib/auth";
import {
  MAX_COMMENT_CHARS,
  canAdminister,
  canDeleteComment,
  collabApi,
  openCount,
  orderComments,
  roleLabel,
  validateCommentBody,
  type Comment,
  type Member,
} from "./collab";
import type { AppVersion, CollabRole } from "./store";
import { useTranslations } from "../../lib/i18n";

interface Props {
  projectId: string;
  /** Caller's role; undefined = local-only or owner. */
  role?: CollabRole;
  /** Optional: lets a comment be anchored to one version. */
  versions?: Pick<AppVersion, "at" | "note">[];
}

export default function CollabPanel({ projectId, role, versions = [] }: Props) {
    const tHC = useTranslations("hcBuildStudioCollabPanel");
  const me = auth.getUserId?.() ?? null;
  const owner = canAdminister(role);
  const [members, setMembers] = useState<Member[]>([]);
  const [comments, setComments] = useState<Comment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const [email, setEmail] = useState("");
  const [inviteRole, setInviteRole] = useState<"VIEWER" | "EDITOR">("VIEWER");
  const [inviting, setInviting] = useState(false);

  const [draft, setDraft] = useState("");
  const [anchor, setAnchor] = useState<string>("");
  const [posting, setPosting] = useState(false);
  const [showResolved, setShowResolved] = useState(false);

  const load = useCallback(async () => {
    const [m, c] = await Promise.all([collabApi.listMembers(projectId), collabApi.listComments(projectId)]);
    if (m.ok) setMembers(m.data);
    if (c.ok) setComments(c.data);
    setError(!m.ok ? m.error : !c.ok ? c.error : null);
    setLoading(false);
  }, [projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function invite() {
    setInviting(true);
    const r = await collabApi.invite(projectId, email.trim(), inviteRole);
    setInviting(false);
    if (!r.ok) return setError(r.error);
    setError(null);
    setEmail("");
    await load();
  }

  async function remove(subject: string) {
    const r = await collabApi.removeMember(projectId, subject);
    if (!r.ok) return setError(r.error);
    await load();
  }

  async function post() {
    const v = validateCommentBody(draft);
    if (!v.ok) return setError(v.error);
    setPosting(true);
    const r = await collabApi.addComment(projectId, v.body, anchor ? Number(anchor) : null);
    setPosting(false);
    if (!r.ok) return setError(r.error);
    setError(null);
    setDraft("");
    setAnchor("");
    setComments((prev) => [...prev, r.data]);
  }

  async function toggle(c: Comment) {
    const r = await collabApi.setResolved(projectId, c.id, !c.resolved);
    if (!r.ok) return setError(r.error);
    setComments((prev) => prev.map((x) => (x.id === c.id ? r.data : x)));
  }

  async function del(c: Comment) {
    const r = await collabApi.deleteComment(projectId, c.id);
    if (!r.ok) return setError(r.error);
    setComments((prev) => prev.filter((x) => x.id !== c.id));
  }

  const ordered = orderComments(comments);
  const visible = showResolved ? ordered : ordered.filter((c) => !c.resolved);
  const open = openCount(comments);

  return (
    <section className="flex flex-col gap-4 text-sm" aria-label="Team">
      {role && role !== "OWNER" && (
        <p className="rounded-lg bg-secondary/60 p-2 text-xs text-muted-foreground">
          {tHC("hcBuildStudioCollabPanel.s11")} <b>{roleLabel(role)}</b>.{" "}
          {role === "VIEWER"
            ? "This copy is read-only."
            : "You can add versions; the owner manages settings and sharing."}
        </p>
      )}
      {error && (
        <p role="alert" className="rounded-lg bg-destructive/10 p-2 text-xs text-destructive">
          {error}
        </p>
      )}

      <div className="flex flex-col gap-2">
        <h3 className="font-medium">People</h3>
        {loading && <p className="text-xs text-muted-foreground">{tHC("hcBuildStudioCollabPanel.s10")}</p>}
        <ul className="flex flex-col gap-1.5">
          {members.map((m) => (
            <li key={m.subject} className="flex items-center gap-2 rounded-lg border border-border p-2">
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium">{m.name}</span>
                {m.email && <span className="block truncate text-xs text-muted-foreground">{m.email}</span>}
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">{roleLabel(m.role)}</span>
              {owner && m.role !== "OWNER" && (
                <Button
                  size="sm"
                  variant="ghost"
                  aria-label={`Remove ${m.name}`}
                  onClick={() => void remove(m.subject)}
                >
                  <X aria-hidden="true" size={14} />
                </Button>
              )}
            </li>
          ))}
        </ul>
        {owner && (
          <form
            className="flex flex-wrap items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              void invite();
            }}
          >
            <Input
              type="email"
              required
              placeholder="teammate@company.com"
              aria-label={tHC("hcBuildStudioCollabPanel.s9")}
              className="min-w-0 flex-1"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            <select
              aria-label="Role"
              className="rounded-md border border-border bg-background px-2 py-1.5 text-sm"
              value={inviteRole}
              onChange={(e) => setInviteRole(e.target.value as "VIEWER" | "EDITOR")}
            >
              <option value="VIEWER">{tHC("hcBuildStudioCollabPanel.s8")}</option>
              <option value="EDITOR">{tHC("hcBuildStudioCollabPanel.s7")}</option>
            </select>
            <Button type="submit" size="sm" disabled={inviting || !email.trim()}>
              <UserPlus aria-hidden="true" size={14} className="me-1" /> Invite
            </Button>
          </form>
        )}
      </div>

      <div className="flex flex-col gap-2">
        <div className="flex items-center gap-2">
          <h3 className="font-medium">
            <MessageSquare aria-hidden="true" size={14} className="me-1 inline" /> Comments
            {open > 0 && <span className="ms-1 text-xs text-muted-foreground">({open} open)</span>}
          </h3>
          <label className="ms-auto flex items-center gap-1 text-xs text-muted-foreground">
            <input type="checkbox" checked={showResolved} onChange={(e) => setShowResolved(e.target.checked)} />
            {tHC("hcBuildStudioCollabPanel.s6")}
          </label>
        </div>
        <ul className="flex flex-col gap-1.5">
          {visible.length === 0 && !loading && <li className="text-xs text-muted-foreground">{tHC("hcBuildStudioCollabPanel.s5")}</li>}
          {visible.map((c) => (
            <li key={c.id} className={`rounded-lg border border-border p-2 ${c.resolved ? "bg-muted/40" : ""}`}>
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <span className="font-medium text-foreground">{c.authorName}</span>
                <time dateTime={new Date(c.createdAt).toISOString()}>{new Date(c.createdAt).toLocaleString()}</time>
                {c.versionAt && (
                  <span className="rounded bg-secondary px-1">v {new Date(c.versionAt).toLocaleTimeString()}</span>
                )}
                <span className="ms-auto flex gap-1">
                  <Button
                    size="sm"
                    variant="ghost"
                    aria-label={c.resolved ? "Re-open comment" : "Resolve comment"}
                    onClick={() => void toggle(c)}
                  >
                    <Check aria-hidden="true" size={14} />
                  </Button>
                  {canDeleteComment(role ?? "OWNER", c, me) && (
                    <Button size="sm" variant="ghost" aria-label={tHC("hcBuildStudioCollabPanel.s4")} onClick={() => void del(c)}>
                      <Trash2 aria-hidden="true" size={14} />
                    </Button>
                  )}
                </span>
              </div>
              <p className="mt-1 whitespace-pre-wrap break-words">{c.body}</p>
            </li>
          ))}
        </ul>
        <form
          className="flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void post();
          }}
        >
          <Textarea
            aria-label={tHC("hcBuildStudioCollabPanel.s3")}
            placeholder={tHC("hcBuildStudioCollabPanel.s3")}
            maxLength={MAX_COMMENT_CHARS}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
          <div className="flex items-center gap-2">
            {versions.length > 0 && (
              <select
                aria-label={tHC("hcBuildStudioCollabPanel.s2")}
                className="min-w-0 rounded-md border border-border bg-background px-2 py-1.5 text-xs"
                value={anchor}
                onChange={(e) => setAnchor(e.target.value)}
              >
                <option value="">{tHC("hcBuildStudioCollabPanel.s1")}</option>
                {[...versions].reverse().map((v) => (
                  <option key={v.at} value={v.at}>
                    {new Date(v.at).toLocaleTimeString()} {v.note ? `- ${v.note.slice(0, 40)}` : ""}
                  </option>
                ))}
              </select>
            )}
            <Button type="submit" size="sm" className="ms-auto" disabled={posting || !draft.trim()}>
              Comment
            </Button>
          </div>
        </form>
      </div>
    </section>
  );
}
