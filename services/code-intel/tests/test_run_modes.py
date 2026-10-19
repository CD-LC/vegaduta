"""Tests for RUN_MODE dispatch (local/docker/e2b) in executor.py.

Neither a real Docker daemon nor E2B credentials are available in most
dev/CI environments, so the docker and e2b branches are exercised against
FAKE modules injected into `sys.modules` before executor.py's lazy
`import docker` / `from e2b import Sandbox` run - the same lazy-import
pattern codebase_rag/contextualizer.py already uses for `anthropic`, just
mocked here instead of skipped. That covers (a) the request/config-building
logic (right volumes/limits/env reaching the SDK call) without needing the
real thing.

The one test that matters most for safety is (b): run_mode="local" (the
existing default) must be BYTE-FOR-BYTE unchanged from before RUN_MODE
existed - that's the regression a real Docker/E2B environment can't catch
for you, since it's the mode CI actually exercises for real.

Run: python3 tests/test_run_modes.py   (from the code-intel directory)
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
import types
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import executor, sandbox_sdk
from app.executor import WorkspaceError
from app.settings import Settings

# The sandbox container's own /tmp tmpfs path, not a host temp dir.
TMP = "/tmp"  # nosec B108

PASS = 0
FAIL = 0


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


# ---------------------------------------------------------------------- #
# fake `docker` SDK - just enough of docker-py's surface for
# containers.run(...).wait()/.logs()/.reload()/.stop()/.remove()

class _FakeContainer:
    def __init__(self, image, **kwargs):
        self.image = image
        self.run_kwargs = kwargs
        self.id = "fakecontainerid0123456789"
        self.name = "fake_container"
        self.status = "running"
        self.attrs = {
            "NetworkSettings": {"Ports": {"3000/tcp": [{"HostPort": "54321"}]}},
            "State": {"ExitCode": 0},
        }
        self._removed = False

    def wait(self, timeout=None):
        return {"StatusCode": 0}

    def logs(self, stdout=True, stderr=True):
        if stdout and not stderr:
            return b"hello from container\n"
        if stderr and not stdout:
            return b""
        return b"hello from container\n"

    def kill(self):
        self.status = "exited"

    def stop(self, timeout=5):
        self.status = "exited"

    def remove(self, force=False):
        self._removed = True

    def reload(self):
        pass


class _FakeContainers:
    def __init__(self):
        self.run_calls = []

    def run(self, image, **kwargs):
        self.run_calls.append({"image": image, **kwargs})
        return _FakeContainer(image, **kwargs)


class _FakeDockerClient:
    def __init__(self):
        self.containers = _FakeContainers()


def _install_fake_docker():
    """Injects a fake `docker` module into sys.modules so executor.py's
    lazy `import docker` picks this up instead of needing the real
    package/daemon. Returns the fake client for assertions."""
    fake_module = types.ModuleType("docker")
    holder = {}

    def from_env():
        c = _FakeDockerClient()
        holder["client"] = c
        return c

    def DockerClient(base_url=None):
        c = _FakeDockerClient()
        holder["client"] = c
        holder["base_url"] = base_url
        return c

    fake_module.from_env = from_env
    fake_module.DockerClient = DockerClient
    sys.modules["docker"] = fake_module
    return holder


def _uninstall_fake_docker():
    sys.modules.pop("docker", None)


# ---------------------------------------------------------------------- #
# fake `e2b` SDK - just enough of Sandbox.create()/.commands.run()/
# .files.write()/.get_host()/.is_running()/.kill()

class _FakeE2bResult:
    exit_code = 0
    stdout = "hello from e2b\n"
    stderr = ""


class _FakeE2bCommands:
    def __init__(self):
        self.run_calls = []

    def run(self, command, cwd=None, envs=None, timeout=None, background=False):
        self.run_calls.append({"command": command, "cwd": cwd, "envs": envs,
                                "timeout": timeout, "background": background})
        return _FakeE2bResult()


class _FakeE2bFiles:
    def __init__(self):
        self.written = {}

    def write(self, path, data):
        self.written[path] = data


class _FakeSandbox:
    instances: list[_FakeSandbox] = []  # noqa: RUF012

    def __init__(self, template=None, api_key=None, timeout=None):
        self.template = template
        self.api_key = api_key
        self.timeout = timeout
        self.commands = _FakeE2bCommands()
        self.files = _FakeE2bFiles()
        self.sandbox_id = "fakesandbox0123"
        self.killed = False
        _FakeSandbox.instances.append(self)

    @classmethod
    def create(cls, template=None, api_key=None, timeout=None, **_opts):
        return cls(template=template, api_key=api_key, timeout=timeout)

    def kill(self):
        self.killed = True

    def get_host(self, port):
        return f"{port}-{self.sandbox_id}.e2b.dev"

    def is_running(self):
        return not self.killed


def _install_fake_e2b():
    fake_module = types.ModuleType("e2b")
    fake_module.Sandbox = _FakeSandbox
    sys.modules["e2b"] = fake_module
    _FakeSandbox.instances = []
    return _FakeSandbox


def _uninstall_fake_e2b():
    sys.modules.pop("e2b", None)


# ---------------------------------------------------------------------- #
# (b) regression: local mode unchanged from before RUN_MODE existed

def test_local_regression():
    print("== local mode: unchanged from before RUN_MODE (regression) ==")
    tmp = tempfile.mkdtemp(prefix="ci-runmode-local-")
    try:
        with mock.patch.object(executor, "SETTINGS", Settings(run_mode="local")):
            out = executor.run_command(tmp, "echo hello-local", timeout=10)
            check("local (default run_mode): success", out["success"] is True)
            check("local: exit_code 0", out["final"]["exit_code"] == 0)
            check("local: stdout captured", "hello-local" in out["final"]["stdout"])
            check("local: result shape unchanged (no new top-level keys)",
                  set(out.keys()) == {"success", "command", "attempts_used",
                                       "final", "flaky"})
            check("local: final shape unchanged",
                  set(out["final"].keys()) == {"attempt", "exit_code",
                                               "duration_seconds", "stdout",
                                               "stderr", "timed_out"})

            out_explicit = executor.run_command(tmp, "echo hello-local",
                                                timeout=10, run_mode="local")
            check("local: explicit run_mode='local' matches default omission",
                  out_explicit["success"] == out["success"]
                  and out_explicit["final"]["stdout"] == out["final"]["stdout"])

            fail = executor.run_command(tmp, "exit 7", timeout=10, run_mode="local")
            check("local: failing command still carries rework_hint",
                  "apply_files" in fail.get("rework_hint", ""))
            check("local: exit_code preserved on failure",
                  fail["final"]["exit_code"] == 7)

            flaky = executor.run_command(
                tmp,
                "test -f flag && echo RECOVERED || (touch flag; echo transient; exit 1)",
                timeout=10, attempts=3, run_mode="local")
            check("local: attempts/flaky mechanics unchanged",
                  flaky["success"] and flaky["attempts_used"] == 2
                  and flaky["flaky"] is True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------------- #
# (c) start_process refuses run_mode="local"

def test_start_process_local_rejected():
    print("== start_process: run_mode='local' is rejected ==")
    tmp = tempfile.mkdtemp(prefix="ci-runmode-local-proc-")
    try:
        with mock.patch.object(executor, "SETTINGS", Settings(run_mode="local")):
            try:
                executor.start_process(tmp, "npm run dev", run_mode="local")
                check("start_process(run_mode='local') raises WorkspaceError", False)
            except WorkspaceError:
                check("start_process(run_mode='local') raises WorkspaceError", True)

            try:
                executor.start_process(tmp, "npm run dev")  # default -> local
                check("start_process(default run_mode) raises WorkspaceError", False)
            except WorkspaceError:
                check("start_process(default run_mode) raises WorkspaceError", True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------------- #
# (a) docker: command/config construction (mocked docker-py)

def test_docker_config_building():
    print("== docker mode: command/config construction (mocked docker-py) ==")
    holder = _install_fake_docker()
    tmp = tempfile.mkdtemp(prefix="ci-runmode-docker-")
    try:
        settings = Settings(run_mode="docker", docker_image="node:22-bookworm")
        with mock.patch.object(executor, "SETTINGS", settings):
            out = executor.run_command(
                tmp, "npm test", timeout=15, run_mode="docker",
                image="python:3.12-slim",
                env_overrides={"DATABASE_URL": "postgres://x"})

        client = holder["client"]
        check("docker: exactly one container run for one attempt",
              len(client.containers.run_calls) == 1)
        call = client.containers.run_calls[0]
        check("docker: per-call image overrides SETTINGS.docker_image",
              call["image"] == "python:3.12-slim")
        check("docker: command wrapped as bash -lc <command>",
              call["command"] == ["bash", "-lc", "npm test"])
        check("docker: working_dir is /workspace", call["working_dir"] == "/workspace")
        vol = call["volumes"][os.path.abspath(tmp)]
        check("docker: workspace bind-mounted rw at /workspace",
              vol["bind"] == "/workspace" and vol["mode"] == "rw")
        check("docker: network disabled", call.get("network_mode") == "none")
        check("docker: memory capped at 1g", call.get("mem_limit") == "1g")
        check("docker: cpu capped to 1 vCPU (nano_cpus)",
              call.get("nano_cpus") == 1_000_000_000)
        check("docker: pids capped at 256", call.get("pids_limit") == 256)
        check("docker: runs detached (so wait()/logs() can be used)",
              call.get("detach") is True)
        check("docker: env_overrides merged over the scrubbed allowlist",
              call["environment"].get("DATABASE_URL") == "postgres://x")
        check("docker: scrubbed allowlist still present alongside overrides",
              "PATH" in call["environment"] or "PYTHONDONTWRITEBYTECODE" in call["environment"])
        check("docker: runs as this service's own non-root uid by default (auto)",
              call.get("user") == _default_docker_user()
              and not str(call.get("user")).startswith("0:"))
        check("docker: read-only root filesystem", call.get("read_only") is True)
        check("docker: /tmp is a size-capped nosuid/nodev tmpfs",
              call.get("tmpfs") == {
                  TMP: "rw,exec,nosuid,nodev,size=512m,mode=1777"})
        check("docker: all capabilities dropped", call.get("cap_drop") == ["ALL"])
        check("docker: no-new-privileges set",
              call.get("security_opt") == ["no-new-privileges:true"])
        check("docker: HOME points at the writable tmpfs",
              call["environment"].get("HOME") == TMP)
        # EXACT kwargs: any new/removed key (e.g. privileged=True, a second
        # volume, a network) must be a deliberate, test-visible change.
        expected_env = dict(call["environment"])
        check("docker: exact containers.run kwargs (secure defaults)",
              call == {
                  "image": "python:3.12-slim",
                  "command": ["bash", "-lc", "npm test"],
                  "working_dir": "/workspace",
                  "volumes": {os.path.abspath(tmp): {"bind": "/workspace",
                                                     "mode": "rw"}},
                  "environment": expected_env,
                  "network_mode": "none",
                  "mem_limit": "1g",
                  "nano_cpus": 1_000_000_000,
                  "pids_limit": 256,
                  "read_only": True,
                  "detach": True,
                  "user": _default_docker_user(),
                  "tmpfs": {TMP: "rw,exec,nosuid,nodev,size=512m,mode=1777"},
                  "cap_drop": ["ALL"],
                  "security_opt": ["no-new-privileges:true"],
              }, str(sorted(call)))
        check("docker: run_command result reports success", out["success"] is True)
        check("docker: result shape matches local mode's shape",
              set(out.keys()) == {"success", "command", "attempts_used",
                                   "final", "flaky"})

        # falls back to SETTINGS.docker_image when no per-call override given.
        # NOTE: _get_docker_client() is called fresh per run_command call
        # (matches docker-py's own cheap from_env() usage pattern), so the
        # fake's from_env() hands back a NEW _FakeDockerClient here -
        # re-read holder["client"] rather than reusing the earlier `client`
        # local, which would still point at the first call's client.
        with mock.patch.object(executor, "SETTINGS", settings):
            executor.run_command(tmp, "true", timeout=10, run_mode="docker")
        check("docker: falls back to SETTINGS.docker_image without an override",
              holder["client"].containers.run_calls[-1]["image"] == "node:22-bookworm")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        _uninstall_fake_docker()


def test_docker_hardening_overrides():
    print("== docker mode: hardening knobs are configurable (mocked docker-py) ==")
    holder = _install_fake_docker()
    tmp = tempfile.mkdtemp(prefix="ci-runmode-docker-hard-")
    try:
        loose = Settings(run_mode="docker", docker_user="1000:1000",
                         docker_read_only=False, docker_tmpfs_size="",
                         docker_cap_drop="", docker_no_new_privileges=False,
                         docker_mem_limit="2g", docker_nano_cpus=2_000_000_000,
                         docker_pids_limit=512)
        with mock.patch.object(executor, "SETTINGS", loose):
            executor.run_command(tmp, "true", timeout=10, run_mode="docker")
        call = holder["client"].containers.run_calls[-1]
        check("override: explicit uid:gid passed through", call.get("user") == "1000:1000")
        check("override: read_only can be turned off", call.get("read_only") is False)
        check("override: empty tmpfs size -> no tmpfs", "tmpfs" not in call)
        check("override: empty cap_drop -> no cap_drop", "cap_drop" not in call)
        check("override: no-new-privileges can be turned off",
              "security_opt" not in call)
        check("override: caps come from settings",
              call.get("mem_limit") == "2g" and call.get("nano_cpus") == 2_000_000_000
              and call.get("pids_limit") == 512)
        check("override: HOME not rewritten when root fs is writable",
              call["environment"].get("HOME") == os.environ.get("HOME"))
        check("network stays disabled regardless of overrides",
              call.get("network_mode") == "none")

        with mock.patch.object(executor, "SETTINGS",
                               Settings(run_mode="docker", docker_tmpfs_exec=False,
                                        docker_tmpfs_size="64m")):
            executor.run_command(tmp, "true", timeout=10, run_mode="docker")
        call = holder["client"].containers.run_calls[-1]
        check("override: noexec tmpfs with custom size",
              call.get("tmpfs") == {TMP: "rw,noexec,nosuid,nodev,size=64m,mode=1777"})

        with mock.patch.object(executor, "SETTINGS",
                               Settings(run_mode="docker", docker_user="")):
            executor.run_command(tmp, "true", timeout=10, run_mode="docker")
        check("override: empty docker_user -> no user kwarg (explicit downgrade)",
              "user" not in holder["client"].containers.run_calls[-1])

        with mock.patch.object(executor, "SETTINGS",
                               Settings(run_mode="docker", docker_user="auto")):
            with mock.patch.object(executor.os, "getuid", create=True, return_value=1234), \
                 mock.patch.object(executor.os, "getgid", create=True, return_value=4321):
                check("auto: resolves to this process's uid:gid",
                      sandbox_sdk.docker_user(executor.SETTINGS) == "1234:4321")
            with mock.patch.object(executor.os, "getuid", create=True, return_value=0), \
                 mock.patch.object(executor.os, "getgid", create=True, return_value=0):
                check("auto: never root - falls back to 65534:65534",
                      sandbox_sdk.docker_user(executor.SETTINGS) == "65534:65534")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        _uninstall_fake_docker()


def _default_docker_user() -> str:
    """What the default CODE_INTEL_DOCKER_USER=auto resolves to on THIS host:
    the test process's own uid:gid, or nobody when root / no getuid()."""
    getuid = getattr(os, "getuid", None)
    getgid = getattr(os, "getgid", None)
    if getuid is None or getgid is None or getuid() == 0:
        return "65534:65534"
    return f"{getuid()}:{getgid()}"


