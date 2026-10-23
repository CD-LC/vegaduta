"""Renders the non-screen footage of the reels with Pillow: hook cards, end cards, the terminal
animation (re-rendered from the real core boot log of this install) and covers/thumbnails."""
import os, re, math, json, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import paths as P
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1080, 1920
BG1, BG2 = (11, 11, 26), (29, 22, 70)
PURPLE, ORANGE, WHITE, MUTED = (99, 91, 255), (249, 115, 22), (255, 255, 255), (170, 170, 200)
FONTS = {}
def font(name, size):
    key = (name, size)
    if key not in FONTS:
        paths = {
            'black': '/usr/share/fonts/truetype/inter/InterDisplay-Black.ttf',
            'bold': '/usr/share/fonts/truetype/inter/InterDisplay-Bold.ttf',
            'semibold': '/usr/share/fonts/truetype/inter/Inter-SemiBold.ttf',
            'regular': '/usr/share/fonts/truetype/inter/Inter-Regular.ttf',
            'mono': '/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf',
            'monobold': '/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf',
        }
        p = paths[name]
        if not os.path.exists(p):  # macOS: Homebrew casks font-inter / font-dejavu
            fn = os.path.basename(p)
            for d in (os.path.expanduser('~/Library/Fonts'), '/Library/Fonts'):
                if os.path.exists(os.path.join(d, fn)): p = os.path.join(d, fn); break
        if not os.path.exists(p):
            import subprocess
            fam = {'black': 'Inter Display:style=Black', 'bold': 'Inter Display:style=Bold', 'semibold': 'Inter:style=SemiBold', 'regular': 'Inter:style=Regular', 'mono': 'DejaVu Sans Mono', 'monobold': 'DejaVu Sans Mono:style=Bold'}[name]
            p = subprocess.run(['fc-match', '-f', '%{file}', fam], capture_output=True, text=True).stdout.strip()
        FONTS[key] = ImageFont.truetype(p, size)
    return FONTS[key]

