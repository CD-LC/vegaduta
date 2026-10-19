"""An in-process, faithful-enough stand-in for GitHub's REST API.

Not a "return {} for everything" stub: it implements the actual object model
app-builder writes through — blobs, trees (with `base_tree` overlay
semantics), commits, refs, contents, pulls — so a test can assert on the real
resulting file tree of a branch, not just on "some POST happened".

Installed by replacing `github_client.httpx` with `shim()`, which keeps
httpx's own exception classes so `github_client._request`'s except clauses
still behave. Every request is recorded in `.requests` so a test can assert
what was and was not sent, including whether a token ever appeared somewhere
it should not.

No network access is used or needed. Real GitHub calls are not possible from
the test environment (no credentials, and we would not want a test creating
branches in someone's repo), so this is an explicit mock — see the header of
test_byo_repo.py.
"""

from __future__ import annotations

import base64
import hashlib
import json
import re
import urllib.parse

import httpx

_REPO = r"/repos/([A-Za-z0-9._\-]+)/([A-Za-z0-9._\-]+)"
_RX_REPO = re.compile(rf"^{_REPO}$")
_RX_GET_REF = re.compile(rf"^{_REPO}/git/ref/heads/(?P<branch>.+)$")
_RX_REFS = re.compile(rf"^{_REPO}/git/refs$")
_RX_PATCH_REF = re.compile(rf"^{_REPO}/git/refs/heads/(?P<branch>.+)$")
_RX_BLOBS = re.compile(rf"^{_REPO}/git/blobs$")
_RX_TREES = re.compile(rf"^{_REPO}/git/trees$")
_RX_COMMITS = re.compile(rf"^{_REPO}/git/commits$")
_RX_GET_COMMIT = re.compile(rf"^{_REPO}/git/commits/(?P<sha>[0-9a-f]+)$")
_RX_GET_TREE = re.compile(rf"^{_REPO}/git/trees/(?P<sha>[0-9a-f]+)$")
_RX_CONTENTS = re.compile(rf"^{_REPO}/contents/(?P<path>.+)$")
_RX_PULLS = re.compile(rf"^{_REPO}/pulls$")
_RX_PAGES = re.compile(rf"^{_REPO}/pages$")
_RX_COMPARE = re.compile(rf"^{_REPO}/compare/(?P<spec>.+)$")
_RX_MERGES = re.compile(rf"^{_REPO}/merges$")


class _Response:
    def __init__(self, status_code: int, payload=None):
        self.status_code = status_code
        self._payload = payload
        self.text = "" if payload is None else json.dumps(payload)
        self.content = self.text.encode("utf-8")

    def json(self):
        if self._payload is None:
            raise ValueError("no json")
        return self._payload


def _sha(*parts: str) -> str:
    # Emulates a git object id, not a security use (bandit B324).
    h = hashlib.sha1(usedforsecurity=False)
    for p in parts:
        h.update(p.encode("utf-8"))
    return h.hexdigest()


