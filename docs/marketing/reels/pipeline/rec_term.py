"""Records the install terminal for reel 1: starts `asciinema rec` on a pseudo-terminal, types the
command into the real shell key by key (the same way the browser scenes type), then waits for the
launcher to finish and leaves the shell. Nothing is simulated: the shell, the launcher, Docker and
the model check all run for real; only the keystrokes come from this script.

Usage: rec_term.py "<command>" out.cast [--cols 72] [--rows 40] [--timeout 3600]
"""
import os, pty, sys, time, random, select, fcntl, termios, struct, re

def main():
    a = sys.argv[1:]; cols, rows, timeout = 72, 40, 3600
    for k, cast in (('--cols', int), ('--rows', int), ('--timeout', int)):
        if k in a: i = a.index(k); v = cast(a[i + 1]); del a[i:i + 2]; cols, rows, timeout = (v if k == '--cols' else cols, v if k == '--rows' else rows, v if k == '--timeout' else timeout)
    cmd, out = a[0], a[1]
    os.environ.setdefault('TERM', 'xterm-256color')
    pid, fd = pty.fork()
    if pid == 0:
        os.execvp('asciinema', ['asciinema', 'rec', '--overwrite', '--window-size', f'{cols}x{rows}', '-I', '--idle-time-limit', '2', out])
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))
    log = open(out + '.log', 'wb'); buf = b''
    def wait_for(pattern, limit):
        nonlocal buf
        t0 = time.time(); rx = re.compile(pattern)
        while time.time() - t0 < limit:
            r, _, _ = select.select([fd], [], [], 1.0)
            if fd in r:
                try: chunk = os.read(fd, 65536)
                except OSError: return False
                if not chunk: return False
                buf += chunk; log.write(chunk); log.flush()
                if rx.search(buf[-20000:]): return True
        return False
    def drain(seconds):
        t0 = time.time()
        while time.time() - t0 < seconds:
            r, _, _ = select.select([fd], [], [], 0.2)
            if fd in r:
                try: chunk = os.read(fd, 65536)
                except OSError: return
                if not chunk: return
                log.write(chunk); log.flush()
    if not wait_for(rb'[%$] ?(\x1b\[[0-9;?]*[A-Za-z])*\s*$', 30): print('no prompt', file=sys.stderr)
    time.sleep(1.2)
    for ch in cmd:
        os.write(fd, ch.encode()); time.sleep(0.07 + random.random() * 0.09 + (0.12 if ch == ' ' else 0))
    time.sleep(0.8); os.write(fd, b'\r')
    buf = b''
    ok = wait_for(rb'status: scripts/vegaduta status|\[FAIL\]|some services did not become healthy|fix the problems above', timeout)
    print('launcher finished' if ok else 'timeout', file=sys.stderr)
    drain(3.0); time.sleep(2.5)
    os.write(fd, b'exit\r'); drain(3.0)
    try: os.waitpid(pid, 0)
    except ChildProcessError: pass
    print('recorded', out)

if __name__ == '__main__': main()
