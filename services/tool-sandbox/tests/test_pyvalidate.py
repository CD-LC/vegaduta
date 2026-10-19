"""Tests for the Python AST gate.

Read these as "the known escape routes are closed", not "Python is contained" -
see ``app/pyvalidate.py``'s module docstring for why that distinction matters.
The containment claim rests on ``app/proc.py`` and on this service holding no
platform credentials; these tests only assert that the casual and well-known
routes fail early with a readable message.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import pyvalidate


def messages(source: str) -> str:
    return " | ".join(v.message for v in pyvalidate.validate(source))


DENIED = {
    "os import": "import os\ndef run(input): return os.environ\n",
    "sys import": "import sys\ndef run(input): return sys.path\n",
    "subprocess import": "import subprocess\ndef run(input): return subprocess.run(['ls'])\n",
    "socket import": "import socket\ndef run(input): return socket.socket()\n",
    "from-import of a denied module": "from os import environ\ndef run(input): return environ\n",
    "submodule of a denied module": "import os.path\ndef run(input): return 1\n",
    "relative import": "from . import something\ndef run(input): return 1\n",
    "importlib": "import importlib\ndef run(input): return importlib\n",
    "ctypes": "import ctypes\ndef run(input): return ctypes\n",
    "pickle": "import pickle\ndef run(input): return pickle\n",
    "open": "def run(input):\n    return open('/etc/passwd').read()\n",
    "eval": "def run(input):\n    return eval('1+1')\n",
    "exec": "def run(input):\n    exec('x=1')\n    return 1\n",
    "compile": "def run(input):\n    return compile('1', 'x', 'eval')\n",
    "__import__": "def run(input):\n    return __import__('os')\n",
    "getattr": "def run(input):\n    return getattr(input, 'keys')\n",
    "globals": "def run(input):\n    return globals()\n",
    "dunder class traversal": "def run(input):\n    return input.__class__\n",
    "subclasses escape": (
        "def run(input):\n"
        "    return ().__class__.__bases__[0].__subclasses__()\n"
    ),
    "function globals escape": "def run(input):\n    return run.__globals__\n",
    "builtins name": "def run(input):\n    return __builtins__\n",
    "explicit object subclass": "class Evil(object):\n    pass\ndef run(input): return 1\n",
    "async entrypoint": "async def run(input):\n    return 1\n",
    # ---- SVC-003: routes around the "no dynamic attribute lookup" rule ----
    "types module": "import types\ndef run(input): return types.FunctionType\n",
    "from types import": "from types import CodeType\ndef run(input): return 1\n",
    "operator.attrgetter": (
        "import operator\n"
        "def run(input):\n"
        "    return operator.attrgetter('__cl' + 'ass__')(input)\n"
    ),
    "operator.methodcaller": (
        "import operator\n"
        "def run(input):\n"
        "    return operator.methodcaller('keys')(input)\n"
    ),
    "from operator import attrgetter": (
        "from operator import attrgetter\ndef run(input): return attrgetter('x')(input)\n"),
    "from operator import methodcaller as alias": (
        "from operator import methodcaller as mc\ndef run(input): return mc('keys')(input)\n"),
    "aliased module attrgetter": (
        "import operator as o\ndef run(input): return o.attrgetter('x')(input)\n"),
    "rebound module methodcaller": (
        "import operator\n"
        "def run(input):\n"
        "    op = operator\n"
        "    return op.methodcaller('keys')(input)\n"
    ),
    "star import": "from operator import *\ndef run(input): return 1\n",
    "typing.get_type_hints eval": (
        "import typing\n"
        "def f(x: 'input'): pass\n"
        "def run(input): return typing.get_type_hints(f)\n"
    ),
    "typing.ForwardRef": (
        "from typing import ForwardRef\ndef run(input): return ForwardRef('x')\n"),
    "string.Formatter.get_field": (
        "import string\n"
        "def run(input):\n"
        "    return string.Formatter().get_field('0.x', (input,), {})\n"
    ),
    "type(...).mro()": "def run(input):\n    return type(input).mro()\n",
    "int.mro() to object": "def run(input):\n    return int.mro()[-1]\n",
    "vars builtin": "def run(input):\n    return vars(input)\n",
    "dir builtin": "def run(input):\n    return dir(input)\n",
    "locals builtin": "def run(input):\n    return locals()\n",
    "generator frame walk": (
        "def gen():\n"
        "    yield 1\n"
        "def run(input):\n"
        "    return gen().gi_frame.f_back.f_globals\n"
    ),
    "traceback frame": (
        "def run(input):\n"
        "    try:\n"
        "        1 / 0\n"
        "    except ZeroDivisionError as exc:\n"
        "        return exc.with_traceback(None).tb_frame\n"
    ),
    "match-class dunder keyword": (
        "def run(input):\n"
        "    match 1:\n"
        "        case int(__class__=c):\n"
        "            return c\n"
    ),
    "dunder string literal": "def run(input):\n    return '__class__'\n",
    "dunder in format string": "def run(input):\n    return '{0.__class__}'.format(input)\n",
    "dunder in string annotation": "def f(x: '().__class__'): pass\ndef run(input): return 1\n",
    "dunder bytes literal": "def run(input):\n    return b'__globals__'\n",
    "private submodule import": "import zoneinfo._tzpath\ndef run(input): return 1\n",
    "private from-import": "from random import _os\ndef run(input): return 1\n",
    # ---- SEC-003: private attributes are where a module's imports live ----
    "random._os": (
        "import random\ndef run(input):\n    return random._os.system('id')\n"),
    "aliased random._os": (
        "import random as r\ndef run(input):\n    m = r\n    return m._os\n"),
    "private attribute on any object": (
        "import string\ndef run(input):\n    return string.Template('x')._invalid\n"),
    "private attribute on your own object": (
        "class Counter:\n"
        "    def __init__(self):\n"
        "        self._n = 0\n"
        "def run(input):\n"
        "    return Counter()._n\n"
    ),
    "name-mangled attribute": (
        "class A:\n    def f(self):\n        return self.__secret\n"
        "def run(input): return 1\n"),
    "match-class private keyword": (
        "import random\n"
        "def run(input):\n"
        "    match random:\n"
        "        case object(_os=o):\n"
        "            return o\n"
    ),
}


@pytest.mark.parametrize("name,source", sorted(DENIED.items()))
def test_denied_source_is_rejected(name: str, source: str) -> None:
    assert pyvalidate.validate(source), f"{name!r} was NOT rejected"


ALLOWED = {
    "trivial": "def run(input):\n    return {'greeting': 'hello'}\n",
    "arithmetic and comprehensions": (
        "def run(input):\n"
        "    values = input.get('values', [1, 2, 3])\n"
        "    squares = [v * v for v in values if v > 0]\n"
        "    return {'squares': squares, 'total': sum(squares)}\n"
    ),
    "allowed imports": (
        "import json\n"
        "import math\n"
        "import re\n"
        "from datetime import date\n"
        "from decimal import Decimal\n"
        "def run(input):\n"
        "    return {\n"
        "        'root': math.sqrt(16),\n"
        "        'matched': bool(re.match(r'\\\\d+', str(input.get('n', '42')))),\n"
        "        'day': date(2026, 8, 5).isoformat(),\n"
        "        'money': str(Decimal('1.005').quantize(Decimal('0.01'))),\n"
        "        'json': json.dumps({'ok': True}),\n"
        "    }\n"
    ),
    "helper functions and classes": (
        "class Basket:\n"
        "    def __init__(self, items):\n"
        "        self.items = items\n"
        "    def total(self):\n"
        "        return sum(self.items)\n"
        "def run(input):\n"
        "    return {'total': Basket(input.get('items', [1, 2])).total()}\n"
    ),
    "try/except and raise": (
        "def run(input):\n"
        "    try:\n"
        "        return {'n': int(input['n'])}\n"
        "    except (KeyError, ValueError) as exc:\n"
        "        return {'error': str(exc)}\n"
    ),
    "printing for debugging": (
        "def run(input):\n"
        "    print('input was', input)\n"
        "    return 'ok'\n"
    ),
    # ---- SVC-003 positives: the new rules must not catch ordinary code ----
    "operator arithmetic and itemgetter": (
        "import operator\n"
        "from operator import itemgetter\n"
        "from functools import reduce\n"
        "def run(input):\n"
        "    rows = [{'n': 3}, {'n': 1}]\n"
        "    return {'sum': reduce(operator.add, [1, 2, 3]),\n"
        "            'sorted': sorted(rows, key=itemgetter('n'))}\n"
    ),
    "math json re datetime together": (
        "import json, math, re\n"
        "from datetime import datetime, timedelta, timezone\n"
        "def run(input):\n"
        "    when = datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(days=1)\n"
        "    return json.loads(json.dumps({'pi': round(math.pi, 2),\n"
        "        'words': re.findall(r'\\\\w+', 'a b'), 'when': when.isoformat()}))\n"
    ),
    "typing annotations": (
        "from typing import Any\n"
        "def run(input: dict[str, Any]) -> dict[str, int]:\n"
        "    return {'n': len(input)}\n"
    ),
    "dunder names in a docstring": (
        "def run(input):\n"
        "    '''Returns input; see __init__ and __class__ docs.'''\n"
        "    return input\n"
    ),
    "__name__ == '__main__' idiom": (
        "def run(input):\n"
        "    return 1\n"
        "if __name__ == '__main__':\n"
        "    print(run({}))\n"
    ),
    "a variable named type": (
        "def run(input):\n"
        "    type = input.get('type', 'basic')\n"
        "    return {'type': type}\n"
    ),
    "double underscores that are not dunders": (
        "def run(input):\n"
        "    return {'key': 'snake__case__name', 'sep': '__'}\n"
    ),
    "public attributes on your own objects": (
        "class Counter:\n"
        "    def __init__(self):\n"
        "        self.n = 0\n"
        "def run(input):\n"
        "    c = Counter()\n"
        "    c.n += 1\n"
        "    _unused, value = 0, c.n\n"
        "    return value\n"
    ),
    "string.Template": (
        "from string import Template\n"
        "def run(input):\n"
        "    return Template('hi $name').substitute(name='x')\n"
    ),
}


@pytest.mark.parametrize("name,source", sorted(ALLOWED.items()))
def test_ordinary_python_is_accepted(name: str, source: str) -> None:
    violations = pyvalidate.validate(source)
    assert violations == [], f"{name!r} was wrongly rejected: {[v.message for v in violations]}"


def test_missing_entrypoint_is_reported() -> None:
    assert "run(input)" in messages("x = 1\n")


def test_nested_run_does_not_count_as_the_entrypoint() -> None:
    source = "def outer():\n    def run(input):\n        return 1\n"
    assert "run(input)" in messages(source)


def test_syntax_error_reports_its_line() -> None:
    violations = pyvalidate.validate("def run(input)\n    return 1\n")
    assert len(violations) == 1
    assert violations[0].line == 1
    assert "Syntax error" in violations[0].message


def test_rejection_message_explains_why() -> None:
    # A rejection the author cannot act on is a support ticket, so the message
    # has to say what is unavailable and what to do instead.
    text = messages("import os\ndef run(input): return 1\n")
    assert "os" in text and "not permitted" in text
    assert "Available modules" in text
