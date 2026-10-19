// Team collaboration on studio projects: pure role/comment helpers plus a thin
// client for core's /api/studio-projects/{id}/members and /comments (V372).
//
// Roles mirror core's StudioCollabRules: OWNER does everything; EDITOR adds
// versions but cannot rename/change settings/delete/share; VIEWER is read-only.
// A project with no role is local-only and therefore fully editable.

import { apiFetch, jsonBody } from "../../lib/api/core";
import type { CollabRole } from "./store";

export interface Member {
  subject: string;
  name: string;
  email: string | null;
  role: CollabRole;
  addedAt: number | null;
}

export interface Comment {
  id: string;
  versionAt: number | null;
  authorSubject: string;
  authorName: string;
  body: string;
  createdAt: number;
  resolved: boolean;
}

export const MAX_COMMENT_CHARS = 2000;

/** Can this role add versions / edit the app locally and push it? */
export function canEdit(role: CollabRole | undefined | null): boolean {
  return !role || role === "OWNER" || role === "EDITOR";
}

/** Rename, settings, delete, share. */
export function canAdminister(role: CollabRole | undefined | null): boolean {
  return !role || role === "OWNER";
}

export function isShared(role: CollabRole | undefined | null): boolean {
  return role === "EDITOR" || role === "VIEWER";
}

export function roleLabel(role: CollabRole): string {
  return role === "OWNER" ? "Owner" : role === "EDITOR" ? "Can edit" : "Can view";
}

/** Author or the project owner may delete a comment (core enforces the same). */
export function canDeleteComment(role: CollabRole | undefined | null, c: Comment, me: string | null): boolean {
  if (!role) return false;
  return role === "OWNER" || (!!me && c.authorSubject === me);
}

/** Open threads first (oldest first, they are the to-do list), then resolved (newest first). */
export function orderComments(list: Comment[]): Comment[] {
  const open = list.filter((c) => !c.resolved).sort((a, b) => a.createdAt - b.createdAt);
  const done = list.filter((c) => c.resolved).sort((a, b) => b.createdAt - a.createdAt);
  return [...open, ...done];
}

export function openCount(list: Comment[]): number {
  return list.filter((c) => !c.resolved).length;
}

/** Client-side mirror of core's body rule: trimmed, non-empty, <= 2000. */
export function validateCommentBody(raw: string): { ok: boolean; body?: string; error?: string } {
  const body = raw.trim();
  if (!body) return { ok: false, error: "Write something first." };
  if (body.length > MAX_COMMENT_CHARS) return { ok: false, error: `Comments are limited to ${MAX_COMMENT_CHARS} characters.` };
  return { ok: true, body };
}

const ERRORS: Record<string, string> = {
  not_a_tenant_member: "No one with that email is in your workspace.",
  already_owner: "That person already owns this project.",
  bad_email: "Enter a valid email address.",
  bad_role: "Pick a role.",
  too_many_members: "This project has reached its member limit.",
  too_many_comments: "This project has reached its comment limit.",
  comment_too_long: `Comments are limited to ${MAX_COMMENT_CHARS} characters.`,
  empty_comment: "Write something first.",
  forbidden: "Your role does not allow that.",
  project_not_found: "This project is not on the server yet, or is no longer shared with you.",
  directory_unavailable: "Team sharing is not available on this deployment.",
};

export function collabErrorMessage(code: string | null | undefined, status?: number): string {
  if (code && ERRORS[code]) return ERRORS[code];
  if (status === 429) return "Too many requests - wait a minute and try again.";
  return "Something went wrong. Please try again.";
}

export type Result<T> = { ok: boolean; data?: T; error?: string };

async function call<T>(path: string, options?: object): Promise<Result<T>> {
  try {
    const res = await apiFetch(path, options);
    if (!res.ok) {
      let code: string | null = null;
      try {
        code = ((await res.json()) as { error?: string }).error ?? null;
      } catch {
        /* non-JSON error body */
      }
      return { ok: false, error: collabErrorMessage(code, res.status) };
    }
    return { ok: true, data: res.status === 204 ? (null as T) : ((await res.json()) as T) };
  } catch {
    return { ok: false, error: "Could not reach the server." };
  }
}

const base = (id: string): string => `/api/studio-projects/${encodeURIComponent(id)}`;

export const collabApi = {
  listMembers: (id: string) => call<Member[]>(`${base(id)}/members`),
  invite: (id: string, email: string, role: "VIEWER" | "EDITOR") =>
    call<Member>(`${base(id)}/members`, { method: "POST", ...jsonBody({ email, role }) }),
  removeMember: (id: string, subject: string) =>
    call<null>(`${base(id)}/members/${encodeURIComponent(subject)}`, { method: "DELETE" }),
  listComments: (id: string) => call<Comment[]>(`${base(id)}/comments`),
  addComment: (id: string, body: string, versionAt?: number | null) =>
    call<Comment>(`${base(id)}/comments`, { method: "POST", ...jsonBody({ body, versionAt: versionAt ?? null }) }),
  setResolved: (id: string, commentId: string, resolved: boolean) =>
    call<Comment>(`${base(id)}/comments/${encodeURIComponent(commentId)}`, {
      method: "PATCH",
      ...jsonBody({ resolved }),
    }),
  deleteComment: (id: string, commentId: string) =>
    call<null>(`${base(id)}/comments/${encodeURIComponent(commentId)}`, { method: "DELETE" }),
};
