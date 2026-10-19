# tool-sandbox

Compiles, statically verifies and executes **tenant-authored Java and Python
programs** that become agent tools ("custom code tools").

A tenant-admin writes a program in the console describing what the tool is for,
what it does and what it returns. `core` stores it, offers it to that tenant's
agents by name, and calls this service to run it when a model invokes it.

---

## The contract a tool implements

**Java** — a class named `Tool` in the default package:

```java
import java.util.Map;

public class Tool {
    public static Object run(Map<String, Object> input) {
        String name = String.valueOf(input.getOrDefault("name", "world"));
        return Map.of("greeting", "Hello, " + name + "!");
    }
}
```

**Python** — a top-level `run` function:

```python
def run(input):
    return {"greeting": f"Hello, {input.get('name', 'world')}!"}
```

The return value is serialized to JSON and handed to the model. `vega.Log.info(...)`
(Java) and `print(...)` (Python) write to a stdout stream that is returned to the
tool's *author* in a test run and never shown to the model.

---

## Threat model

The question this service has to answer is: **a tenant is running code of their
choosing on our infrastructure — what stops it reading another tenant's data,
the platform's credentials, or the internet?**

The honest answer is that no single mechanism does, so there are several, and
they are ordered here from the ones that would still hold if everything above
them failed.

### Layer 0 — there is nothing here worth stealing

This container has **no platform credentials**: no datasource URL or password,
no `APP_ENCRYPTION_KEY`, no Keycloak client secret, no LLM provider key, no
tenant data at rest. Its entire environment is its own internal token and a
handful of numeric budgets. A total compromise of this container yields the
ability to run code in a container that can run code.

This is the most important property in the document, and it is the reason the
service exists separately at all. `core` cannot host this: `core` holds every
one of those secrets and bridges five Docker networks including the data tier.

### Layer 1 — no route to anything

`tool-sandbox` is attached to exactly one Docker network, `sandbox-net`,
declared `internal: true`. An internal network has no gateway, so there is no
route to the host or the internet, and the only other member is `core`.

There is no `ports:` mapping, so nothing outside the compose project can reach
it either.

**Residual reachable surface:** `core:8080`, which requires a Keycloak-issued
JWT that this container never sees, and the sandbox's own API, which requires
the internal token held only in the supervisor process's environment — an
environment the child never inherits (Layer 3). Closing even this would mean
moving `core`↔`sandbox` onto a Unix socket and running the container with
`network_mode: none`; that is the natural next hardening step and is not done
today.

### Layer 2 — the process cannot escalate

At the container level: every Linux capability is dropped except `SETUID`,
`SETGID` and `KILL`. `no-new-privileges:true` makes the uid drop irreversible,
so no setuid binary can win privileges back. `pids_limit` and `mem_limit` bound
the container as a whole. The working and cache directories are `tmpfs` mounted
`noexec,nosuid,nodev`, so nothing a tool writes survives the container or can be
executed as a program.

`KILL` is there for a reason worth stating, because it was found by running
this rather than reasoning about it: a root process in a container with all
capabilities dropped **cannot signal a process owned by a different uid** —
which is exactly the situation the uid drop creates. Without `CAP_KILL` the
wall-clock timeout failed with `EPERM`, the API returned a 500, and the runaway
program kept running until `RLIMIT_CPU` reaped it. Its scope is only this
container's own PID namespace, which contains nothing but sandbox processes.
`app/proc.py` now reports whether the kill landed and logs an error if it did
not, so the same regression can never be silent again.

At the process level, tenant code runs as **uid 5001 (`sbxrun`)**, which owns no
file in the image and no file this service writes. It cannot modify the harness
jars or the compiled-class cache — for its own tenant or anyone else's. The
supervisor refuses to run at all if it is root and cannot perform this drop
(`TOOL_SANDBOX_REQUIRE_PRIVILEGE_DROP`, default true).

The uid drop and the resource limits are applied by `setpriv` and `prlimit`,
not by CPython's `preexec_fn`, because `preexec_fn` runs arbitrary Python
between `fork()` and `exec()` and that is a documented deadlock hazard in a
threaded server.

### Layer 3 — the process is starved

- **Environment scrubbed** to `PATH`/`HOME`/`LANG`. A fresh `ProcessBuilder`
  or `Popen` inherits the full parent environment by default, and that is
  precisely how a credential leaks — the same lesson `core`'s
  `EnvIsolatedStdioClientTransport` learned for MCP stdio servers.
- **rlimits**: address space (Python), CPU seconds, process/thread count, open
  files, and file size. `RLIMIT_FSIZE` is also how output is capped — the
  kernel kills a process that writes past it, which is stronger than reading a
  pipe and hoping the producer stops.
  *Java is deliberately exempt from `RLIMIT_AS`*: a HotSpot JVM reserves far
  more virtual address space than it commits, so an `RLIMIT_AS` near the
  intended heap makes the JVM fail to start rather than use less memory. Java
  is bounded by `-Xmx` and `-XX:MaxMetaspaceSize` instead.
- **Wall-clock kill** of the whole process group with `SIGKILL`.
- **Fresh working directory** per execution, destroyed afterwards.

### Layer 4 — the code cannot express the dangerous thing

**Java — this layer is strong and checkable.**

Source is compiled by `javac`, and then **the compiled bytecode is verified**,
not the source text. Every class, field and method a class file can reach must
be named in its constant pool; the JVM cannot link to a symbol that is not
there. So a complete scan of the pool is a complete answer to "what can this
code touch?", in a way that grepping source — with its comments, string
literals, unicode escapes and generics — never is.

