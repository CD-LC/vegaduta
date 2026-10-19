"""Java class-file constant-pool verifier - the authoritative gate for Java.

Why the constant pool, rather than scanning source or bytecode instructions:
**every** class, field and method that a class file can possibly touch must be
named in its constant pool. The JVM has no way to link to a symbol that is not
there, and there is no string-to-code path left once ``java.lang.Class``,
``java.lang.ClassLoader`` and ``java.lang.reflect`` are unreferenceable. So a
complete scan of the pool is a complete answer to "what can this code reach?",
in a way that grepping source (comments, unicode escapes, string concatenation)
never is.

The one subtlety is ``java.lang.invoke``. Lambdas, string ``+``, records and
pattern switches all compile to an ``invokedynamic`` whose bootstrap method
lives there, so the package cannot be denied outright. It is therefore denied
*as a class* and re-permitted only for the exact (owner, method) bootstrap
pairs in :mod:`policy`, with its signature types allowed in descriptors only.
That combination links lambdas fine while leaving
``MethodHandles.lookup().findStatic(...)`` - the obvious way to reach a denied
class by name at runtime - unreferenceable.
"""

from __future__ import annotations

import re
import struct
from dataclasses import dataclass, field

from . import policy

CONSTANT_Utf8 = 1
CONSTANT_Integer = 3
CONSTANT_Float = 4
CONSTANT_Long = 5
CONSTANT_Double = 6
CONSTANT_Class = 7
CONSTANT_String = 8
CONSTANT_Fieldref = 9
CONSTANT_Methodref = 10
CONSTANT_InterfaceMethodref = 11
CONSTANT_NameAndType = 12
CONSTANT_MethodHandle = 15
CONSTANT_MethodType = 16
CONSTANT_Dynamic = 17
CONSTANT_InvokeDynamic = 18
CONSTANT_Module = 19
CONSTANT_Package = 20

ACC_NATIVE = 0x0100

# Descriptors carry no generics, so a flat scan for object types is exact.
_DESCRIPTOR_TYPE = re.compile(r"L([^;\[]+);")


class ClassFileError(ValueError):
    """Malformed or truncated class file. Always treated as a rejection."""


@dataclass
class _Reader:
    data: bytes
    pos: int = 0

    def u1(self) -> int:
        return self._take(1, "B")

    def u2(self) -> int:
        return self._take(2, ">H")

    def u4(self) -> int:
        return self._take(4, ">I")

    def _take(self, size: int, fmt: str) -> int:
        if self.pos + size > len(self.data):
            raise ClassFileError("truncated class file")
        if fmt == "B":
            value = self.data[self.pos]
        else:
            value = struct.unpack_from(fmt, self.data, self.pos)[0]
        self.pos += size
        return value

    def take(self, size: int) -> bytes:
        if self.pos + size > len(self.data):
            raise ClassFileError("truncated class file")
        chunk = self.data[self.pos:self.pos + size]
        self.pos += size
        return chunk

    def skip(self, size: int) -> None:
        if self.pos + size > len(self.data):
            raise ClassFileError("truncated class file")
        self.pos += size


@dataclass
class ParsedClass:
    """Just enough of a class file to answer "what does this reference?"."""

    this_class: str
    #: (owner_internal_name, member_name) for every Fieldref/Methodref/InterfaceMethodref
    members: list[tuple[str, str]] = field(default_factory=list)
    #: every CONSTANT_Class name, in internal form (``java/lang/String``, ``[Ljava/lang/String;``)
    class_refs: list[str] = field(default_factory=list)
    #: every descriptor string reachable from the pool or from field/method declarations
    descriptors: list[str] = field(default_factory=list)
    has_native_method: bool = False
    has_module_entry: bool = False


