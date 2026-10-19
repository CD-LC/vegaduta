"""Static-analysis policy: what a tenant-authored tool is allowed to reference.

This module is *only* the policy data plus small pure predicates. The two
enforcement points live next to it and are deliberately separate so the policy
can be unit-tested on its own:

  * ``classfile.py``  - verifies COMPILED Java bytecode against this policy.
    This is the authoritative gate for Java: every class, field and method a
    class file can possibly touch must appear in its constant pool, so
    scanning the whole pool is complete. Source-level checks are only there to
    produce nicer error messages.
  * ``pyvalidate.py`` - verifies a Python AST against this policy.

**Read this honestly:** the Java bytecode gate is a strong, checkable property.
The Python AST gate is *not* equivalently strong - CPython has a long history
of sandbox escapes through attribute chains, and no AST allowlist should be
treated as a hard boundary on its own. For BOTH languages the real security
boundary is the operating-system containment in ``proc.py`` plus the fact that
this whole service runs in a container that holds no platform credentials and
has no route to the platform's data tier. The static policy is the first layer,
not the only one.
"""

from __future__ import annotations

# --------------------------------------------------------------------------
# Java
# --------------------------------------------------------------------------

# Denials are checked FIRST and always win over any allow rule below. Anything
# in these packages can reach the filesystem, the network, other processes, the
# class loader, or the JVM's own internals.
JAVA_DENIED_PREFIXES: tuple[str, ...] = (
    "java.io.",
    "java.nio.",
    "java.net.",
    "java.security.",
    "java.sql.",
    "java.rmi.",
    "java.applet.",
    "java.beans.",
    "java.awt.",
    "java.lang.reflect.",
    "java.lang.ref.",
    "java.lang.module.",
    "java.lang.instrument.",
    "java.lang.management.",
    "java.lang.annotation.",
    "java.lang.constant.",
    "java.lang.classfile.",
    "java.lang.foreign.",
    "java.lang.System$",
    "java.util.concurrent.",
    "java.util.jar.",
    "java.util.zip.",
    "java.util.logging.",
    "java.util.prefs.",
    "java.util.spi.",
    "javax.",
    "jakarta.",
    "sun.",
    "com.sun.",
    "jdk.",
    "org.",
    "net.",
    "com.",
    # java.lang.invoke and java.lang.runtime are *mostly* denied; the handful
    # of compiler-generated bootstrap entry points that lambdas, string
    # concatenation, records and pattern switches require are re-permitted
    # below by exact (owner, method) pair - never by prefix.
    "java.lang.invoke.",
    "java.lang.runtime.",
)

# Exact class denials inside otherwise-allowed packages. java.lang.System is
# the single most important entry here: it is the door to getenv, getProperty,
# exit, load/loadLibrary, and the raw in/out/err streams.
JAVA_DENIED_CLASSES: frozenset[str] = frozenset({
    "java.lang.System",
    "java.lang.Runtime",
    "java.lang.Process",
    "java.lang.ProcessBuilder",
    "java.lang.ProcessHandle",
    "java.lang.Thread",
    "java.lang.ThreadGroup",
    "java.lang.ThreadLocal",
    "java.lang.InheritableThreadLocal",
    "java.lang.Class",
    "java.lang.ClassLoader",
    "java.lang.ClassValue",
    "java.lang.Compiler",
    "java.lang.SecurityManager",
    "java.lang.Module",
    "java.lang.ModuleLayer",
    "java.lang.Package",
    "java.lang.StackWalker",
    "java.lang.Shutdown",
    "java.util.ServiceLoader",
    "java.util.Timer",
    "java.util.TimerTask",
    # Both take a File/OutputStream in some overload; denying them outright is
    # simpler to reason about than relying on the descriptor check to catch it.
    "java.util.Scanner",
    "java.util.Formatter",
    "java.util.PropertyPermission",
    # ResourceBundle.getBundle(name) loads and instantiates a class (or reads a
    # classpath resource) BY NAME through the caller's class loader - the same
    # string-to-code door as Class.forName. getBundle is inherited, so a
    # PropertyResourceBundle.getBundle(...) call site names the subclass as the
    # Methodref owner; all three are denied.
    "java.util.ResourceBundle",
    "java.util.ListResourceBundle",
    "java.util.PropertyResourceBundle",
})

