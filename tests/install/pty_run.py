#!/usr/bin/env python3
"""Run a command on a real pseudo-terminal, feed it answers, print what it showed.

usage: pty_run.py ANSWERS COMMAND [ARG...]
ANSWERS is the lines to type, separated by '|' ("" types nothing, "-" is an empty line).
Exit status is the command's.  Used by vegaduta-install.test.sh to exercise the first-run question,
which only appears when stdin and stdout are terminals.
"""
import os
import pty
import select
import sys
import time

answers = [] if sys.argv[1] == "" else ["" if a == "-" else a for a in sys.argv[1].split("|")]
cmd = sys.argv[2:]
pid, fd = pty.fork()
if pid == 0:
    os.execvp(cmd[0], cmd)
out = b""
typed = 0
deadline = time.time() + 60
last = time.time()
while time.time() < deadline:
    r, _, _ = select.select([fd], [], [], 0.2)
    if r:
        try:
            data = os.read(fd, 4096)
        except OSError:
            break
        if not data:
            break
        out += data
        last = time.time()
    elif typed < len(answers) and time.time() - last > 0.4:
        os.write(fd, (answers[typed] + "\n").encode())
        typed += 1
        last = time.time()
_, status = os.waitpid(pid, 0)
sys.stdout.write(out.decode(errors="replace").replace("\r", ""))
sys.exit(os.waitstatus_to_exitcode(status))