def test_docker_process_lifecycle():
    print("== docker mode: start/stop/status process lifecycle (mocked) ==")
    holder = _install_fake_docker()
    tmp = tempfile.mkdtemp(prefix="ci-runmode-docker-proc-")
    try:
        settings = Settings(run_mode="docker", docker_image="node:22-bookworm")
        with mock.patch.object(executor, "SETTINGS", settings):
            started = executor.start_process(
                tmp, "npm run dev", run_mode="docker", port=3000, ws_id="ws-abc")
            check("start_process(docker): process_id is proc-prefixed",
                  started["process_id"].startswith("proc-"))
            check("start_process(docker): reports run_mode",
                  started["run_mode"] == "docker")
            check("start_process(docker): reads back the docker-assigned host port",
                  started["port"] == 54321)
            check("start_process(docker): reports a container_id",
                  bool(started["container_id"]))

            call = holder["client"].containers.run_calls[-1]
            check("start_process(docker): publishes the container port",
                  call.get("ports") == {"3000/tcp": None})
            check("start_process(docker): labels the container with ws_id",
                  call.get("labels", {}).get("codeintel.ws_id") == "ws-abc")
            check("start_process(docker): same hardening as run_command",
                  call.get("user") == _default_docker_user()
                  and call.get("read_only") is True
                  and call.get("cap_drop") == ["ALL"]
                  and call.get("security_opt") == ["no-new-privileges:true"]
                  and call.get("network_mode") == "none"
                  and TMP in call.get("tmpfs", {}))

            status = executor.process_status(started["process_id"])
            check("process_status(docker): running is True", status["running"] is True)

            executor.stop_process(started["process_id"])
            try:
                executor.process_status(started["process_id"])
                check("process_status after stop_process raises WorkspaceError", False)
            except WorkspaceError:
                check("process_status after stop_process raises WorkspaceError", True)

            try:
                executor.stop_process("proc-does-not-exist")
                check("stop_process on unknown id raises WorkspaceError", False)
            except WorkspaceError:
                check("stop_process on unknown id raises WorkspaceError", True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        _uninstall_fake_docker()


# ---------------------------------------------------------------------- #
# (d) e2b: missing E2B_API_KEY fails closed before any SDK/network call

def test_e2b_missing_key_fails_closed():
    print("== e2b mode: missing E2B_API_KEY fails closed before any SDK call ==")
    FakeSandbox = _install_fake_e2b()
    tmp = tempfile.mkdtemp(prefix="ci-runmode-e2b-nokey-")
    try:
        settings = Settings(run_mode="e2b", e2b_api_key=None)
        with mock.patch.object(executor, "SETTINGS", settings):
            try:
                executor.run_command(tmp, "pytest -q", timeout=10, run_mode="e2b")
                check("run_command(e2b) without key raises WorkspaceError", False)
            except WorkspaceError as e:
                check("run_command(e2b) without key raises WorkspaceError", True)
                check("error names E2B_API_KEY", "E2B_API_KEY" in str(e))
            check("no Sandbox ever constructed (no network attempt)",
                  len(FakeSandbox.instances) == 0)

            try:
                executor.start_process(tmp, "npm run dev", run_mode="e2b", port=3000)
                check("start_process(e2b) without key raises WorkspaceError", False)
            except WorkspaceError:
                check("start_process(e2b) without key raises WorkspaceError", True)
            check("still no Sandbox constructed after start_process attempt",
                  len(FakeSandbox.instances) == 0)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        _uninstall_fake_e2b()


def test_e2b_mocked_run_and_process():
    print("== e2b mode: run_command + start/stop/status (mocked SDK) ==")
    FakeSandbox = _install_fake_e2b()
    tmp = tempfile.mkdtemp(prefix="ci-runmode-e2b-ok-")
    try:
        os.makedirs(os.path.join(tmp, "src"))
        with open(os.path.join(tmp, "src", "a.py"), "w") as f:
            f.write("print('hi')\n")

        settings = Settings(run_mode="e2b", e2b_api_key="fake-key",
                            e2b_template="base")
        with mock.patch.object(executor, "SETTINGS", settings):
            out = executor.run_command(tmp, "pytest -q", timeout=10, run_mode="e2b")
            check("run_command(e2b): succeeds against the mocked SDK",
                  out["success"] is True)
            check("run_command(e2b): result shape matches local/docker",
                  set(out.keys()) == {"success", "command", "attempts_used",
                                       "final", "flaky"})
            check("run_command(e2b): workspace files uploaded before running",
                  any(p.endswith("src/a.py")
                      for p in FakeSandbox.instances[-1].files.written))
            check("run_command(e2b): sandbox killed in finally",
                  FakeSandbox.instances[-1].killed is True)

            started = executor.start_process(tmp, "npm run dev", run_mode="e2b",
                                             port=3000)
            check("start_process(e2b): process_id is proc-prefixed",
                  started["process_id"].startswith("proc-"))
            check("start_process(e2b): returns an https preview_url",
                  (started["preview_url"] or "").startswith("https://"))
            check("start_process(e2b): reports a sandbox_id",
                  bool(started["sandbox_id"]))
            check("start_process(e2b): command started in background",
                  FakeSandbox.instances[-1].commands.run_calls[-1]["background"] is True)

            status = executor.process_status(started["process_id"])
            check("process_status(e2b): running is True", status["running"] is True)

            executor.stop_process(started["process_id"])
            check("stop_process(e2b): sandbox killed",
                  FakeSandbox.instances[-1].killed is True)
            try:
                executor.process_status(started["process_id"])
                check("process_status after stop_process raises WorkspaceError", False)
            except WorkspaceError:
                check("process_status after stop_process raises WorkspaceError", True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        _uninstall_fake_e2b()


def main():
    test_local_regression()
    test_start_process_local_rejected()
    test_docker_config_building()
    test_docker_hardening_overrides()
    test_docker_process_lifecycle()
    test_e2b_missing_key_fails_closed()
    test_e2b_mocked_run_and_process()
    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
