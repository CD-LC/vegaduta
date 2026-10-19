"""Code execution with isolated workspaces and a fix-and-retry loop.

Industry pattern (SWE-agent / Reflexion style): the agent iterates
  run -> observe structured failure -> apply corrected files -> run again
against an ISOLATED WORKSPACE, never the primary checkout. This module
provides the deterministic half of that loop; the LLM half (deciding the
fix) lives in the calling agent, which sees each failure as structured
output designed to be acted on.

Safety model:
- Execution is DISABLED unless CODE_INTEL_ALLOW_EXEC=true (fail closed).
- Every run happens inside a scratch workspace under the tenant's subtree
  (git worktree when the source is a git checkout; rsync-copy otherwise).
  The primary checkout and the index are never mutated.
- Hard timeout per run, output capped, env scrubbed to a minimal allowlist
  (no service secrets leak into tenant code).
- Deterministic retries (`attempts`) rerun UNCHANGED code to absorb flaky
  tests; they are distinct from rework iterations (changed code).

For untrusted multi-tenant production, point RUN_MODE at "docker" (one
throwaway container per run) or wire the platform's existing E2B sandbox
credentials; "local" mode is for the dev/self-hosted single-org case.

RUN_MODE (see settings.py):
- "local"  - bare subprocess in this container/host (unchanged, default).
- "docker" - one throwaway container per run_command call via the `docker`
  Python SDK (not the CLI, so behavior is identical whether this service
  runs on Windows or Linux): no network, memory/cpu/pids capped, workspace
  bind-mounted read-write, removed on exit.
- "e2b"    - a real E2B (https://e2b.dev) cloud sandbox via the `e2b` SDK;
  requires E2B_API_KEY.

Both sandboxed modes also back two new capabilities that plain run_command
can't express - `start_process`/`stop_process`/`process_status` - for
LONG-RUNNING processes (e.g. `npm run dev`) that a consumer-facing "app
builder" needs a live preview URL/port for. `local` mode refuses
start_process outright: an unsandboxed long-running process on the host is
exactly the risk this module exists to close.
"""

from __future__ import annotations

import os
import subprocess
import threading
import time
import uuid

from . import sandbox_sdk
from .settings import SETTINGS
from .textcap import tail as _tail
from .workspace import (
    WorkspaceError,
    apply_files,
    create_workspace,
    delete_workspace,
    diff_workspace,
    load_workspace,
)

# Workspace lifecycle lives in workspace.py; it is re-exported here because
# callers treat executor as the one entry point for "workspace + run".
__all__ = [
    "ENV_ALLOWLIST",
    "ExecDisabledError",
    "WorkspaceError",
    "apply_files",
    "create_workspace",
    "delete_workspace",
    "diff_workspace",
    "is_busy",
    "load_workspace",
    "process_status",
    "run_command",
    "start_process",
    "stop_process",
]

ENV_ALLOWLIST = ("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TZ",
                 "JAVA_HOME", "MAVEN_HOME", "GRADLE_HOME", "NODE_PATH",
                 "PYTHONPATH", "GOPATH", "CARGO_HOME", "DOTNET_ROOT")

class ExecDisabledError(PermissionError):
    pass


# Workspaces currently mid-execution (inside run_command). The reaper
# (reaper.py) checks this before deleting a stale-by-TTL workspace, so it
# never yanks a workspace out from under a running command. In-memory only
# (this process runs run_command and the reaper in the same interpreter -
# FastAPI sync routes execute in the app's threadpool, sharing this
# module's state with the asyncio reaper task).
_BUSY_LOCK = threading.Lock()
_BUSY_WORKSPACES: set[str] = set()


def is_busy(ws_id: str) -> bool:
    """True while `ws_id` is actively running a command."""
    with _BUSY_LOCK:
        return ws_id in _BUSY_WORKSPACES


# Long-running processes started by start_process (docker containers / e2b
# sandboxes), keyed by process_id. In-memory only - same caveat as
# _BUSY_WORKSPACES: this process's own restart orphans the tracking record
# here (the container/sandbox itself keeps running). Docker containers are
# tagged with a `codeintel.ws_id`/`codeintel.process_id` label specifically
# so an operator (or a future reaper sweep, not written here) can find and
# clean up anything orphaned this way by label instead of by this dict.
_PROCESS_LOCK = threading.Lock()
_PROCESSES: dict[str, dict] = {}


def _scrubbed_env() -> dict[str, str]:
    env = {k: v for k, v in os.environ.items() if k in ENV_ALLOWLIST}
    # Ephemeral workspaces are rewritten between rework iterations; never
    # let Python serve stale bytecode from a prior iteration's __pycache__
    # (same-second, same-size edits would otherwise not invalidate it).
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