The precise rule (`app/classfile.py`):

> A denied class may never be the **owner of a `Fieldref` or `Methodref`**.

That is the whole gate, and it is sufficient: there is no way to invoke a
method or read a field without such an entry. Bare `CONSTANT_Class` entries and
type descriptors are checked more loosely, because naming a type grants no
capability — you cannot do anything with a `Class` object once every method on
`java.lang.Class` is unreachable.

Denied: `java.lang.System` (the door to `getenv`, `getProperty`, `exit`,
`loadLibrary`), `Runtime`, `ProcessBuilder`, `Thread`, `Class`, `ClassLoader`,
all of `java.io`, `java.nio`, `java.net`, `java.security`, `java.sql`,
`java.lang.reflect`, `java.util.concurrent`, `javax.*`, `sun.*`, `jdk.*`, and
everything not on the allowlist. Permitted: collections, streams, text, regex,
time, `BigDecimal`/`BigInteger`, `Math` — computation, and nothing else.

`java.lang.invoke` is the one subtlety. Lambdas, string `+`, records and
pattern switches all compile to an `invokedynamic` bootstrapped there, so it
cannot be denied outright. It is denied as a *call target* and re-permitted only
for the exact `(owner, method)` bootstrap pairs, which links lambdas fine while
leaving `MethodHandles.lookup().findStatic(...)` — the obvious runtime route to
a denied class — unreferenceable.

At runtime the JVM additionally starts with `--limit-modules java.base`, so
`java.net.http`, `java.sql` and every `jdk.*` internal are not even observable.

**Python — this layer is hardening, not a boundary. Read this part carefully.**

CPython object graphs are richly interconnected and the history of "safe eval"
is a history of escapes. The AST checks close the well-known routes — dunder
attribute access, `getattr`/`setattr`, `eval`/`exec`/`compile`, `open`, dynamic
import, imports outside a small allowlist — and at runtime `__import__` is
replaced with an allowlist-consulting version and `__builtins__` with a curated
mapping. The interpreter runs `-I -S -B` (isolated, no `site`, no `.pyc`).

**None of that should be treated as proof that a hostile Python tool cannot
reach the interpreter's internals.** For Python, layers 0–3 are the security
argument; layer 4 is what makes mistakes fail early with a readable message.

If you need a boundary for Python as strong as the Java one, the answer is not
a longer denylist — it is one-process-per-execution in a separate kernel
namespace (gVisor, or a per-call container), which is a larger piece of
infrastructure than this.

### Layer 5 — budgets and accountability

Per-tenant tool count, wall-clock timeout, memory ceiling, source size, input
size, result size and service-wide concurrency are all capped, with the tenant's
requested values clamped to the deployment's maxima. `core` records an audit-log
entry for every create/update/delete/enable, and every execution flows through
the same `GuardedToolCallback` / `LoggingToolCallback` / circuit-breaker stack
as any other agent tool.

### What is explicitly NOT claimed

- Not a defence against a **CPython or JVM 0-day**. Layers 0–2 are what limit
  the blast radius if one lands.
- Not a defence against **resource abuse in aggregate** beyond the caps above.
- Python static analysis is **not** claimed to be escape-proof (see Layer 4).
- Side channels between concurrent executions in the same container (timing,
  cache) are **not** addressed.

---

## API

All routes except `/health` require `X-Internal-Token` (constant-time compared)
and `X-Tenant-Id`. An unset token rejects everything — there is no
unauthenticated mode.

| Route | Purpose |
| --- | --- |
| `GET /health` | Liveness, plus which isolation controls are actually active |
| `POST /v1/validate` | Compile + verify without running |
| `POST /v1/execute` | Compile (or reuse cache) + verify + run |

A rejected program is a **200 with `valid: false`** — a verdict about the
tenant's code, not a failure of the call. `core` stores it so the author sees
the compiler and verifier messages and can fix them.

`errorType` is a closed set: `VALIDATION`, `COMPILE`, `RUNTIME`, `TIMEOUT`,
`MEMORY`, `OUTPUT_TOO_LARGE`, `INTERNAL`.

Compiled Java classes are cached per tenant, keyed by SHA-256 of the source. The
tenant is in the cache path even though identical source yields identical bytes:
it keeps one tenant's artifacts off another's path, makes per-tenant deletion a
single `rmtree`, and leaves no shared-cache timing signal.

---

## Running the tests

```bash
cd tool-sandbox && python -m pytest tests/ -q
```

The Java tests compile real fixtures with a real `javac` and assert on the
resulting class files, because verifying bytecode is the entire premise — a test
against hand-rolled class files would be testing the wrong thing. They cover
both directions, and **the acceptance half matters as much as the rejection
half**: lambdas, records, streams and string concatenation must keep working, or
a well-meaning tightening of the policy makes the feature unusable rather than
merely insecure.

`javac` on `PATH` is required; those tests skip without it.

---

## Enabling it

Off by default at both ends. In `deploy/.env`:

```bash
TOOL_SANDBOX_ENABLED=true
TOOL_SANDBOX_INTERNAL_TOKEN=$(openssl rand -hex 32)
```

then restart `core` and bring up `tool-sandbox`. With `ENABLED=false` core never
builds the client bean, so no agent is offered a custom code tool and no request
is ever made; with an empty token the sandbox rejects everything. Both halves
fail closed independently.
