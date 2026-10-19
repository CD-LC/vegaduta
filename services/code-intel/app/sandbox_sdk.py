"""Sandbox SDK plumbing for the docker and e2b run modes (see executor.py).

How a container or an E2B sandbox is created and configured: the lazily
imported SDKs, the hardened `containers.run` kwargs and the workspace
upload. Every function takes the settings it needs as an argument, so
executor.py stays the one place that reads SETTINGS and decides WHICH mode
runs.
"""

from __future__ import annotations

import os
from typing import Any

from .settings import Settings
from .workspace import WorkspaceError

# docker run's blast-radius caps and hardening (user, read-only root fs,
# /tmp tmpfs, cap_drop, no-new-privileges) now live in settings.py
# (CODE_INTEL_DOCKER_*) - see docker_run_kwargs.
_DOCKER_NOBODY = "65534:65534"
# /tmp INSIDE the sandbox container (its private tmpfs), not this host's /tmp.
_CONTAINER_TMP = "/tmp"  # nosec B108


def get_docker_client(settings: Settings) -> Any:
    """Lazy import (like codebase_rag/contextualizer.py's `import
    anthropic`) so code-intel doesn't hard-require the `docker` package
    when nobody uses run_mode=docker. Raises a clear, operator-actionable
    WorkspaceError instead of letting a raw docker-py/connection exception
    leak to the caller."""
    try:
        import docker  # type: ignore
    except ImportError as e:
        raise WorkspaceError(
            "run_mode='docker' requires the 'docker' package "
            "(pip install docker) plus a reachable Docker daemon") from e
    try:
        if settings.docker_host:
            return docker.DockerClient(base_url=settings.docker_host)
        return docker.from_env()
    except Exception as e:
        raise WorkspaceError(
            "run_mode='docker': cannot reach the Docker daemon "
            f"({settings.docker_host or 'local socket/env default'}) - "
            f"is Docker running and DOCKER_HOST configured correctly? {e}"
        ) from e


def docker_user(settings: Settings) -> str | None:
    """Resolve settings.docker_user to the `user=` value for containers.run.

    "auto" = this service process's own uid:gid, so the container can write
    the workspaces this process created without any chown - but never root:
    a root service process (or a platform without getuid, i.e. Windows dev
    boxes) falls back to nobody. "" = pass no user (image default)."""
    configured = (settings.docker_user or "").strip()
    if not configured:
        return None
    if configured.lower() == "auto":
        getuid = getattr(os, "getuid", None)
        getgid = getattr(os, "getgid", None)
        if getuid is None or getgid is None:
            return _DOCKER_NOBODY
        uid, gid = getuid(), getgid()
        if uid == 0:
            return _DOCKER_NOBODY
        return f"{uid}:{gid}"
    return configured