# ------------------------------------------------------------------ #
# execution

def _attempt_local(ws_path: str, command: str, timeout: int,
                   env: dict[str, str]) -> dict:
    """One run_command attempt, bare subprocess on this host. UNCHANGED
    logic from before RUN_MODE existed - this is the regression-safety
    branch (see tests/test_run_modes.py)."""
    started = time.time()
    try:
        r = subprocess.run(
            ["bash", "-lc", command] if os.name != "nt"
            else ["cmd", "/c", command],
            cwd=ws_path, env=env, capture_output=True,
            text=True, timeout=timeout)
        return {
            "exit_code": r.returncode,
            "duration_seconds": round(time.time() - started, 2),
            "stdout": _tail(r.stdout),
            "stderr": _tail(r.stderr),
            "timed_out": False,
        }
    except subprocess.TimeoutExpired as e:
        return {
            "exit_code": None,
            "duration_seconds": round(time.time() - started, 2),
            "stdout": _tail(e.stdout or "" if isinstance(e.stdout, str)
                            else (e.stdout or b"").decode("utf-8", "replace")),
            "stderr": f"timed out after {timeout}s",
            "timed_out": True,
        }


def _attempt_docker(ws_path: str, command: str, timeout: int,
                    env: dict[str, str], image: str | None) -> dict:
    """One run_command attempt in a throwaway docker container. docker-py's
    `containers.run` has no clean per-call timeout, so this runs detached
    and enforces the timeout itself via `container.wait(timeout=...)`,
    killing + removing the container either way."""
    client = sandbox_sdk.get_docker_client(SETTINGS)
    img = image or SETTINGS.docker_image
    started = time.time()
    container = None
    try:
        try:
            container = client.containers.run(
                img, **sandbox_sdk.docker_run_kwargs(ws_path, command, env,
                                                     SETTINGS, detach=True))
        except Exception as e:
            raise WorkspaceError(
                f"run_mode='docker': failed to start container "
                f"(image={img!r}) - check the image exists/can be pulled "
                f"and the Docker daemon is reachable: {e}") from e

        timed_out = False
        exit_code = None
        try:
            wait_result = container.wait(timeout=timeout)
            exit_code = (wait_result.get("StatusCode")
                        if isinstance(wait_result, dict) else wait_result)
        except Exception:
            # docker-py surfaces a timed-out wait() as a client-side
            # exception (e.g. requests.exceptions.ReadTimeout), not a
            # return value - treat any of those as OUR timeout, not a
            # fatal error.
            timed_out = True

        try:
            stdout = container.logs(stdout=True, stderr=False).decode(
                "utf-8", "replace")
            stderr = container.logs(stdout=False, stderr=True).decode(
                "utf-8", "replace")
        except Exception:
            stdout, stderr = "", ""

        if timed_out:
            try:
                container.kill()
            except Exception:
                pass
            stderr = f"timed out after {timeout}s"

        return {
            "exit_code": exit_code,
            "duration_seconds": round(time.time() - started, 2),
            "stdout": _tail(stdout),
            "stderr": _tail(stderr),
            "timed_out": timed_out,
        }
    finally:
        if container is not None:
            try:
                container.remove(force=True)
            except Exception:
                pass


def _attempt_e2b(ws_path: str, command: str, timeout: int,
                 env: dict[str, str], image: str | None) -> dict:
    """One run_command attempt in an E2B cloud sandbox. Creates a fresh
    sandbox per attempt (simple, matches the docker branch's throwaway-
    per-run posture) and always kills it in `finally`."""
    started = time.time()
    sandbox = sandbox_sdk.e2b_create_sandbox(
        image or SETTINGS.e2b_template, timeout + 30, SETTINGS.e2b_api_key)
    try:
        sandbox_sdk.e2b_upload_workspace(sandbox, ws_path)
        timed_out = False
        exit_code = None
        stdout = stderr = ""
        try:
            result = sandbox.commands.run(
                command, cwd="/workspace", envs=env, timeout=timeout)
            exit_code = result.exit_code
            stdout = result.stdout or ""
            stderr = result.stderr or ""
        except Exception as e:
            # Foreground commands.run() raises on the SDK's own connection/
            # command timeout (and on non-zero exit in some SDK versions,
            # which the exit_code branch above already handles when it
            # doesn't raise) - anything unexpected here is treated as a
            # timeout so the caller gets a structured result either way.
            timed_out = True
            stderr = f"timed out after {timeout}s: {e}"
        return {
            "exit_code": exit_code,
            "duration_seconds": round(time.time() - started, 2),
            "stdout": _tail(stdout),
            "stderr": _tail(stderr),
            "timed_out": timed_out,
        }
    finally:
        try:
            sandbox.kill()
        except Exception:
            pass


