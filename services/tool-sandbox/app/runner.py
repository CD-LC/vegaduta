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
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from . import classfile, jsvalidate, policy, proc, pyvalidate
from .isolation import RunIdentity, RunIsolation, Workdir
from .javacache import JavaArtifactCache
from .outcome import ExecutionOutcome, ValidationOutcome, interpret
from .outcome import elapsed_ms as _elapsed_ms
from .secretenv import secret_env
from .settings import Settings

log = logging.getLogger(__name__)

JAVA = "java"
PYTHON = "python"
JAVASCRIPT = "javascript"
TYPESCRIPT = "typescript"
LANGUAGES = (JAVA, PYTHON, JAVASCRIPT, TYPESCRIPT)

#: Languages whose source runs under the Node runtime (`.mjs` / `.mts`).
JS_LANGS = (JAVASCRIPT, TYPESCRIPT)


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

        if language in JS_LANGS:
            violations = jsvalidate.validate(source)
            with self._workdir(tenant) as (work, ident):
                violations.extend(self._check_js_syntax(language, source, work, ident))
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
                        tenant,
                        source,
                        input_text,
                        work,
                        timeout_ms,
                        memory_mb,
                        started,
                        secrets,
                        ident=ident,
                    )
                if language in JS_LANGS:
                    return self._execute_js(
                        language,
                        source,
                        input_text,
                        work,
                        timeout_ms,
                        memory_mb,
                        started,
                        ident=ident,
                    )
                return self._execute_python(
                    source, input_text, work, timeout_ms, memory_mb, started, secrets, ident=ident
                )
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

    def _compile_java(self, source: str, work: Path, ident: RunIdentity) -> Runner._CompileResult:
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
                "-encoding",
                "UTF-8",
                # keeps line numbers, so a thrown exception can point at the tool
                "-g:lines,source",
                "-d",
                str(classes_dir),
                "-cp",
                str(self.settings.vega_api_jar),
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
            return Runner._CompileResult(
                errors=[
                    {
                        "line": None,
                        "message": "Compilation timed out. Simplify the program and try again.",
                    }
                ]
            )
        if result.exit_code != 0:
            return Runner._CompileResult(errors=_parse_javac_errors(result.stderr or result.stdout))

        class_files = {p.name: p.read_bytes() for p in sorted(classes_dir.rglob("*.class"))}
        if not class_files:
            return Runner._CompileResult(
                errors=[
                    {
                        "line": None,
                        "message": "Compilation produced no classes. "
                        + policy.JAVA_ENTRYPOINT_HINT,
                    }
                ]
            )
        if "Tool.class" not in class_files:
            return Runner._CompileResult(
                errors=[
                    {
                        "line": None,
                        "message": "No `Tool` class was produced. " + policy.JAVA_ENTRYPOINT_HINT,
                    }
                ]
            )

        violations = classfile.verify(class_files)
        if violations:
            return Runner._CompileResult(errors=[{"line": None, "message": v} for v in violations])

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

        classpath = os.pathsep.join(
            [
                str(self.settings.harness_jar),
                str(self.settings.vega_api_jar),
                str(classes_dir),
            ]
        )
        run = proc.run(
            [
                self.settings.java,
                # Only java.base is observable: no java.net.http, no java.sql,
                # no jdk.* internals, nothing the policy would have to deny
                # one class at a time.
                "--limit-modules",
                "java.base",
                "-XX:-UsePerfData",  # no shared-memory perf file on disk
                "-XX:+DisableAttachMechanism",  # no external process can attach to this JVM
                "-XX:ActiveProcessorCount=1",  # keep GC/JIT thread counts inside RLIMIT_NPROC
                "-XX:TieredStopAtLevel=1",  # a tool runs for milliseconds; C2 never pays off
                f"-Xmx{memory_mb}m",
                "-XX:MaxMetaspaceSize=96m",
                "-Xss1m",
                "-Dfile.encoding=UTF-8",
                # Keep any JVM-internal temp file inside the per-run directory
                # that is destroyed afterwards, rather than in a shared /tmp.
                f"-Djava.io.tmpdir={work}",
                "-cp",
                classpath,
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

    # ------------------------------------------------------- JavaScript path

    #: Trusted footer appended to tenant source AFTER jsvalidate has seen it.
    #: Reads the tool input on stdin, calls `run`, and writes the outcome
    #: envelope to result.json - the same contract py_harness.py fulfils.
    #: `process`/`import` appear here and only here; the identifier policy
    #: never sees this text.
    _JS_FOOTER = (
        "\n"
        'import { writeFileSync as __sandboxWrite } from "node:fs";\n'
        "const __sandboxReport = (envelope) => __sandboxWrite(\n"
        '    new URL("./result.json", import.meta.url), JSON.stringify(envelope));\n'
        "(async () => {\n"
        '    let __data = "";\n'
        "    for await (const __chunk of process.stdin) __data += __chunk;\n"
        '    const __input = JSON.parse(__data || "null");\n'
        "    const __result = await Promise.resolve(run(__input));\n"
        "    __sandboxReport({ ok: true, result: __result === undefined ? null : __result });\n"
        "})().catch((err) => {\n"
        "    __sandboxReport({ ok: false, error: String((err && err.stack) || err),\n"
        '        errorType: "RUNTIME" });\n'
        "    process.exit(2);\n"
        "});\n"
    )

    def _js_suffix(self, language: str) -> str:
        return ".mts" if language == TYPESCRIPT else ".mjs"

    def _js_argv(self, tool_file: Path, work: Path) -> list[str]:
        # Node's permission model: every fs write anywhere except the result
        # file, every fs read outside the run's own directory, and all
        # child_process/worker/addons/wasi use throws ERR_ACCESS_DENIED. The
        # entry file itself is loaded by Node's own bootstrap, so it needs no
        # grant; the read grant exists because `.mts` type-stripping resolves
        # the file a second time.
        return [
            self.settings.node,
            self._node_permission_flag(),
            "--experimental-strip-types",
            "--allow-fs-read=" + str(work),
            "--allow-fs-write=" + str(work / "result.json"),
            "--no-warnings",
            str(tool_file),
        ]

    # Node renamed the flag: --experimental-permission through 22.x (the pinned
    # runtime), --permission since 23.5. Probe once so the same code runs on
    # either - the sandbox image pins 22.x but tests and dev machines do not.
    _node_perm_flag_cache: str | None = None

    def _node_permission_flag(self) -> str:
        if self._node_perm_flag_cache is None:
            probe = proc.run(
                [self.settings.node, "--experimental-permission", "-e", "1"],
                cwd=self.settings.work_root,
                limits=proc.Limits(wall_ms=10_000, cpu_seconds=5, max_output_bytes=4096),
                require_privilege_drop=False,
            )
            self._node_perm_flag_cache = (
                "--experimental-permission"
                if probe.exit_code == 0 and "bad option" not in (probe.stderr or "")
                else "--permission"
            )
        return self._node_perm_flag_cache

    def _check_js_syntax(
        self, language: str, source: str, work: Path, ident: RunIdentity
    ) -> list[jsvalidate.Violation]:
        """Parse the source without executing it - the syntax half of JS
        validation (the identifier policy half is jsvalidate.validate, run by
        the caller). Uses ``node:module.stripTypeScriptTypes`` because ``node
        --check`` cannot parse TypeScript annotations on this runtime, while
        the stripper parses (and thereby syntax-checks) both plain JS and TS.
        """
        tool_file = work / ("check" + self._js_suffix(language))
        tool_file.write_text(source, encoding="utf-8")
        result = proc.run(
            [
                self.settings.node,
                "--experimental-strip-types",
                "-e",
                "require('node:module').stripTypeScriptTypes("
                "require('node:fs').readFileSync(process.argv[1], 'utf8'),"
                " { mode: 'strip' });",
                str(tool_file),
            ],
            cwd=work,
            limits=proc.Limits(
                wall_ms=10_000,
                cpu_seconds=5,
                max_output_bytes=self.settings.max_output_bytes,
            ),
            stdin_data=b"",
            run_uid=ident.uid,
            run_gid=ident.gid,
            require_privilege_drop=self.settings.require_privilege_drop,
            enable_seccomp=self.settings.enable_seccomp,
        )
        if result.timed_out:
            return [jsvalidate.Violation(None, "The syntax check timed out.")]
        if result.exit_code == 0:
            return []
        # The useful line is the `*Error: <message>` one; the lines around it
        # are the source echo, a caret and stack frames.
        errors: list[jsvalidate.Violation] = []
        for line in (result.stderr or "").splitlines():
            m = re.search(r"(SyntaxError|TypeError|ReferenceError)\s*[^:]*:\s*(.+)", line)
            if m:
                errors.append(jsvalidate.Violation(None, f"Syntax error: {m.group(2).strip()}"))
                break
        if not errors:
            first = next(
                (
                    line.strip()
                    for line in (result.stderr or "").splitlines()
                    if line.strip() and "Warning" not in line
                ),
                "the program did not pass Node's syntax check",
            )
            errors.append(jsvalidate.Violation(None, f"Syntax error: {first}"))
        return errors

    def _execute_js(
        self,
        language: str,
        source: str,
        input_text: str,
        work: Path,
        timeout_ms: int,
        memory_mb: int,
        started: float,
        *,
        ident: RunIdentity,
    ) -> ExecutionOutcome:
        violations = jsvalidate.validate(source) + self._check_js_syntax(
            language, source, work, ident
        )
        if violations:
            return ExecutionOutcome(
                False,
                error="; ".join(v.message for v in violations),
                error_type="VALIDATION",
                duration_ms=_elapsed_ms(started),
            )

        tool_file = work / ("tool" + self._js_suffix(language))
        result_file = work / "result.json"
        # The footer is trusted code - appended here, AFTER validation, never
        # part of what jsvalidate scanned. Input travels on stdin (the run has
        # no fs grant it could read an input file with).
        tool_file.write_text(source + self._JS_FOOTER, encoding="utf-8")
        result_file.write_text("", encoding="utf-8")
        self._make_writable(result_file, ident)

        run = proc.run(
            self._js_argv(tool_file, work),
            cwd=work,
            limits=self._run_limits(timeout_ms, address_space_mb=memory_mb),
            stdin_data=input_text.encode("utf-8"),
            # No secrets: JS tools cannot declare vault secrets yet - the JS
            # harness has no narrow-API equivalent of vega.Secrets, and the
            # whole child environment would be reachable from process.env if
            # the identifier policy were ever outsmarted.
            **self._isolation_kwargs(ident, None),
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
            return f"Unsupported language '{language}'. Choose {' or '.join(LANGUAGES)}."
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