# Individual members denied inside otherwise-allowed classes.
#
#   * Boolean.getBoolean / Integer.getInteger / Long.getLong read a JVM SYSTEM
#     PROPERTY by name: System is denied for exactly that reason (java.home,
#     user.dir, java.vm.name ...), and these three are the same read behind a
#     different class. Parsing (parseInt, valueOf, ...) is unaffected.
JAVA_DENIED_MEMBERS: frozenset[tuple[str, str]] = frozenset({
    ("java.lang.Boolean", "getBoolean"),
    ("java.lang.Integer", "getInteger"),
    ("java.lang.Long", "getLong"),
})

# Member NAMES denied on any java.* owner. Parallel streams and the Arrays
# parallel* helpers run on the common ForkJoinPool, i.e. they start threads
# without the tool's constant pool ever naming java.util.concurrent - which
# would quietly contradict "a custom tool runs single-threaded". The sequential
# forms (stream(), Arrays.sort, ...) are unaffected. A tool's OWN method that
# happens to be called `parallel` is fine (own classes are checked first).
JAVA_DENIED_MEMBER_NAMES: frozenset[str] = frozenset({
    "parallel",
    "parallelStream",
    "parallelSort",
    "parallelPrefix",
    "parallelSetAll",
})

# Packages a tool may freely use: pure computation, collections, text, time and
# math. Nothing here can perform I/O of any kind on its own.
JAVA_ALLOWED_PREFIXES: tuple[str, ...] = (
    "java.lang.",       # minus JAVA_DENIED_CLASSES / prefixes above
    "java.util.",       # minus the sub-packages denied above
    "java.math.",
    "java.time.",
    "java.text.",
    "vega.",            # the tiny trusted helper API this service ships
)

# The compiler-generated bootstrap methods. A lambda, a `+` on non-constant
# strings, a record's equals/hashCode/toString, and a pattern switch each emit
# an invokedynamic whose bootstrap is one of these. Permitting the exact
# (owner, method) pair - rather than the class - is what stops
# `MethodHandles.lookup().findStatic(...)` from being used to reach a denied
# class by name at runtime.
JAVA_ALLOWED_BOOTSTRAPS: frozenset[tuple[str, str]] = frozenset({
    ("java.lang.invoke.LambdaMetafactory", "metafactory"),
    ("java.lang.invoke.LambdaMetafactory", "altMetafactory"),
    ("java.lang.invoke.StringConcatFactory", "makeConcat"),
    ("java.lang.invoke.StringConcatFactory", "makeConcatWithConstants"),
    ("java.lang.runtime.ObjectMethods", "bootstrap"),
    ("java.lang.runtime.SwitchBootstraps", "typeSwitch"),
    ("java.lang.runtime.SwitchBootstraps", "enumSwitch"),
})

# Types that a class file may *name* - in a descriptor, or as a bare
# CONSTANT_Class entry - but may never invoke a method on or read a field of.
#
# Naming a class is inert. To actually do anything with one you need a
# Fieldref or Methodref that lists it as the owner, and those are held to the
# strict allowlist above (with only the bootstrap carve-out). So this set can
# safely contain types that would be alarming as call targets:
#
#   * the bootstrap factories and their signature types - javac emits
#     CONSTANT_Class entries for these in the BootstrapMethods and InnerClasses
#     attributes of any class using a lambda, a `+` on strings, a record or a
#     pattern switch, so denying them here would reject ordinary Java;
#   * java.lang.Class - unavoidable, because `Object.getClass()` is an
#     allowlisted method whose descriptor returns one. A tool can therefore
#     hold a Class object, and it is inert: every method on it lives behind a
#     Methodref owned by java.lang.Class, which is strictly denied.
JAVA_DESCRIPTOR_ONLY: frozenset[str] = frozenset({
    "java.lang.Class",
    "java.lang.invoke.LambdaMetafactory",
    "java.lang.invoke.StringConcatFactory",
    "java.lang.invoke.MethodHandles",
    "java.lang.invoke.MethodHandles$Lookup",
    "java.lang.invoke.MethodHandle",
    "java.lang.invoke.MethodType",
    "java.lang.invoke.CallSite",
    "java.lang.invoke.TypeDescriptor",
    "java.lang.invoke.TypeDescriptor$OfField",
    "java.lang.invoke.TypeDescriptor$OfMethod",
    "java.lang.invoke.VarHandle",
    "java.lang.runtime.ObjectMethods",
    "java.lang.runtime.SwitchBootstraps",
})

# Surfaced to the user in the editor's help text.
JAVA_ENTRYPOINT_HINT = (
    "Your program must declare `public class Tool` with a "
    "`public static Object run(java.util.Map<String, Object> input)` method."
)