def run_command(ws_path: str, command: str, timeout: int = 300,
                attempts: int = 1, ws_id: str | None = None,
                run_mode: str | None = None, image: str | None = None,
                env_overrides: dict | None = None) -> dict:
    """Run `command` in the workspace. `attempts` > 1 reruns UNCHANGED
    code on failure (flaky-test absorption) - distinct from rework.

    `run_mode` (None = use SETTINGS.run_mode) picks local subprocess /
    docker-per-run / E2B sandbox; the RESULT SHAPE is identical across all
    three so callers never need to know which one ran. `image` overrides
    the default container image (docker) or template (e2b) for this call.
    `env_overrides` is merged OVER the existing scrubbed-allowlist env
    (e.g. a BaaS-provisioned DATABASE_URL) - it does not replace the scrub.

    `ws_id`, when given, is marked busy (see is_busy()) for the duration
    of this call so the TTL reaper won't delete the workspace mid-run."""
    attempts = max(1, min(attempts, 5))
    timeout = max(1, min(timeout, 1800))
    mode = (run_mode or SETTINGS.run_mode or "local").lower()
    env = _scrubbed_env()
    if env_overrides:
        env.update({str(k): str(v) for k, v in env_overrides.items()})
    if ws_id:
        with _BUSY_LOCK:
            _BUSY_WORKSPACES.add(ws_id)
    try:
        history = []
        for attempt in range(1, attempts + 1):
            if mode == "local":
                result = _attempt_local(ws_path, command, timeout, env)
            elif mode == "docker":
                result = _attempt_docker(ws_path, command, timeout, env, image)
            elif mode == "e2b":
                result = _attempt_e2b(ws_path, command, timeout, env, image)
            else:
                raise WorkspaceError(
                    f"unknown run_mode: {mode!r} (expected local|docker|e2b)")
            result["attempt"] = attempt
            history.append(result)
            if result["exit_code"] == 0:
                break
    finally:
        if ws_id:
            with _BUSY_LOCK:
                _BUSY_WORKSPACES.discard(ws_id)
    final = history[-1]
    success = final["exit_code"] == 0
    out = {
        "success": success,
        "command": command,
        "attempts_used": len(history),
        "final": final,
        "flaky": success and len(history) > 1,
    }
    if not success:
        # Structured guidance for the agent's rework step - this text is
        # what the LLM sees, so it states the loop explicitly.
        out["rework_hint"] = (
            "The command failed. Inspect final.stderr/final.stdout, then "
            "call apply_files on this workspace with corrected full file "
            "contents and call run_code again with the same command. "
            "Repeat until success, then call workspace_diff to get the "
            "final patch.")
    return out


# ------------------------------------------------------------------ #
# long-running processes (live-preview dev servers) - docker/e2b only.
# See module docstring: unsandboxed long-running host processes are
# exactly the risk this file exists to close, so "local" is one-shot-only.