def background(w=W, h=H, dots=True):
    img = Image.new('RGB', (w, h), BG1)
    px = img.load()
    for y in range(h):
        t = y / h
        c = tuple(int(BG1[i] * (1 - t) + BG2[i] * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    d = ImageDraw.Draw(img)
    if dots:
        for y in range(40, h, 48):
            for x in range(40, w, 48):
                d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(255, 255, 255, 18) if False else (40, 38, 80))
    # soft glow
    glow = Image.new('RGB', (w, h), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([w * 0.1, h * 0.55, w * 0.9, h * 1.05], fill=(60, 45, 160))
    glow = glow.filter(ImageFilter.GaussianBlur(220))
    img = Image.blend(img, Image.composite(glow, img, glow.convert('L').point(lambda v: min(255, v * 2))), 0.55)
    return img

def logo():
    p = P.LOGO
    return Image.open(p).convert('RGBA')

def wrap(d, text, f, maxw):
    words, lines, cur = text.split(), [], ''
    for w_ in words:
        t = (cur + ' ' + w_).strip()
        if d.textlength(t, font=f) <= maxw: cur = t
        else: lines.append(cur); cur = w_
    if cur: lines.append(cur)
    return lines

def brand_row(img, y=120, size=64, with_word=True):
    d = ImageDraw.Draw(img)
    lg = logo().resize((size, size), Image.LANCZOS)
    x = 96
    img.paste(lg, (x, y), lg)
    if with_word:
        f = font('bold', int(size * 0.66))
        d.text((x + size + 22, y + size * 0.17), 'Vega', font=f, fill=WHITE)
        d.text((x + size + 22 + d.textlength('Vega', font=f), y + size * 0.17), 'Dūta', font=f, fill=ORANGE)
    return img

def hook_card(text, sub=None, out=None, size=112):
    img = background(); d = ImageDraw.Draw(img)
    brand_row(img)
    f = font('black', size)
    lines = wrap(d, text, f, W - 192)
    lh = size * 1.12
    total = lh * len(lines)
    y = H * 0.42 - total / 2
    for ln in lines:
        d.text((96, y), ln, font=f, fill=WHITE); y += lh
    d.rounded_rectangle([96, y + 26, 96 + 220, y + 40], radius=7, fill=ORANGE)
    if sub:
        fs = font('semibold', 44)
        for ln in wrap(d, sub, fs, W - 192):
            y += 60; d.text((96, y + 30), ln, font=fs, fill=MUTED)
    if out: img.save(out, quality=95)
    return img

def end_card(out=None, line1='Build the agent.', line2='Then give the idea wings.', cmd='./scripts/vegaduta personal --build', url='github.com/CD-LC/vegaduta'):
    img = background(); d = ImageDraw.Draw(img)
    lg = logo().resize((240, 240), Image.LANCZOS)
    img.paste(lg, ((W - 240) // 2, 420), lg)
    f = font('bold', 92)
    d.text(((W - d.textlength('Vega', font=f) - d.textlength('Dūta', font=f)) / 2, 700), 'Vega', font=f, fill=WHITE)
    d.text(((W - d.textlength('Vega', font=f) - d.textlength('Dūta', font=f)) / 2 + d.textlength('Vega', font=f), 700), 'Dūta', font=f, fill=ORANGE)
    f2 = font('black', 64)
    for i, t in enumerate([line1, line2]):
        d.text(((W - d.textlength(t, font=f2)) / 2, 860 + i * 84), t, font=f2, fill=WHITE)
    fm = font('monobold', 36)
    tw = d.textlength(cmd, font=fm)
    d.rounded_rectangle([(W - tw) / 2 - 36, 1110, (W + tw) / 2 + 36, 1190], radius=18, fill=(20, 18, 48), outline=PURPLE, width=3)
    d.text(((W - tw) / 2, 1130), cmd, font=fm, fill=(220, 220, 255))
    fu = font('semibold', 44)
    d.text(((W - d.textlength(url, font=fu)) / 2, 1240), url, font=fu, fill=MUTED)
    fs = font('regular', 32)
    s = 'Open source · Self-hosted · MCP · A2A · 14 LLM providers'
    d.text(((W - d.textlength(s, font=fs)) / 2, 1330), s, font=fs, fill=(120, 120, 160))
    if out: img.save(out, quality=95)
    return img

def caption_png(text, out, width=W, size=58, maxw=W - 220, color=WHITE, accent=False):
    """A caption plate with transparent background (overlaid on footage by ffmpeg)."""
    f = font('black', size)
    tmp = Image.new('RGBA', (10, 10)); d = ImageDraw.Draw(tmp)
    lines = wrap(d, text, f, maxw)
    lh = int(size * 1.22); pad = 28
    h = lh * len(lines) + pad * 2
    img = Image.new('RGBA', (width, h), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    for i, ln in enumerate(lines):
        tw = d.textlength(ln, font=f)
        x = (width - tw) / 2; y = pad + i * lh
        d.rounded_rectangle([x - 26, y - 6, x + tw + 26, y + lh - 2], radius=22, fill=(12, 12, 28, 215) if not accent else (99, 91, 255, 235))
        d.text((x, y + 2), ln, font=f, fill=color)
    img.save(out)
    return h

def terminal_frames(outdir, fps=30, seconds=15.0, cmd='./scripts/vegaduta personal --build --native-core', log_path=f'{P.PERSONAL_HOME}/core.log'):
    """The real boot log of this install, replayed as a typed terminal session."""
    os.makedirs(outdir, exist_ok=True)
    raw = open(log_path, errors='ignore').read().splitlines()
    keep = []
    for ln in raw:
        m = re.match(r'^\S+T(\d\d:\d\d:\d\d)\.\d+Z\s+(\w+)\s+\[[^\]]*\]\s+\[[^\]]*\]\s+(\S+)\s+-\s+(.*)$', ln)
        if not m: continue
        t, lvl, cls, msg = m.groups()
        cls = cls.split('.')[-1]
        if any(k in msg for k in ('Starting CoreApplication', 'profiles are active', 'Flyway', 'Migrating schema "public"', 'Successfully applied', 'Started CoreApplication', 'first-run', 'Tomcat started', 'pgvector', 'Embeddings:')) or 'Migrating schema' in msg and len(keep) < 40:
            keep.append((t, lvl, cls, msg))
    # thin the migration lines so the log scrolls but does not drown the frame
    mig = [k for k in keep if 'Migrating schema' in k[3]]
    other = [k for k in keep if 'Migrating schema' not in k[3]]
    def pick(sub): return [k for k in other if sub in k[3]]
    keep = pick('Starting CoreApplication') + pick('profiles are active') + mig + pick('Successfully applied') + pick('pgvector') + pick('Tomcat started') + pick('Started CoreApplication')
    link = 'http://localhost:3000/login?setup=vdls_••••••••••••••••••••••••'
    keep.append(('', 'OK', 'vegaduta', 'VegaDūta (personal) is up. Set the owner password here:'))
    keep.append(('', 'OK', 'vegaduta', link[:56] + '…'))
    f_mono = font('mono', 25); f_b = font('monobold', 25); f_t = font('semibold', 26)
    n = int(fps * seconds); type_frames = int(fps * 2.2)
    line_h = 40; top = 300; left = 60
    rows = []
    for (t, lvl, cls, msg) in keep:
        if cls == 'vegaduta': rows.append(('==> ', (120, 240, 160), msg, (255, 189, 46) if 'http' in msg else WHITE, True))
        else:
            short = f'{cls}: {msg}'
            short = short if len(short) <= 60 else short[:59] + '…'
            hi = 'Successfully applied' in msg or 'Started CoreApplication' in msg or 'Tomcat started' in msg
            rows.append((f'{lvl:<5}', (120, 240, 160) if lvl == 'INFO' else (255, 189, 46), short, (120, 240, 160) if hi else (200, 200, 230), hi))
    for i in range(n):
        img = background(dots=False); d = ImageDraw.Draw(img)
        d.rounded_rectangle([30, 200, W - 30, H - 260], radius=28, fill=(16, 16, 30), outline=(60, 58, 110), width=2)
        d.rounded_rectangle([30, 200, W - 30, 270], radius=28, fill=(28, 26, 56))
        d.rectangle([30, 245, W - 30, 270], fill=(28, 26, 56))
        for j, c in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
            d.ellipse([66 + j * 38, 222, 90 + j * 38, 246], fill=c)
        d.text((W / 2 - 120, 220), 'vegaduta — zsh', font=f_t, fill=(190, 190, 220))
        y = top + 40
        typed = cmd[:int(len(cmd) * min(1, i / type_frames))]
        d.text((left, y), '$ ', font=f_b, fill=(120, 240, 160)); d.text((left + 32, y), typed, font=f_b, fill=WHITE)
        if i < type_frames and (i // 8) % 2 == 0: d.rectangle([left + 32 + d.textlength(typed, font=f_b) + 2, y + 2, left + 32 + d.textlength(typed, font=f_b) + 14, y + 30], fill=WHITE)
        y += line_h * 1.6
        if i > type_frames:
            prog = (i - type_frames) / (n - type_frames - fps * 3.0)
            shown = rows[:max(0, min(len(rows), int(len(rows) * prog) + 1))]
            for (p, pc, m, mc, hi) in shown:
                d.text((left, y), p, font=f_mono, fill=pc)
                d.text((left + d.textlength(p, font=f_mono) + 8, y), m, font=f_b if hi else f_mono, fill=mc)
                y += line_h
        img.save(f'{outdir}/t{i:05d}.jpg', quality=90)
    return n

if __name__ == '__main__':
    out = P.CARDS
    hook_card('Your data. Someone else’s cloud?', 'Self-host the agent platform instead.', f'{out}/r1_hook.png')
    end_card(f'{out}/end.png')
    print('ok')

# ---------------------------------------------------------------- covers, grid crops, thumbnail
HOOKS = {
    'r1': ('Your data. Someone else’s cloud?', 'Self-host the agent platform instead.'),
    'r2': ('Same 5 questions. All day.', 'Build a support agent from your own documents in 60 seconds.'),
    'r3': ('Can your agent go rogue?', 'Put a human approval inside the workflow.'),
    'r4': ('They won’t install your app.', 'WhatsApp, Telegram, Slack, SMS, email: one agent.'),
    'r5': ('“What does it cost?”', 'Cost, value and ROI per agent. Shown with Example data.'),
    'overview': ('Build the agent. Then give the idea wings.', 'Self-hosted AI agents in 75 seconds.'),
}

def _screen_bg(w, h, shot=None):
    """A real console screenshot, blurred and darkened, under the cover text; the brand gradient without one."""
    img = background(w, h)
    if shot and os.path.exists(shot):
        s = Image.open(shot).convert('RGB')
        r = max(w / s.width, h / s.height)
        s = s.resize((int(s.width * r) + 1, int(s.height * r) + 1), Image.LANCZOS).crop((0, 0, w, h))
        s = s.filter(ImageFilter.GaussianBlur(14))
        img = Image.blend(s, img, 0.62)
    return img

def cover(reel, out, shot=None, w=W, h=H, size=None):
    """1080x1920 cover (also used, cropped, for the grid): brand, hook, sub-line, URL; text in the safe zone."""
    size = size or (104 if w == W else 88)
    img = _screen_bg(w, h, shot); d = ImageDraw.Draw(img)
    brand_row(img, y=int(h * 0.0625), size=int(w * 0.06))
    text, sub = HOOKS[reel]
    f = font('black', size); lines = wrap(d, text, f, w - 192); lh = size * 1.12
    y = h * 0.46 - lh * len(lines) / 2
    for ln in lines:
        d.text((96, y), ln, font=f, fill=WHITE); y += lh
    d.rounded_rectangle([96, y + 26, 96 + 220, y + 40], radius=7, fill=ORANGE)
    fs = font('semibold', int(size * 0.4)); y += 60
    for ln in wrap(d, sub, fs, w - 192):
        d.text((96, y + 30), ln, font=fs, fill=MUTED); y += int(size * 0.52)
    fu = font('semibold', int(size * 0.36))
    d.text((96, h - int(h * 0.19)), 'github.com/CD-LC/vegaduta', font=fu, fill=(200, 200, 230))
    img.save(out, quality=95); return img

def grid_cover(reel, out, shot=None):
    """1080x1080 crop for the Instagram profile grid."""
    cover(reel, out, shot, w=1080, h=1080, size=96)

def thumbnail(reel, out, shot=None, title='Self-hosted AI agents'):
    """1280x720 YouTube thumbnail: big three-word title, logo, a real screenshot on the right."""
    w, h = 1280, 720
    img = background(w, h); d = ImageDraw.Draw(img)
    if shot and os.path.exists(shot):
        s = Image.open(shot).convert('RGB'); r = (h - 80) / s.height
        s = s.resize((int(s.width * r), h - 80), Image.LANCZOS)
        frame = Image.new('RGB', (s.width + 12, s.height + 12), (99, 91, 255)); frame.paste(s, (6, 6))
        img.paste(frame, (w - frame.width - 36, 40))
    brand_row(img, y=44, size=56)
    f = font('black', 76); y = 190
    for ln in wrap(d, title, f, 700):
        d.text((56, y), ln, font=f, fill=WHITE); y += 86
    d.rounded_rectangle([56, y + 18, 56 + 180, y + 30], radius=6, fill=ORANGE)
    fs = font('semibold', 34)
    d.text((56, y + 60), 'Open source · one command', font=fs, fill=MUTED)
    img.save(out, quality=95); return img

def all_covers(shots=None):
    """Every cover, grid crop and the thumbnail; `shots` maps reel -> a real screenshot path."""
    shots = shots or {}
    for reel in HOOKS:
        if reel != 'overview': hook_card(*HOOKS[reel], f'{P.CARDS}/{reel}_hook.png')
        cover(reel, f'{P.CARDS}/cover_{reel}.png', shots.get(reel))
        grid_cover(reel, f'{P.CARDS}/grid_{reel}.png', shots.get(reel))
    thumbnail('overview', f'{P.CARDS}/thumb_overview.png', shots.get('overview'))