def java_class_allowed(name: str, own_classes: frozenset[str] = frozenset()) -> bool:
    """True if ``name`` may own a field or method the code calls.

    This is the strict predicate - the one that decides what the code can
    actually *reach*. ``own_classes`` is the set of classes produced by this
    very compilation; a tool's own helper, nested and synthetic classes must
    obviously be callable.
    """
    if name in own_classes:
        return True
    if name in JAVA_DENIED_CLASSES:
        return False
    # A nested class inherits its outer class's denial (Thread$State,
    # ProcessBuilder$Redirect, Runtime$Version ...). Without this the
    # allowed-prefix rule below would admit them: their names are not in
    # JAVA_DENIED_CLASSES, which lists only top-level classes.
    if "$" in name and name.split("$", 1)[0] in JAVA_DENIED_CLASSES:
        return False
    if any(name.startswith(p) for p in JAVA_DENIED_PREFIXES):
        return False
    return any(name.startswith(p) for p in JAVA_ALLOWED_PREFIXES)


def java_named_type_allowed(name: str, own_classes: frozenset[str] = frozenset()) -> bool:
    """True if ``name`` may be *mentioned* - in a descriptor or a class entry.

    Deliberately looser than :func:`java_class_allowed`: mentioning a type
    grants no capability on its own, so the useful thing to check here is that
    a denied type is not being smuggled in as a field type or a cast target,
    while still tolerating the compiler-generated references every modern Java
    class carries.
    """
    return name in JAVA_DESCRIPTOR_ONLY or java_class_allowed(name, own_classes)


def java_member_allowed(owner: str, member: str, own_classes: frozenset[str] = frozenset()) -> bool:
    """True if ``owner.member`` may be referenced (field or method).

    Owners in the invoke/runtime bootstrap packages are gated per-method; every
    other owner inherits the class-level decision.
    """
    if (owner, member) in JAVA_ALLOWED_BOOTSTRAPS:
        return True
    if owner in own_classes:
        return True
    if (owner, member) in JAVA_DENIED_MEMBERS or member in JAVA_DENIED_MEMBER_NAMES:
        return False
    return java_class_allowed(owner, own_classes)


# --------------------------------------------------------------------------
# Python
# --------------------------------------------------------------------------

# Import allowlist. Deliberately small and purely computational. Note the
# absences: os, sys, subprocess, socket, importlib, ctypes, pickle, shutil,
# pathlib, threading, multiprocessing, urllib, http, and anything that can
# open a file, spawn a process or reach the network.
#
# `types` is ALSO absent, deliberately (SVC-003): FunctionType, CodeType,
# MethodType etc. are object-construction primitives - with a code object in
# hand they rebuild functions over arbitrary globals - and nothing a
# pure-computation tool does needs them. Re-checked for Python 3.12 (the
# image's interpreter): every module listed here still exists in 3.12's stdlib;
# none of the PEP 594 / 3.12 removals (imp, distutils, asynchat, asyncore,
# smtpd) was ever on this list.
#
# Allowed modules are NOT handed to the tool as-is: the harness wraps each one
# in a read-only proxy that hides private names and any submodule outside this
# list (py_harness._ModuleProxy), because a module object's attributes include
# whatever IT imported - `uuid.os`, `random._os`, `typing.sys` - and that is a
# plain, dunder-free attribute access the AST check cannot tell apart from
# `uuid.uuid4`.
PYTHON_ALLOWED_MODULES: frozenset[str] = frozenset({
    "base64",
    "binascii",
    "bisect",
    "calendar",
    "cmath",
    "collections",
    "copy",
    "dataclasses",
    "datetime",
    "decimal",
    "difflib",
    "enum",
    "fractions",
    "functools",
    "hashlib",
    "heapq",
    "hmac",
    "html",
    "itertools",
    "json",
    "math",
    "numbers",
    "operator",
    "random",
    "re",
    "statistics",
    "string",
    "textwrap",
    "time",
    "typing",
    "unicodedata",
    "uuid",
    "zoneinfo",
})

# Builtins removed from the tool's namespace. `getattr`/`setattr` are here for
# a specific reason: the AST check below rejects literal dunder attribute
# access (`x.__class__`), but `getattr(x, "__cl" + "ass__")` would slip past a
# purely syntactic check, and `__class__.__bases__[0].__subclasses__()` is the
# classic route from any object to every loaded class.
PYTHON_DENIED_BUILTINS: frozenset[str] = frozenset({
    "__import__",
    "breakpoint",
    "compile",
    "delattr",
    "dir",
    "eval",
    "exec",
    "exit",
    "getattr",
    "globals",
    "help",
    # NOTE: `input` is deliberately NOT listed, even though the builtin is
    # unavailable. It is the parameter name in the `run(input)` contract, so
    # flagging the name syntactically would reject every tool that reads its
    # own argument. The builtin is removed a different way - it is simply
    # absent from PYTHON_ALLOWED_BUILTINS, so at runtime the only `input` in
    # scope is the parameter.
    "locals",
    "memoryview",
    "object",
    "open",
    "quit",
    "setattr",
    "super",
    "vars",
})