def start_process(ws_path: str, command: str, run_mode: str | None = None,
                  image: str | None = None, port: int | None = None,
                  env_overrides: dict | None = None,
                  ws_id: str | None = None) -> dict:
    """Start a LONG-RUNNING process (e.g. `npm run dev`) and return
    immediately with a `process_id` - NOT captured output, unlike
    run_command. Use process_status/stop_process afterwards.

    docker: returns {process_id, run_mode, host, port, container_id} - the
    container is started detached with `port` published to an
    ephemeral host port docker assigns (read back from the container's own
    attrs after start).
    e2b: returns {process_id, run_mode, preview_url, sandbox_id} - `port`
    is exposed via the sandbox's built-in per-port HTTPS preview URL
    (E2B's `get_host`), which needs no explicit port-publish step.
    local: always raises WorkspaceError - see module docstring."""
    mode = (run_mode or SETTINGS.run_mode or "local").lower()
    if mode == "local":
        raise WorkspaceError(
            "run_mode='local' does not support start_process: a "
            "long-running unsandboxed process on the host is exactly the "
            "risk RUN_MODE exists to close. Use run_mode='docker' or "
            "'e2b' for live-preview / dev-server processes.")

    env = _scrubbed_env()
    if env_overrides:
        env.update({str(k): str(v) for k, v in env_overrides.items()})
    process_id = "proc-" + uuid.uuid4().hex[:12]

    if mode == "docker":
        client = sandbox_sdk.get_docker_client(SETTINGS)
        img = image or SETTINGS.docker_image
        ports: dict[str, int | None] | None = (
            {f"{port}/tcp": None} if port else None)
        labels = {"codeintel.ws_id": ws_id or "",
                  "codeintel.process_id": process_id}
        try:
            container = client.containers.run(
                img, **sandbox_sdk.docker_run_kwargs(ws_path, command, env,
                                                     SETTINGS, detach=True,
                                                     ports=ports, labels=labels))
        except Exception as e:
            raise WorkspaceError(
                f"run_mode='docker': failed to start long-running "
                f"container (image={img!r}): {e}") from e
        host_port = None
        if port:
            try:
                container.reload()
                bindings = (container.attrs.get("NetworkSettings", {})
                                     .get("Ports", {}).get(f"{port}/tcp"))
                if bindings:
                    host_port = int(bindings[0]["HostPort"])
            except Exception:
                host_port = None
        with _PROCESS_LOCK:
            _PROCESSES[process_id] = {
                "run_mode": "docker", "ws_id": ws_id, "container": container,
                "container_id": container.id, "port": port,
                "host_port": host_port, "started_at": time.time(),
            }
        return {"process_id": process_id, "run_mode": "docker",
                "host": container.name, "port": host_port,
                "container_id": container.id}

    if mode == "e2b":
        sandbox = sandbox_sdk.e2b_create_sandbox(
            image or SETTINGS.e2b_template, 3600, SETTINGS.e2b_api_key)
        try:
            sandbox_sdk.e2b_upload_workspace(sandbox, ws_path)
            try:
                sandbox.commands.run(command, cwd="/workspace", envs=env,
                                     background=True)
            except Exception as e:
                raise WorkspaceError(
                    f"run_mode='e2b': failed to start process: {e}") from e
        except Exception:
            try:
                sandbox.kill()
            except Exception:
                pass
            raise
        preview_url = None
        if port:
            try:
                preview_url = f"https://{sandbox.get_host(port)}"
            except Exception:
                preview_url = None
        sandbox_id = getattr(sandbox, "sandbox_id", None)
        with _PROCESS_LOCK:
            _PROCESSES[process_id] = {
                "run_mode": "e2b", "ws_id": ws_id, "sandbox": sandbox,
                "sandbox_id": sandbox_id, "port": port,
                "preview_url": preview_url, "started_at": time.time(),
            }
        return {"process_id": process_id, "run_mode": "e2b",
                "preview_url": preview_url, "sandbox_id": sandbox_id}

    raise WorkspaceError(
        f"unknown run_mode: {mode!r} (expected docker|e2b for start_process)")


def stop_process(process_id: str) -> None:
    """Stop + remove/kill the tracked container or sandbox. Silently a
    no-op on the underlying stop/remove call failing (best-effort cleanup)
    but raises WorkspaceError if `process_id` isn't tracked at all."""
    with _PROCESS_LOCK:
        record = _PROCESSES.pop(process_id, None)
    if record is None:
        raise WorkspaceError(f"process not found: {process_id}")
    if record["run_mode"] == "docker":
        container = record["container"]
        try:
            container.stop(timeout=5)
        except Exception:
            pass
        try:
            container.remove(force=True)
        except Exception:
            pass
    elif record["run_mode"] == "e2b":
        try:
            record["sandbox"].kill()
        except Exception:
            pass


def process_status(process_id: str) -> dict:
    """{process_id, running, exit_code} plus the same host/port or
    preview_url fields start_process returned for this process."""
    with _PROCESS_LOCK:
        record = _PROCESSES.get(process_id)
    if record is None:
        raise WorkspaceError(f"process not found: {process_id}")
    if record["run_mode"] == "docker":
        container = record["container"]
        try:
            container.reload()
            running = container.status == "running"
            exit_code = (None if running
                        else container.attrs.get("State", {}).get("ExitCode"))
        except Exception:
            running, exit_code = False, None
        return {"process_id": process_id, "running": running,
                "exit_code": exit_code, "run_mode": "docker",
                "host": container.name, "port": record.get("host_port"),
                "container_id": record.get("container_id")}
    # e2b
    sandbox = record["sandbox"]
    try:
        running = sandbox.is_running()
    except Exception:
        running = False
    return {"process_id": process_id, "running": running,
            "exit_code": None, "run_mode": "e2b",
            "preview_url": record.get("preview_url"),
            "sandbox_id": record.get("sandbox_id")}
