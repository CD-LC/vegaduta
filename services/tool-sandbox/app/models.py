"""Request/response models - the wire contract with core.

Kept deliberately small and stable: core's ``ToolSandboxClient`` is the only
caller, and the contract is versioned in the path (``/v1/...``) so it can change
without a flag day.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

Language = Literal["java", "python"]

#: Every way an execution can fail, as a closed set the caller can branch on.
ErrorType = Literal[
    "VALIDATION",        # the program was rejected before it ran
    "COMPILE",           # javac refused it
    "RUNTIME",           # the program itself threw
    "TIMEOUT",           # exceeded its wall-clock budget
    "MEMORY",            # exceeded its memory budget
    "OUTPUT_TOO_LARGE",  # returned more than the result cap
    "INTERNAL",          # the sandbox itself failed
]


class ValidateRequest(BaseModel):
    language: Language
    toolName: str = Field(default="", max_length=64)
    source: str


class ValidationError(BaseModel):
    line: int | None = None
    message: str


class ValidateResponse(BaseModel):
    valid: bool
    sourceSha256: str
    errors: list[ValidationError] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class ExecuteRequest(BaseModel):
    language: Language
    toolName: str = Field(default="", max_length=64)
    source: str
    input: Any = None
    timeoutMs: int | None = None
    memoryMb: int | None = None
    # Vault secrets resolved by core for this one execution, keyed by the name
    # the program refers to them by.
    #
    # These reach the TRUSTED HARNESS as environment variables, never the
    # tenant program directly: tenant code cannot read the environment (the
    # Python builtin allow-list has no `open` and `os` is not importable; the
    # Java verifier denies java.lang.System, "the door to getenv"), so the
    # harness is the only thing in the process that can see them and it hands
    # them over through a narrow API.
    #
    # Never logged. main.py's failure log already omits source and input on the
    # grounds that they are tenant data; this is strictly more sensitive.
    secrets: dict[str, str] = Field(default_factory=dict)


class ExecuteResponse(BaseModel):
    ok: bool
    result: Any = None
    error: str | None = None
    errorType: ErrorType | None = None
    stdout: str = ""
    stderr: str = ""
    durationMs: int = 0
    truncated: bool = False


class HealthResponse(BaseModel):
    status: str
    version: str
    languages: list[str]
    seccomp: str
    privilegeDrop: str
