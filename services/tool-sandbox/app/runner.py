"""Compile, verify and execute a tenant-authored tool.

Ordering matters and is deliberate for Java: **compile first, then verify the
compiled bytecode, then run.** Verifying source text instead would be guessing -
comments, string literals, unicode escapes and generics all make a source grep
unreliable - whereas after ``javac`` has run, the constant pool is an exact,
complete statement of what the code can reach. See ``classfile.py``.

Compiled Java artifacts are cached per tenant (see ``javacache.py``); each
execution's uid and directory come from ``isolation.py``; the result file is
read back and classified in ``outcome.py``.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from . import classfile, policy, proc, pyvalidate
from .isolation import RunIdentity, RunIsolation, Workdir
from .javacache import JavaArtifactCache
from .outcome import ExecutionOutcome, ValidationOutcome, interpret
from .outcome import elapsed_ms as _elapsed_ms
from .secretenv import secret_env
from .settings import Settings

log = logging.getLogger(__name__)

JAVA = "java"
PYTHON = "python"
LANGUAGES = (JAVA, PYTHON)


class Runner:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        settings.work_root.mkdir(parents=True, exist_ok=True)
        settings.cache_root.mkdir(parents=True, exist_ok=True)
        self._isolation = RunIsolation(settings)
        self._java_cache = JavaArtifactCache(settings)

    # ------------------------------------------------------------------ API

    def validate(self, language: str, tenant: str, source: str) -> ValidationOutcome:
        digest = sha256(source)
        problem = self._check_source_envelope(language, source)
        if problem:
            return ValidationOutcome(False, digest, errors=[{"line": None, "message": problem}])

        if language == PYTHON:
            violations = pyvalidate.validate(source)
            return ValidationOutcome(
                valid=not violations,
                source_sha256=digest,
                errors=[{"line": v.line, "message": v.message} for v in violations],
            )

        with self._workdir(tenant) as (work, ident):
            compiled = self._compile_java(source, work, ident)
            if compiled.errors:
                return ValidationOutcome(False, digest, errors=compiled.errors)
            self._java_cache.store(tenant, digest, compiled.classes_dir)
            return ValidationOutcome(True, digest, warnings=compiled.warnings)

    def execute(
        self,
        language: str,
        tenant: str,
        source: str,
        tool_input: Any,
        timeout_ms: int,
        memory_mb: int,
        secrets: dict[str, str] | None = None,
    ) -> ExecutionOutcome:
        started = time.monotonic()
        problem = self._check_source_envelope(language, source)
        if problem:
            return ExecutionOutcome(False, error=problem, error_type="VALIDATION")

        input_text = json.dumps(tool_input if tool_input is not None else {})
        if len(input_text.encode("utf-8")) > self.settings.max_input_bytes:
            return ExecutionOutcome(
                False,
                error=f"Tool input exceeds {self.settings.max_input_bytes} bytes.",
                error_type="VALIDATION",
            )

        try:
            with self._workdir(tenant) as (work, ident):
                if language == JAVA:
                    return self._execute_java(
                        tenant, source, input_text, work, timeout_ms, memory_mb, started,
                        secrets, ident=ident)
                return self._execute_python(
                    source, input_text, work, timeout_ms, memory_mb, started, secrets,
                    ident=ident)
        except proc.UidPoolExhausted as exc:
            log.error("refusing to execute tenant code: %s", exc)
            return ExecutionOutcome(
                False,
                error="The sandbox is at capacity. Try again shortly.",
                error_type="INTERNAL",
                duration_ms=_elapsed_ms(started),
            )
        except proc.PrivilegeDropUnavailable as exc:
            # Fail closed, loudly. This is a deployment fault, not a tenant one.
            log.error("refusing to execute tenant code: %s", exc)
            return ExecutionOutcome(
                False,
                error="The sandbox is misconfigured and refused to run this tool. "
                      "An administrator needs to check the tool-sandbox service logs.",
                error_type="INTERNAL",
                duration_ms=_elapsed_ms(started),
            )

    # ------------------------------------------------------------- Java path

    @dataclass
    class _CompileResult:
        classes_dir: Path | None = None
        errors: list[dict] = field(default_factory=list)
        warnings: list[str] = field(default_factory=list)

    def _compile_java(
        self, source: str, work: Path, ident: RunIdentity
    ) -> Runner._CompileResult:
        src_dir = work / "src"
        classes_dir = work / "classes"
        src_dir.mkdir(parents=True, exist_ok=True)
        classes_dir.mkdir(parents=True, exist_ok=True)
        source_file = src_dir / "Tool.java"
        source_file.write_text(source, encoding="utf-8")
        # src/ stays root-owned and read-only to the run; javac writes classes/.
        self._make_writable(classes_dir, ident)

        result = proc.run(
            [
                self.settings.javac,
                # no annotation processors - they are arbitrary code at compile time
                "-proc:none",
                "-nowarn",
                "-implicit:none",
                "-encoding", "UTF-8",
                # keeps line numbers, so a thrown exception can point at the tool
                "-g:lines,source",
                "-d", str(classes_dir),
                "-cp", str(self.settings.vega_api_jar),
                str(source_file),
            ],
            cwd=work,
            limits=proc.Limits(
                wall_ms=self.settings.compile_timeout_ms,
                cpu_seconds=max(2, self.settings.compile_timeout_ms // 1000),
                max_output_bytes=self.settings.max_output_bytes,
                address_space_mb=None,  # javac is a JVM - see Limits.address_space_mb
            ),
            **self._isolation_kwargs(ident),
        )

        if result.timed_out:
            return Runner._CompileResult(errors=[{
                "line": None,
                "message": "Compilation timed out. Simplify the program and try again.",
            }])
        if result.exit_code != 0:
            return Runner._CompileResult(errors=_parse_javac_errors(result.stderr or result.stdout))

        class_files = {p.name: p.read_bytes() for p in sorted(classes_dir.rglob("*.class"))}
        if not class_files:
            return Runner._CompileResult(errors=[{
                "line": None,
                "message": "Compilation produced no classes. " + policy.JAVA_ENTRYPOINT_HINT,
            }])
        if "Tool.class" not in class_files:
            return Runner._CompileResult(errors=[{
                "line": None,
                "message": "No `Tool` class was produced. " + policy.JAVA_ENTRYPOINT_HINT,
            }])

        violations = classfile.verify(class_files)
        if violations:
            return Runner._CompileResult(
                errors=[{"line": None, "message": v} for v in violations])

        return Runner._CompileResult(classes_dir=classes_dir)

    def _execute_java(
        self,
        tenant: str,
        source: str,
        input_text: str,
        work: Path,
        timeout_ms: int,
        memory_mb: int,
        started: float,
        secrets: dict[str, str] | None = None,
        *,
        ident: RunIdentity,
    ) -> ExecutionOutcome:
        digest = sha256(source)
        classes_dir = self._java_cache.cached(tenant, digest)
        if classes_dir is None:
            compiled = self._compile_java(source, work, ident)
            if compiled.errors:
                return ExecutionOutcome(
                    False,
                    error="; ".join(e["message"] for e in compiled.errors),
                    error_type="COMPILE",
                    duration_ms=_elapsed_ms(started),
                )
            classes_dir = self._java_cache.store(tenant, digest, compiled.classes_dir)

        input_file = work / "input.json"
        result_file = work / "result.json"
        input_file.write_text(input_text, encoding="utf-8")
        result_file.write_text("", encoding="utf-8")
        self._make_writable(result_file, ident)

        classpath = os.pathsep.join([
            str(self.settings.harness_jar),
            str(self.settings.vega_api_jar),
            str(classes_dir),
        ])
        run = proc.run(
            [
                self.settings.java,
                # Only java.base is observable: no java.net.http, no java.sql,
                # no jdk.* internals, nothing the policy would have to deny
                # one class at a time.
                "--limit-modules", "java.base",
                "-XX:-UsePerfData",            # no shared-memory perf file on disk
                "-XX:+DisableAttachMechanism", # no external process can attach to this JVM
                "-XX:ActiveProcessorCount=1",  # keep GC/JIT thread counts inside RLIMIT_NPROC
                "-XX:TieredStopAtLevel=1",     # a tool runs for milliseconds; C2 never pays off
                f"-Xmx{memory_mb}m",
                "-XX:MaxMetaspaceSize=96m",
                "-Xss1m",
                "-Dfile.encoding=UTF-8",
                # Keep any JVM-internal temp file inside the per-run directory
                # that is destroyed afterwards, rather than in a shared /tmp.
                f"-Djava.io.tmpdir={work}",
                "-cp", classpath,
                "vega.internal.Harness",
                str(input_file),
                str(result_file),
            ],
            cwd=work,
            limits=self._run_limits(timeout_ms, address_space_mb=None),
            **self._isolation_kwargs(ident, secrets),
        )
        return self._interpret(run, result_file, started)

    # ----------------------------------------------------------- Python path

    def _execute_python(
        self,
        source: str,
        input_text: str,
        work: Path,
        timeout_ms: int,
        memory_mb: int,
        started: float,
        secrets: dict[str, str] | None = None,
        *,
        ident: RunIdentity,
    ) -> ExecutionOutcome:
        violations = pyvalidate.validate(source)
        if violations:
            return ExecutionOutcome(
                False,
                error="; ".join(v.message for v in violations),
                error_type="VALIDATION",
                duration_ms=_elapsed_ms(started),
            )

        tool_file = work / "tool.py"
        input_file = work / "input.json"
        result_file = work / "result.json"
        tool_file.write_text(source, encoding="utf-8")
        input_file.write_text(input_text, encoding="utf-8")
        result_file.write_text("", encoding="utf-8")
        self._make_writable(result_file, ident)

        run = proc.run(
            [
                self.settings.python,
                "-I",  # isolated: ignores PYTHON* env vars and the user site dir
                "-S",  # no site module
                "-B",  # no .pyc left behind
                str(self.settings.py_harness),
                str(tool_file),
                str(input_file),
                str(result_file),
            ],
            cwd=work,
            limits=self._run_limits(timeout_ms, address_space_mb=memory_mb),
            **self._isolation_kwargs(ident, secrets),
        )
        return self._interpret(run, result_file, started)

    # -------------------------------------------------------------- shared

    def _run_limits(self, timeout_ms: int, address_space_mb: int | None) -> proc.Limits:
        return proc.Limits(
            wall_ms=timeout_ms,
            # One second of headroom so the wall-clock kill is normally what
            # fires; RLIMIT_CPU is the backstop for a process that somehow
            # escapes it.
            cpu_seconds=max(1, timeout_ms // 1000 + 1),
            max_output_bytes=self.settings.max_output_bytes,
            address_space_mb=address_space_mb,
        )

    def _isolation_kwargs(
        self, ident: RunIdentity, secrets: dict[str, str] | None = None
    ) -> dict[str, Any]:
        # Only this run's own secrets ever reach its environment (secret_env),
        # and with per-run uids no other run can read /proc/<pid>/environ.
        kwargs: dict[str, Any] = {
            "run_uid": ident.uid,
            "run_gid": ident.gid,
            "require_privilege_drop": self.settings.require_privilege_drop,
            "enable_seccomp": self.settings.enable_seccomp,
        }
        extra_env = secret_env(secrets, self.settings)
        if extra_env:
            kwargs["extra_env"] = extra_env
        return kwargs

    def _interpret(
        self, run: proc.RunResult, result_file: Path, started: float
    ) -> ExecutionOutcome:
        return interpret(run, result_file, started, self.settings.max_result_bytes)

    def _check_source_envelope(self, language: str, source: str) -> str | None:
        if language not in LANGUAGES:
            return f"Unsupported language '{language}'. Choose java or python."
        if not source or not source.strip():
            return "The program is empty."
        if len(source.encode("utf-8")) > self.settings.max_source_bytes:
            return f"The program exceeds the {self.settings.max_source_bytes} byte limit."
        return None

    # --------------------------------------------------------- workdir/cache

    def _workdir(self, tenant: str) -> Workdir:
        return self._isolation.workdir(tenant)

    def _make_writable(self, path: Path, ident: RunIdentity) -> None:
        self._isolation.make_writable(path, ident)


# ---------------------------------------------------------------- helpers


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _parse_javac_errors(text: str) -> list[dict]:
    """Turn javac's output into per-line messages, dropping the noise.

    javac prints ``<path>:<line>: error: <message>`` plus the offending source
    line and a caret. The path is a temp directory that means nothing to the
    author, so it is stripped and only the line number kept.
    """
    errors: list[dict] = []
    for raw in (text or "").splitlines():
        line = raw.strip()
        if not line or line.startswith("^") or line.endswith("errors") or line.endswith("error"):
            continue
        marker = ": error: "
        if marker in line:
            head, _, message = line.partition(marker)
            number = None
            parts = head.rsplit(":", 1)
            if len(parts) == 2 and parts[1].strip().isdigit():
                number = int(parts[1].strip())
            errors.append({"line": number, "message": message.strip()})
    if not errors:
        cleaned = (text or "").strip()
        errors.append({"line": None, "message": cleaned[:2000] or "Compilation failed."})
    return errors