def parse(data: bytes) -> ParsedClass:
    r = _Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise ClassFileError("not a Java class file")
    r.u2()  # minor
    r.u2()  # major

    count = r.u2()
    # 1-indexed; Long/Double occupy two slots and leave a hole.
    pool: dict[int, tuple] = {}
    index = 1
    while index < count:
        tag = r.u1()
        if tag == CONSTANT_Utf8:
            length = r.u2()
            raw = r.take(length)
            # Modified UTF-8; surrogateescape keeps us from dying on the
            # differences, which never matter for the names we compare.
            pool[index] = (tag, raw.decode("utf-8", errors="surrogateescape"))
        elif tag in (CONSTANT_Integer, CONSTANT_Float):
            r.skip(4)
            pool[index] = (tag, None)
        elif tag in (CONSTANT_Long, CONSTANT_Double):
            r.skip(8)
            pool[index] = (tag, None)
            index += 1  # the documented two-slot hole
        elif tag in (CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType,
                     CONSTANT_Module, CONSTANT_Package):
            pool[index] = (tag, r.u2())
        elif tag in (CONSTANT_Fieldref, CONSTANT_Methodref,
                     CONSTANT_InterfaceMethodref, CONSTANT_NameAndType,
                     CONSTANT_Dynamic, CONSTANT_InvokeDynamic):
            pool[index] = (tag, (r.u2(), r.u2()))
        elif tag == CONSTANT_MethodHandle:
            r.u1()
            pool[index] = (tag, r.u2())
        else:
            raise ClassFileError(f"unknown constant pool tag {tag}")
        index += 1

    def utf8(i: int) -> str:
        entry = pool.get(i)
        if not entry or entry[0] != CONSTANT_Utf8:
            raise ClassFileError(f"constant pool index {i} is not a Utf8")
        return entry[1]

    def class_name(i: int) -> str:
        entry = pool.get(i)
        if not entry or entry[0] != CONSTANT_Class:
            raise ClassFileError(f"constant pool index {i} is not a Class")
        return utf8(entry[1])

    access_flags = r.u2()  # noqa: F841 - read for position only
    this_class = class_name(r.u2())

    parsed = ParsedClass(this_class=this_class)

    # --- everything reachable from the pool itself ---
    for tag, payload in pool.values():
        if tag == CONSTANT_Class:
            parsed.class_refs.append(utf8(payload))
        elif tag in (CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref):
            owner = class_name(payload[0])
            nat = pool.get(payload[1])
            if not nat or nat[0] != CONSTANT_NameAndType:
                raise ClassFileError("member ref without a NameAndType")
            parsed.members.append((owner, utf8(nat[1][0])))
        elif tag == CONSTANT_NameAndType:
            parsed.descriptors.append(utf8(payload[1]))
        elif tag == CONSTANT_MethodType:
            parsed.descriptors.append(utf8(payload))
        elif tag in (CONSTANT_Module, CONSTANT_Package):
            parsed.has_module_entry = True

    # --- declarations, so an unused-but-declared denied type is still caught ---
    super_index = r.u2()
    if super_index:
        parsed.class_refs.append(class_name(super_index))
    for _ in range(r.u2()):
        parsed.class_refs.append(class_name(r.u2()))

    def skip_attributes() -> None:
        for _ in range(r.u2()):
            r.u2()
            r.skip(r.u4())

    for _ in range(r.u2()):  # fields
        r.u2()
        r.u2()
        parsed.descriptors.append(utf8(r.u2()))
        skip_attributes()

    for _ in range(r.u2()):  # methods
        flags = r.u2()
        if flags & ACC_NATIVE:
            parsed.has_native_method = True
        r.u2()
        parsed.descriptors.append(utf8(r.u2()))
        skip_attributes()

    skip_attributes()  # class attributes
    return parsed


def _to_dotted(internal: str) -> str | None:
    """Internal class name -> dotted, or ``None`` for a non-reference type.

    Handles the array forms that appear in CONSTANT_Class (``[Ljava/lang/String;``,
    ``[[I``). A primitive array references no class and needs no check.
    """
    name = internal.lstrip("[")
    if name != internal:  # it was an array
        if name.startswith("L") and name.endswith(";"):
            name = name[1:-1]
        else:
            return None  # primitive element type
    return name.replace("/", ".")