def docker_run_kwargs(ws_path: str, command: str,
                      env: dict[str, str], settings: Settings, *, detach: bool,
                      ports: dict[str, int | None] | None = None,
                      labels: dict[str, str] | None = None
                      ) -> dict[str, object]:
    """Shared kwargs between the one-shot (run_command) and long-running
    (start_process) docker branches - with the default settings, equivalent
    to:
    `docker run --rm --network none --memory=1g --cpus=1 --pids-limit=256
     --user <service uid:gid, never 0> --read-only
     --tmpfs /tmp:rw,exec,nosuid,nodev,size=512m,mode=1777
     --cap-drop ALL --security-opt no-new-privileges:true
     -v {ws_path}:/workspace:rw -w /workspace -e KEY=VAL ... -e HOME=/tmp
     {image} bash -lc "{command}"`.

    Hardening (SVC-002, each knob in settings.py as CODE_INTEL_DOCKER_*):
    non-root numeric user, read-only root fs, a size-capped /tmp tmpfs, all
    capabilities dropped, no-new-privileges, no network. The /workspace bind
    mount is the only persistent writable path; it must be writable by the
    configured uid (settings.docker_user - default "auto" = the service's own
    uid, which owns the workspaces; an explicit uid needs an operator chown).
    """
    container_env = dict(env)
    if settings.docker_read_only:
        # The host's HOME (if it survived the allowlist) means nothing inside
        # the container, and with a read-only root fs the image's own HOME
        # isn't writable either: point npm/pip/cargo caches at the tmpfs.
        container_env["HOME"] = _CONTAINER_TMP
    kwargs: dict[str, object] = {
        "command": ["bash", "-lc", command],
        "working_dir": "/workspace",
        "volumes": {os.path.abspath(ws_path): {"bind": "/workspace", "mode": "rw"}},
        "environment": container_env,
        "network_mode": "none",
        "mem_limit": settings.docker_mem_limit,
        "nano_cpus": settings.docker_nano_cpus,
        "pids_limit": settings.docker_pids_limit,
        "read_only": settings.docker_read_only,
        "detach": detach,
    }
    user = docker_user(settings)
    if user:
        kwargs["user"] = user
    if settings.docker_tmpfs_size:
        # exec kept by default (settings.docker_tmpfs_exec): build tooling
        # routinely executes helpers it unpacked into /tmp. nosuid/nodev
        # always - there is no legitimate need for either on scratch space.
        exec_opt = "exec" if settings.docker_tmpfs_exec else "noexec"
        kwargs["tmpfs"] = {
            _CONTAINER_TMP: (f"rw,{exec_opt},nosuid,nodev,"
                     f"size={settings.docker_tmpfs_size},mode=1777")}
    cap_drop = [c.strip() for c in settings.docker_cap_drop.split(",") if c.strip()]
    if cap_drop:
        kwargs["cap_drop"] = cap_drop
    if settings.docker_no_new_privileges:
        kwargs["security_opt"] = ["no-new-privileges:true"]
    if ports:
        kwargs["ports"] = ports
    if labels:
        kwargs["labels"] = labels
    return kwargs


def _e2b_sandbox_class() -> Any:
    """Lazy import of the E2B SDK (see get_docker_client's docstring for
    why: no hard dependency unless run_mode=e2b is actually used)."""
    try:
        from e2b import Sandbox  # type: ignore
    except ImportError as e:
        raise WorkspaceError(
            "run_mode='e2b' requires the 'e2b' package "
            "(pip install e2b)") from e
    return Sandbox


def e2b_create_sandbox(template: str, timeout: int, api_key: str | None) -> Any:
    if not api_key:
        raise WorkspaceError(
            "run_mode='e2b' requires E2B_API_KEY to be configured "
            "(CODE_INTEL_E2B_TEMPLATE selects which sandbox template)")
    Sandbox = _e2b_sandbox_class()
    try:
        return Sandbox.create(template=template, api_key=api_key,
                              timeout=timeout)
    except WorkspaceError:
        raise
    except Exception as e:
        raise WorkspaceError(f"run_mode='e2b': failed to create sandbox: {e}") from e


def e2b_upload_workspace(sandbox: Any, ws_path: str) -> None:
    """Best-effort upload of the workspace tree into the sandbox's
    /workspace over the filesystem API (E2B has no directory-upload
    helper in this SDK version). Skips the same non-essential directories
    create_workspace's copytree already ignores for local-source repos."""
    skip_dirs = {".git", "node_modules", "__pycache__", ".coderag"}
    for root, dirs, files in os.walk(ws_path):
        dirs[:] = [d for d in dirs if d not in skip_dirs]
        for name in files:
            local_path = os.path.join(root, name)
            rel = os.path.relpath(local_path, ws_path).replace("\\", "/")
            try:
                with open(local_path, "rb") as f:
                    sandbox.files.write(f"/workspace/{rel}", f.read())
            except Exception:
                continue  # best-effort - one unreadable/binary-weird file shouldn't abort the run