# Attribute names rejected on ANY receiver, and as the imported name in a
# `from X import name` statement. Checking the attribute name regardless of
# what it is looked up on is what makes aliasing useless: `import operator as
# o; o.attrgetter(...)` and `op = operator; op.methodcaller(...)` are rejected
# exactly like `operator.attrgetter(...)`. Each entry is a documented route
# around the "no dynamic attribute lookup" rule (getattr/setattr are removed
# builtins) or a way to reach an interpreter frame (SVC-003):
#
#   * operator.attrgetter / operator.methodcaller - getattr() and a method call
#     by *string name*, so `attrgetter("__cl" + "ass__")` defeats the literal
#     dunder check. The rest of `operator` (add, itemgetter, ...) is harmless
#     and stays available.
#   * typing.get_type_hints / typing.ForwardRef - evaluate string annotations
#     with eval(), i.e. arbitrary expressions the AST check never parsed.
#   * string.Formatter - Formatter().get_field("0.__class__", ...) returns the
#     real attribute object (str.format only ever returns a string).
#   * mro - `int.mro()[-1]` is `object`, the root of every subclass traversal.
#   * generator / coroutine / frame / traceback / code internals - the classic
#     `gen.gi_frame.f_back.f_globals` walk out of the tool's namespace into the
#     trusted harness frame, whose globals hold the real `os` and __import__.
PYTHON_DENIED_ATTRIBUTES: frozenset[str] = frozenset({
    "attrgetter",
    "methodcaller",
    "get_type_hints",
    "ForwardRef",
    "Formatter",
    "mro",
    "gi_frame", "gi_code", "gi_yieldfrom", "gi_running", "gi_suspended",
    "cr_frame", "cr_code", "cr_await", "cr_origin", "cr_running", "cr_suspended",
    "ag_frame", "ag_code", "ag_await", "ag_running",
    "f_back", "f_builtins", "f_code", "f_globals", "f_locals", "f_trace", "f_lasti",
    "tb_frame", "tb_next", "tb_lasti",
    "co_code", "co_consts", "co_names", "co_varnames",
})

# Dunder-shaped string literals that pyvalidate's dunder-literal check still
# permits, because they are idiomatic and inert (`if __name__ == "__main__":`).
PYTHON_PERMITTED_DUNDER_STRINGS: frozenset[str] = frozenset({"__main__"})

# Names that must never appear at all, in any position.
PYTHON_DENIED_NAMES: frozenset[str] = frozenset({
    "__builtins__",
    "__loader__",
    "__spec__",
    "__import__",
})

PYTHON_ENTRYPOINT_HINT = (
    "Your program must define a top-level function `run(input)` that takes a "
    "dict and returns a JSON-serializable value."
)

# The builtins a tool actually gets. Anything not listed simply does not exist
# inside the tool's namespace. Note what is absent by omission rather than by
# name: `type` (so `type(x).mro()` and 3-arg `type(...)` class construction are
# unavailable) and `input`. Neither is in PYTHON_DENIED_BUILTINS because both
# are common *variable* names (`type = input.get("type")`), so a syntactic ban
# would reject ordinary tools; at runtime the builtin simply does not resolve.
# `vars`, `dir`, `globals`, `locals`, `getattr` and `setattr` are denied by
# name above and are likewise absent here.
PYTHON_ALLOWED_BUILTINS: tuple[str, ...] = (
    "abs", "all", "any", "ascii", "bin", "bool", "bytearray", "bytes",
    "callable", "chr", "complex", "dict", "divmod", "enumerate", "filter",
    "float", "format", "frozenset", "hash", "hex", "int", "isinstance",
    "issubclass", "iter", "len", "list", "map", "max", "min", "next", "oct",
    "ord", "pow", "print", "range", "repr", "reversed", "round", "set",
    "slice", "sorted", "str", "sum", "tuple", "zip",
    # Exceptions a tool may legitimately raise or catch.
    "ArithmeticError", "AssertionError", "AttributeError", "Exception",
    "IndexError", "KeyError", "LookupError", "NotImplementedError",
    "OverflowError", "RuntimeError", "StopIteration", "TypeError",
    "ValueError", "ZeroDivisionError",
    "True", "False", "None",
)