def verify(class_files: dict[str, bytes]) -> list[str]:
    """Check every compiled class against the policy.

    ``class_files`` maps a display name (usually the file name) to its bytes.
    Returns a list of human-readable violations; empty means the unit is
    acceptable. Never raises for policy reasons - a malformed class file is
    reported as a violation, not an exception, so a rejection always reaches
    the user as an explanation rather than a 500.
    """
    parsed: dict[str, ParsedClass] = {}
    violations: list[str] = []

    for name, data in class_files.items():
        try:
            parsed[name] = parse(data)
        except ClassFileError as exc:
            violations.append(f"{name}: unreadable class file ({exc})")
    if violations:
        return violations

    own = frozenset(p.this_class.replace("/", ".") for p in parsed.values())

    # A tool compiles into the default package. Refusing a package declaration
    # is cheap and removes a whole class of confusion, including any attempt to
    # declare a class into `vega.*` or `java.*` and shadow a trusted one.
    for name, p in parsed.items():
        if "/" in p.this_class:
            violations.append(
                f"{name}: declares a package. A custom tool must be in the "
                f"default package - remove the `package` statement."
            )

    seen: set[str] = set()
    for name, p in parsed.items():
        if p.has_native_method:
            violations.append(f"{name}: declares a native method, which is not permitted.")
        if p.has_module_entry:
            violations.append(
                f"{name}: contains module/package constants, which are not permitted.")

        # The gate that matters: a class cannot be *used* without a member ref
        # naming it as owner, so this check is the strict one.
        for owner, member in p.members:
            dotted = _to_dotted(owner)
            if dotted is None:
                continue
            if not policy.java_member_allowed(dotted, member, own):
                key = f"{dotted}#{member}"
                if key in seen:
                    continue
                seen.add(key)
                violations.append(_explain(dotted, member))

        # Bare mentions - class entries and descriptors - are inert on their
        # own, so they get the looser predicate. It still catches a denied type
        # used as a field type, a cast target or a declared throwable, while
        # tolerating the BootstrapMethods and InnerClasses entries that javac
        # emits for every lambda, record and string concatenation.
        for ref in p.class_refs:
            dotted = _to_dotted(ref)
            if dotted is None or dotted in seen:
                continue
            if not policy.java_named_type_allowed(dotted, own):
                seen.add(dotted)
                violations.append(_explain(dotted))

        for descriptor in p.descriptors:
            for raw in _DESCRIPTOR_TYPE.findall(descriptor):
                dotted = raw.replace("/", ".")
                if dotted in seen:
                    continue
                if not policy.java_named_type_allowed(dotted, own):
                    seen.add(dotted)
                    violations.append(_explain(dotted))

    return violations


def _explain(dotted: str, member: str | None = None) -> str:
    target = f"{dotted}.{member}" if member else dotted
    hint = ""
    if dotted == "java.lang.System":
        hint = (
            " (System is unavailable because it exposes getenv, getProperty and exit. "
            "Use vega.Log.info(...) to print, and java.time.Instant for the clock.)"
        )
    elif dotted.startswith(("java.io.", "java.nio.")):
        hint = " (a custom tool has no filesystem access)"
    elif dotted.startswith("java.net."):
        hint = " (a custom tool has no network access)"
    elif (dotted.startswith(("java.lang.reflect.", "java.lang.invoke."))
          or dotted == "java.lang.Class"):
        hint = " (reflection is unavailable - it would defeat every other restriction)"
    elif dotted.startswith("java.util.concurrent.") or dotted == "java.lang.Thread":
        hint = " (a custom tool runs single-threaded)"
    elif member and member.startswith("parallel"):
        hint = " (a custom tool runs single-threaded - use the sequential form)"
    elif member in ("getBoolean", "getInteger", "getLong"):
        hint = (" (this reads a JVM system property; use Boolean.parseBoolean, "
                "Integer.parseInt or Long.parseLong)")
    elif dotted.endswith("ResourceBundle"):
        hint = " (resource bundles load classes and files by name)"
    return f"Not permitted in a custom tool: {target}{hint}"