class FakeGitHub:
    def __init__(self, api_base: str = "https://api.github.test",
                 token: str = "ghp_valid", repo: str = "acme/widgets",
                 default_branch: str = "main",
                 can_push: bool = True,
                 empty_repo: bool = False):
        self.api_base = api_base.rstrip("/")
        self.valid_token = token
        self.repo = repo
        self.default_branch = default_branch
        self.can_push = can_push
        self.include_permissions = True

        self.blobs: dict[str, str] = {}          # sha -> base64 content
        self.trees: dict[str, dict[str, str]] = {}   # sha -> {path: blob_sha}
        self.commits: dict[str, dict] = {}       # sha -> {tree, parents, message}
        self.refs: dict[str, str] = {}           # branch -> commit sha
        self.pulls: list[dict] = []
        self.requests: list[dict] = []
        self.fail_pull_with: int | None = None
        self.echo_token_in_error = False
        # Pages site state: None = never enabled, else the POST body that
        # enabled it. fail_pages_with simulates a token without Pages:write.
        self.pages: dict | None = None
        self.fail_pages_with: int | None = None

        # One initial commit on the default branch, so the repo is not empty
        # — unless `empty_repo` asks us to model a freshly-created repo with
        # zero commits anywhere, which is what real GitHub 409s on for any
        # git-data ref lookup (see _RX_GET_REF below).
        if not empty_repo:
            empty_tree = _sha("tree", "empty")
            self.trees[empty_tree] = {"README.md": self._put_blob(
                base64.b64encode(b"# widgets\n").decode("ascii"))}
            root = _sha("commit", "root")
            self.commits[root] = {"tree": empty_tree, "parents": [],
                                  "message": "initial commit"}
            self.refs[default_branch] = root

    # ---------------------------------------------------------------- #

    def _put_blob(self, content_b64: str) -> str:
        sha = _sha("blob", content_b64)
        self.blobs[sha] = content_b64
        return sha

    def file_paths(self, branch: str) -> set[str]:
        """Every path present on `branch` — the assertion surface for "did the
        scaffold land, and did the AI commit avoid wiping it"."""
        commit = self.commits[self.refs[branch]]
        return set(self.trees[commit["tree"]].keys())

    def file_text(self, branch: str, path: str) -> str:
        commit = self.commits[self.refs[branch]]
        blob_sha = self.trees[commit["tree"]][path]
        return base64.b64decode(self.blobs[blob_sha]).decode("utf-8")

    def commit_messages(self, branch: str) -> list[str]:
        """Newest first, walking first parents."""
        out = []
        sha = self.refs.get(branch)
        while sha:
            c = self.commits[sha]
            out.append(c["message"])
            sha = c["parents"][0] if c["parents"] else None
        return out

    def shim(self):
        """An object usable as a drop-in for the `httpx` module inside
        github_client (it only ever uses .request and the two exception
        types)."""
        fake = self

        class _Shim:
            TimeoutException = httpx.TimeoutException
            TransportError = httpx.TransportError

            @staticmethod
            def request(method, url, headers=None, json=None, params=None,
                        timeout=None):
                return fake.handle(method, url, headers or {}, json, params)

        return _Shim()

    # ---------------------------------------------------------------- #

    def handle(self, method: str, url: str, headers: dict,
               body: dict | None, params: dict | None) -> _Response:
        self.requests.append({"method": method, "url": url,
                              "headers": dict(headers), "body": body,
                              "params": params})
        if not url.startswith(self.api_base):
            return _Response(404, {"message": "no such host"})
        path = urllib.parse.unquote(url[len(self.api_base):])

        auth = headers.get("Authorization", "")
        if auth != f"Bearer {self.valid_token}":
            msg = "Bad credentials"
            if self.echo_token_in_error:
                # Worst case: GitHub reflects the credential back at us. The
                # client must scrub it before it becomes an exception message.
                msg = f"Bad credentials for {auth}"
            return _Response(401, {"message": msg})

        return self._route(method, path, body, params)

    def _route(self, method, path, body, params) -> _Response:
        m = _RX_REPO.match(path)
        if m and method == "GET":
            return self._get_repo(m)

        m = _RX_GET_REF.match(path)
        if m and method == "GET":
            branch = m.group("branch")
            if branch not in self.refs:
                # Real GitHub distinguishes "repo has commits, just not on
                # this branch" (404) from "repo has zero commits anywhere"
                # (409) — the latter only when there are no refs at all.
                if not self.refs:
                    return _Response(409, {"message": "Git Repository is empty."})
                return _Response(404, {"message": "Not Found"})
            return _Response(200, {"ref": f"refs/heads/{branch}",
                                   "object": {"sha": self.refs[branch],
                                              "type": "commit"}})

        m = _RX_REFS.match(path)
        if m and method == "POST":
            ref = (body or {}).get("ref", "")
            if not ref.startswith("refs/heads/"):
                return _Response(422, {"message": "Invalid ref"})
            branch = ref[len("refs/heads/"):]
            if branch in self.refs:
                return _Response(422, {"message": "Reference already exists"})
            self.refs[branch] = (body or {})["sha"]
            return _Response(201, {"ref": ref,
                                   "object": {"sha": self.refs[branch]}})

        m = _RX_PATCH_REF.match(path)
        if m and method == "PATCH":
            branch = m.group("branch")
            if branch not in self.refs:
                return _Response(422, {"message": "Reference does not exist"})
            self.refs[branch] = (body or {})["sha"]
            return _Response(200, {"object": {"sha": self.refs[branch]}})

        m = _RX_BLOBS.match(path)
        if m and method == "POST":
            if (body or {}).get("encoding") != "base64":
                return _Response(422, {"message": "unsupported encoding"})
            return _Response(201, {"sha": self._put_blob(body["content"])})

        m = _RX_TREES.match(path)
        if m and method == "POST":
            base = (body or {}).get("base_tree")
            merged = dict(self.trees.get(base, {})) if base else {}
            for entry in (body or {}).get("tree", []):
                # Git's data API semantics: a null sha removes the path.
                if entry.get("sha") is None:
                    merged.pop(entry["path"], None)
                else:
                    merged[entry["path"]] = entry["sha"]
            sha = _sha("tree", json.dumps(merged, sort_keys=True))
            self.trees[sha] = merged
            return _Response(201, {"sha": sha})

        m = _RX_COMMITS.match(path)
        if m and method == "POST":
            b = body or {}
            author = b.get("author") or {"name": "octocat",
                                         "email": "octocat@users.noreply.github.test"}
            sha = _sha("commit", b.get("tree", ""), b.get("message", ""),
                       ",".join(b.get("parents", [])), author.get("name", ""),
                       str(len(self.commits)))
            self.commits[sha] = {"tree": b["tree"],
                                 "parents": list(b.get("parents", [])),
                                 "message": b.get("message", ""),
                                 "author": {"name": author.get("name", ""),
                                            "email": author.get("email", "")}}
            return _Response(201, {"sha": sha})

        m = _RX_GET_TREE.match(path)
        if m and method == "GET":
            sha = m.group("sha")
            if sha not in self.trees:
                return _Response(404, {"message": "Not Found"})
            return _Response(200, {
                "sha": sha,
                "tree": [{"path": p, "mode": "100644", "type": "blob",
                          "sha": bsha,
                          "size": len(base64.b64decode(
                              self.blobs.get(bsha, "")))}
                         for p, bsha in self.trees[sha].items()]})

        m = _RX_GET_COMMIT.match(path)
        if m and method == "GET":
            sha = m.group("sha")
            if sha not in self.commits:
                return _Response(404, {"message": "Not Found"})
            c = self.commits[sha]
            return _Response(200, {"sha": sha, "tree": {"sha": c["tree"]},
                                   "parents": [{"sha": p} for p in c["parents"]],
                                   "message": c["message"]})

        m = _RX_CONTENTS.match(path)
        if m and method == "GET":
            rel = m.group("path")
            ref = (params or {}).get("ref") or self.default_branch
            if ref not in self.refs:
                return _Response(404, {"message": "Not Found"})
            tree = self.trees[self.commits[self.refs[ref]]["tree"]]
            if rel not in tree:
                return _Response(404, {"message": "Not Found"})
            return _Response(200, {"type": "file", "path": rel,
                                   "encoding": "base64",
                                   "content": self.blobs[tree[rel]]})

        m = _RX_PULLS.match(path)
        if m and method == "POST":
            if self.fail_pull_with:
                return _Response(self.fail_pull_with,
                                 {"message": "Validation Failed"})
            b = body or {}
            if b.get("head") not in self.refs:
                return _Response(422, {"message": f"no branch {b.get('head')}"})
            number = len(self.pulls) + 1
            pr = {"number": number, "title": b.get("title"),
                  "body": b.get("body"), "head": b.get("head"),
                  "base": b.get("base"),
                  "html_url": f"https://github.com/{self.repo}/pull/{number}"}
            self.pulls.append(pr)
            return _Response(201, pr)

        m = _RX_COMPARE.match(path)
        if m and method == "GET":
            return self._compare(m.group("spec"))

        m = _RX_MERGES.match(path)
        if m and method == "POST":
            return self._merge(body or {})

        m = _RX_PAGES.match(path)
        if m and method == "POST":
            if self.fail_pages_with:
                return _Response(self.fail_pages_with,
                                 {"message": "Resource not accessible by "
                                             "personal access token"})
            if self.pages is not None:
                # Real GitHub 409s when the site already exists.
                return _Response(409, {"message": "Pages site already exists"})
            self.pages = dict(body or {})
            return _Response(201, {
                "html_url": f"https://{self.repo.split('/')[0]}.github.io/"
                            f"{self.repo.split('/')[1]}/",
                "build_type": (body or {}).get("build_type")})

        return _Response(404, {"message": f"fake-github: unrouted {method} {path}"})

    # ---------------------------------------------------------------- #
    # graph helpers (compare / merge)

    def _ancestors(self, sha: str) -> list[str]:
        """sha and every ancestor, nearest-first (BFS)."""
        seen, order, queue = set(), [], [sha]
        while queue:
            cur = queue.pop(0)
            if cur in seen or cur not in self.commits:
                continue
            seen.add(cur)
            order.append(cur)
            queue.extend(self.commits[cur]["parents"])
        return order

    def _merge_base(self, a: str, b: str):
        anc_b = set(self._ancestors(b))
        for cur in self._ancestors(a):
            if cur in anc_b:
                return cur
        return None

    def _tree_of(self, sha) -> dict:
        if not sha:
            return {}
        return self.trees[self.commits[sha]["tree"]]

    def _commit_json(self, sha: str) -> dict:
        c = self.commits[sha]
        return {"sha": sha, "parents": [{"sha": p} for p in c["parents"]],
                "commit": {"message": c["message"],
                           "author": {**c.get("author", {}),
                                      "date": "2026-01-01T00:00:00Z"}},
                "author": None}

    def _compare(self, spec: str) -> _Response:
        if "..." not in spec:
            return _Response(404, {"message": "Not Found"})
        base, head = spec.split("...", 1)
        if base not in self.refs or head not in self.refs:
            return _Response(404, {"message": "Not Found"})
        b, h = self.refs[base], self.refs[head]
        mb = self._merge_base(b, h)
        anc_b, anc_h = set(self._ancestors(b)), set(self._ancestors(h))
        head_only = [c for c in self._ancestors(h) if c not in anc_b]
        base_only = [c for c in self._ancestors(b) if c not in anc_h]
        ahead, behind = len(head_only), len(base_only)
        status = ("identical" if not ahead and not behind else
                  "ahead" if ahead and not behind else
                  "behind" if behind and not ahead else "diverged")
        mb_tree, h_tree = self._tree_of(mb), self._tree_of(h)
        files = sorted(p for p in set(mb_tree) | set(h_tree)
                       if mb_tree.get(p) != h_tree.get(p))
        return _Response(200, {
            "status": status, "ahead_by": ahead, "behind_by": behind,
            "commits": [self._commit_json(c) for c in reversed(head_only)],
            "files": [{"filename": f} for f in files]})

    def _merge(self, body: dict) -> _Response:
        into, src = body.get("base"), body.get("head")
        if into not in self.refs or src not in self.refs:
            return _Response(404, {"message": "Not Found"})
        a, b = self.refs[into], self.refs[src]
        if b in self._ancestors(a):
            return _Response(204, None)          # already up to date
        mb = self._merge_base(a, b)
        if mb == a:                               # fast-forward
            self.refs[into] = b
            return _Response(201, {"sha": b})
        base_t, ours, theirs = (self._tree_of(mb), self._tree_of(a),
                                self._tree_of(b))
        merged = {}
        for path in set(base_t) | set(ours) | set(theirs):
            bv, ov, tv = base_t.get(path), ours.get(path), theirs.get(path)
            if ov == tv:
                val = ov
            elif ov == bv:
                val = tv
            elif tv == bv:
                val = ov
            else:
                return _Response(409, {"message": "Merge conflict"})
            if val is not None:
                merged[path] = val
        tsha = _sha("tree", json.dumps(merged, sort_keys=True))
        self.trees[tsha] = merged
        csha = _sha("commit", tsha, a, b, body.get("commit_message", ""))
        self.commits[csha] = {"tree": tsha, "parents": [a, b],
                              "message": body.get("commit_message")
                              or f"Merge {src} into {into}",
                              "author": {"name": "octocat", "email": ""}}
        self.refs[into] = csha
        return _Response(201, {"sha": csha})

    # ---------------------------------------------------------------- #
    # test helpers

    def add_commit(self, branch: str, files: dict, message: str = "edit",
                   author_name: str = "Jane Dev",
                   author_email: str = "jane@example.com") -> str:
        """Commit `files` ({path: text, None deletes}) onto `branch` as a
        given author - simulates someone pushing outside the builder."""
        parent = self.refs[branch]
        tree = dict(self._tree_of(parent))
        for path, text in files.items():
            if text is None:
                tree.pop(path, None)
            else:
                tree[path] = self._put_blob(
                    base64.b64encode(text.encode("utf-8")).decode("ascii"))
        tsha = _sha("tree", json.dumps(tree, sort_keys=True))
        self.trees[tsha] = tree
        csha = _sha("commit", tsha, message, parent, str(len(self.commits)))
        self.commits[csha] = {"tree": tsha, "parents": [parent],
                              "message": message,
                              "author": {"name": author_name,
                                         "email": author_email}}
        self.refs[branch] = csha
        return csha

    def _get_repo(self, m) -> _Response:
        full = f"{m.group(1)}/{m.group(2)}"
        if full != self.repo:
            return _Response(404, {"message": "Not Found"})
        payload = {"full_name": full, "default_branch": self.default_branch,
                   "private": True}
        if self.include_permissions:
            payload["permissions"] = {"admin": False, "push": self.can_push,
                                      "pull": True}
        return _Response(200, payload)
