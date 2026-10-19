# E2B Code Execution Template

Master Roadmap 2026 item #14 (E2B code execution completion, §B).

## Why this exists

E2B's own in-sandbox command execution (the `envd` agent's Process API) is an
undocumented Connect-RPC service with no official Java/Kotlin SDK and no
published `.proto` spec - see `E2bCodeExecutionClient.java`'s javadoc for the
full reasoning. Rather than reverse-engineer that wire format, each tenant
builds and pushes this template: a minimal container that runs `runner.py`,
a stdlib-only HTTP server exposing exactly one contract we define and own
(`POST /run`, `GET /health`). `E2bCodeExecutionClient` talks to that contract
over plain HTTPS, using E2B's own documented sandbox-host pattern
(`https://{port}-{sandboxId}.e2b.app`).

## One-time setup (per tenant)

E2B's V1 build system (`e2b template build`, driven by `e2b.toml`) is
deprecated and no longer builds anything; the command is a stub since
E2B disabled V1 access tokens on 2026-08-01 (see
<https://docs.e2b.dev/migration/v1-build-deprecation>). The current flow is
`e2b template create`, which builds the Dockerfile directly and authenticates
with an API key.

1. Install the E2B CLI: `npm install -g @e2b/cli` (or `brew install e2b`).
2. Put the tenant's E2B API key in the environment: `export E2B_API_KEY=...`
   (`e2b template create` authenticates with the API key; `e2b auth info`
   and `e2b template create --help` show whether a logged-in CLI session
   also supplies the team key, which is not relied on here).
3. From this directory, build the template. The start and ready commands
   are the two values recorded in `e2b.toml`; the CLI does not read that
   file, so they are passed explicitly:
   ```
   cd core/sdlc/e2b-template
   e2b template create vegaduta-code-exec \
     --dockerfile Dockerfile \
     --cmd "python3 /runner.py" \
     --ready-cmd "curl -s http://localhost:8080/health"
   ```
   Optional: `--cpu-count 2 --memory-mb 1024` (the defaults), `--no-cache`
   to skip E2B's layer cache. The build runs on E2B's side (a local Docker
   daemon is not needed); it takes a few minutes the first time because the
   pinned base image is several GB.
4. The CLI prints the template name and its template ID when the build
   finishes. The platform passes the registered value straight through to
   E2B's create-sandbox API, which accepts either the name or the ID. Copy
   one of them.
5. Register it with the platform:
   ```
   PUT /api/sdlc/credential/code-execution-template
   { "templateId": "<the template name or ID from step 4>" }
   ```
   (Requires an active E2B credential - `POST /api/sdlc/credential` - configured first.)

### Alternative: the E2B SDK build flow

Tenants that build templates from CI with the E2B SDK (version 2.3.0 or
later) can generate an SDK project from the files in this directory.
**`migrate` modifies its inputs**: after writing the SDK project it renames
`e2b.toml` to `e2b.toml.old` and the Dockerfile to `e2b.Dockerfile.old`
(<https://docs.e2b.dev/migration/template-v2>). Never run it inside the
checkout; run it on a scratch copy so the tracked files stay untouched:

```
rm -rf /tmp/e2b-migrate && cp -r core/sdlc/e2b-template /tmp/e2b-migrate
cd /tmp/e2b-migrate
e2b template migrate --config e2b.toml --dockerfile Dockerfile --language python-sync
```

(`--config e2b.toml` names the CLI's default config file explicitly.) If it
was run in the checkout by mistake, restore the inputs with
`git checkout -- core/sdlc/e2b-template/Dockerfile core/sdlc/e2b-template/e2b.toml`
and delete the `*.old` files before committing.

`migrate` reads `Dockerfile` and `e2b.toml` and writes `template.py`,
`build_dev.py` and `build_prod.py`; `python build_prod.py` (run from the
scratch copy, which also holds `runner.py` for the `COPY` step) then builds
the template. The generated `template.py` is equivalent to
`Template().from_dockerfile("Dockerfile").set_start_cmd("python3 /runner.py",
"curl -s http://localhost:8080/health")`, which can also be written by hand.
`Template.build(...)` returns the template name, template ID and build ID;
register the name or ID exactly as in step 5 above. Do not commit the
generated files to this repository; this directory is the source of truth.

### Rebuilding

Rebuild (same command, same name) and re-register whenever `Dockerfile` or
`runner.py` changes. Features that depend on a rebuilt template, such as the
data-analysis sandbox (`app.sandbox.data-analysis.enabled`), must only be
switched on after the rebuilt template is registered.

## Using it

Once configured, `POST /api/sdlc/sandbox/run-code` with:
```json
{ "code": "print('hi')", "language": "python", "timeoutSeconds": 30 }
```
returns `{ "stdout": "...", "stderr": "...", "exitCode": 0, "timedOut": false, "sandboxId": "..." }`.

Each call creates a fresh disposable sandbox from the configured template,
runs the code, and kills the sandbox - no state persists between calls.

## Security model

Isolation comes from the E2B microVM, not from the Linux account inside it.
Each `run-code` call gets its own single-tenant Firecracker microVM created
from the template snapshot and killed when the call returns; nothing of one
tenant's run is reachable from another's, and nothing survives the call.

Inside that microVM the runner and the tenant's script both execute as the
unprivileged account `user` (the `USER user` / `WORKDIR /home/user` at the end
of the Dockerfile; docker:S6471), but `user` has unrestricted passwordless
sudo, so a tenant script can become root *inside its own disposable VM*.
That is the platform's standard sandbox account, and this template cannot
change it from the Dockerfile:

- The pinned base image `e2bdev/code-interpreter` is generated by
  `Template.to_dockerfile(make_template(is_docker=True))` in
  `e2b-dev/code-interpreter` (`template/template.py`, published by
  `.github/workflows/release.yml`). That path appends
  `user ALL=(ALL) NOPASSWD: ALL` to `/etc/sudoers` and sets the image's start
  command to `sudo --preserve-env=E2B_LOCAL /root/.jupyter/start-up.sh`.
- Decisively, E2B's own template build pipeline re-grants it after every
  layer of this Dockerfile has run. The finalize phase executes
  `packages/orchestrator/pkg/template/build/phases/finalize/configure.sh`
  (repository `e2b-dev/infra`) as root in the build VM; that script creates
  `user` if missing, adds it to the `sudo`/`wheel` group, runs
  `passwd -d user` and appends `user ALL=(ALL:ALL) NOPASSWD: ALL` to
  `/etc/sudoers` unless a line starting with that text is already present
  (the guard is `grep -q '^user ALL=(ALL:ALL) NOPASSWD: ALL'`; the base
  image's `user ALL=(ALL) NOPASSWD: ALL` does not match it). It runs before
  the start command and the snapshot, for every template, regardless of the
  Dockerfile. E2B's SDK also relies on it (its devcontainer helper's start
  command is a chain of `sudo ...`).

Consequently a `RUN` step that deletes the base image's rule or narrows it to
`user ALL=(root) NOPASSWD:SETENV: /root/.jupyter/start-up.sh` would be
overwritten by the platform and would only *look* like hardening, which is
worse than the honest comment the Dockerfile carries today. The sudo
behaviour is therefore left unchanged on purpose. Expected observable state
in any sandbox built from this template: `sudo -n true` as `user` exits 0.

### The only real mitigation (plan, not implemented)

The one lever this directory controls is which account the *tenant script*
runs as. `runner.py` could run the submitted code as a second account with
no sudoers entry instead of as itself:

1. Dockerfile, as root: `useradd --create-home --shell /bin/bash --no-user-group sandboxed`
   (any name other than `user`; E2B's finalize phase only touches `user`).
2. `runner.py`: two things it does today only work because the script and
   the interpreter run as the same account, and both must change:
   - the per-run working directory comes from `tempfile.mkdtemp` (mode
     `0o700`, owned by `user`), so `sandboxed` could neither enter it nor
     write artifacts: create it with mode `0o1777`, or `chown` it to
     `sandboxed`;
   - the script file comes from `tempfile.NamedTemporaryFile` (mode `0o600`,
     owned by `user`), so `sandboxed` could not even open it: `os.chmod`
     it to `0o644` before launching (the file holds the tenant's own code,
     nothing secret). Also `mkdtemp`/`NamedTemporaryFile` create under
     `/tmp`, which is world-traversable, so the path itself is reachable.

   Then launch the interpreter via
   `sudo -n --preserve-env=MPLBACKEND -u sandboxed -- <interpreter> <script>`
   (allowed by `user`'s `(ALL:ALL)` rule). The explicit `--preserve-env` is
   required: sudo's default `env_reset` drops the `MPLBACKEND=Agg` the
   runner sets (`env.setdefault` in `runner.py`), leaving the backend to
   whatever matplotlib auto-detects in the sandbox; passing it through keeps
   the `chart.png` check in step 3 independent of that detection, so a
   failure there means the account change broke artifacts, not the
   backend. (`sudo -n -u sandboxed -- env
   MPLBACKEND=Agg <interpreter> <script>` is the equivalent that does not
   depend on sudoers allowing `--preserve-env`.) Collect artifacts as today
   (files written by `sandboxed` are world-readable under the default
   umask). Keep the current `subprocess.run(..., timeout=...)` handling,
   but note that on timeout only `sudo` is killed, so the child must be
   started in its own process group and the group killed; `sudo -n` with a
   non-existent account must fail the run, not fall back to `user`.
3. Verification, which needs a real build and cannot be done from this
   repository: `e2b template create` as above, then with the E2B CLI
   `e2b sandbox spawn <name>` and inside it
   `sudo -n true` (as `user`, expected: exit 0, it is the platform's
   account), `sudo -n -u sandboxed sudo -n true` (expected: non-zero,
   "a password is required"), and `curl -s http://localhost:8080/health`
   (expected: `{"status": "ok"}`). Then from the platform,
   `POST /api/sdlc/sandbox/run-code` with `{"code": "import subprocess;
   print(subprocess.run(['sudo','-n','true']).returncode)", "language":
   "python"}` must print a non-zero code, and a run that writes `chart.png`
   must still return it as an artifact.

This closes in-VM escalation for the tenant script only. It does not change
the trust boundary, which remains the microVM; it is defence in depth for
the case where E2B's sandbox-level isolation is the thing being tested.

## Files

- `runner.py` - the HTTP server (see its own docstring for the full contract).
- `Dockerfile` - based on `e2bdev/code-interpreter` (pinned by digest; the
  tag is informational), adds Node.js for the `node` language option and the
  data-analysis Python packages, copies in `runner.py`, ends as `user`.
- `e2b.toml` - records the start command (`python3 /runner.py`) and readiness
  check that `e2b template create` is given as `--cmd` / `--ready-cmd`; also
  the input of `e2b template migrate`. The V2 CLI does not read it otherwise.
- `test_runner.py` - stdlib unit tests for `runner.py`
  (`python -m unittest core/sdlc/e2b-template/test_runner.py`).

## Supported languages

`python`, `node`, `bash` - see `INTERPRETERS` in `runner.py` to add more.
