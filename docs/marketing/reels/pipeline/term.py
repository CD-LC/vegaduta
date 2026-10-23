"""Turns the asciinema recording of the real install (out/term/install.cast) into a 1080x1920 clip.

agg renders the cast to a GIF (true terminal rendering, every byte the launcher and Docker printed);
ffmpeg scales it onto the reel canvas. Usage: term.py [cast] [out.mp4] [--speed X] [--idle S] [--font-size N] [--start S] [--end S]
"""
import os, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import paths as P

def trim(cast, out, start=0.0, end=None):
    """A copy of an asciicast v3 file holding only the events between `start` and `end` seconds
    (v3 stores the interval since the previous event, so the first kept event starts at 0)."""
    import json
    lines = open(cast).read().splitlines(); hdr = lines[0]; t = 0.0; kept = []
    for ln in lines[1:]:
        try: d, kind, data = json.loads(ln)
        except Exception: continue
        t += d
        if t < start or (end is not None and t > end): continue
        kept.append(json.dumps([round(d if kept else 0.0, 4), kind, data]))
    open(out, 'w').write(hdr + '\n' + '\n'.join(kept) + '\n'); return out

def render(cast=None, out=None, speed=1.0, idle=1.5, font_size=22, theme='custom', fps=30, start=0.0, end=None):
    cast = cast or f'{P.TERM}/install.cast'; out = out or f'{P.TERM}/install.mp4'
    if start or end is not None: cast = trim(cast, out.replace('.mp4', '.cast'), start, end)
    gif = out.replace('.mp4', '.gif')
    # VegaDuta palette: background 0b0b1a, white text, ANSI colours in the console's own hues
    custom = '0b0b1a,e6e6f0,1d1d35,ff5f56,27c93f,ffbd2e,635bff,c678dd,56b6c2,d0d0e0,6a6a8a,ff7b72,56d364,ffd866,8b85ff,e2a8ff,7fdbe6,ffffff'
    subprocess.run(['agg', cast, gif, '--font-size', str(font_size), '--line-height', '1.35', '--speed', str(speed),
                    '--idle-time-limit', str(idle), '--fps-cap', str(fps), '--no-loop',
                    '--theme', custom if theme == 'custom' else theme,
                    '--font-family', 'DejaVu Sans Mono,Menlo,SF Mono'], check=True)
    vf = (f'fps={fps},scale={P_W}:-2:flags=lanczos:force_original_aspect_ratio=decrease,'
          f'pad={P_W}:{P_H}:(ow-iw)/2:(oh-ih)/2:color=#0b0b1a,format=yuv420p')
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', gif, '-vf', vf, '-r', str(fps),
                    '-c:v', 'libx264', '-preset', 'fast', '-crf', '17', out], check=True)
    d = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', out], capture_output=True, text=True).stdout.strip()
    print(out, d, 's'); return out

P_W, P_H = 1080, 1920
if __name__ == '__main__':
    a = sys.argv[1:]; kw = {}
    for k in ('--speed', '--idle', '--font-size', '--start', '--end'):
        if k in a: i = a.index(k); kw[k[2:].replace('-', '_')] = float(a[i + 1]) if k != '--font-size' else int(a[i + 1]); del a[i:i + 2]
    render(*(a[:2]), **kw)
