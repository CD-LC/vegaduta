"""Assembles each reel from the recorded takes, cards, voice-over and music with ffmpeg.

A reel is a list of segments. Each segment has a video source and a duration; voice-over lines are
placed at segment starts; captions are burned in; music is ducked under the voice; an SRT is
written next to the MP4. Sources:
  {"take": "<dir with frames.txt>", "from": s, "to": s, "speed": x}   screen recording (CDP frames)
  {"video": "path.mp4", "from": s, "to": s, "speed": x}                an encoded clip (terminal)
  {"image": "path.png"}                                               a still (hook / end card)
"""
import json, os, subprocess, sys, shlex
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import cards; import paths as P

W, H, FPS = 1080, 1920, 30
OUT, TMP = P.OUT, P.TMP
VO_DUR = json.load(open(f'{P.AUDIO}/vo_durations.json'))

def run(cmd):
    r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True)
    if r.returncode: print(r.stderr[-3000:]); raise SystemExit('ffmpeg failed')

def take_to_mp4(take):
    """Encode a CDP frame take once (variable frame durations -> 30 fps)."""
    out = f'{TMP}/take_{os.path.basename(take.rstrip("/"))}.mp4'
    if os.path.exists(out): return out
    run(f'ffmpeg -y -loglevel error -f concat -safe 0 -i {take}/frames.txt -vf "scale={W}:{H}:force_original_aspect_ratio=decrease,pad={W}:{H}:(ow-iw)/2:(oh-ih)/2:color=#0b0b1a,fps={FPS},format=yuv420p" -c:v libx264 -preset fast -crf 17 {out}')
    return out

def events(take):
    try: return {e['name']: e['t'] / 1000 for e in json.load(open(f'{take}/events.json'))}
    except Exception: return {}

def segment_clip(seg, idx, dur):
    """Return an mp4 of exactly `dur` seconds for this segment."""
    out = f'{TMP}/seg_{idx:02d}.mp4'
    if 'image' in seg:
        zoom = seg.get('zoom', 1.04)
        # slow push-in so a still never looks frozen
        run(f'ffmpeg -y -loglevel error -framerate {FPS} -loop 1 -t {dur} -i {shlex.quote(P.resolve(seg["image"]))} -vf "scale={W*2}:{H*2},zoompan=z=\'1+({zoom}-1)*on/({dur*FPS})\':x=\'iw/2-(iw/zoom/2)\':y=\'ih/2-(ih/zoom/2)\':d=1:s={W}x{H}:fps={FPS},format=yuv420p" -r {FPS} -c:v libx264 -preset fast -crf 17 {out}')
        return out
    src = take_to_mp4(P.resolve(seg['take'])) if 'take' in seg else P.resolve(seg['video'])
    start = seg.get('from', 0); speed = seg.get('speed', 1.0)
    src_len = seg.get('to', start + dur * speed) - start
    need = dur * speed
    vf = f'setpts=PTS/{speed},fps={FPS}'
    if src_len + 0.05 < need:
        # hold the last frame if the take is shorter than the slot
        vf += f',tpad=stop_mode=clone:stop_duration={need - src_len + 1}'
    run(f'ffmpeg -y -loglevel error -ss {start} -i {src} -t {need + 2} -vf "{vf},trim=duration={dur},setpts=PTS-STARTPTS,format=yuv420p" -an -r {FPS} -c:v libx264 -preset fast -crf 17 {out}')
    return out

def build(reel, spec, music, music_gain=-16, vo_gain=1.0):
    segs = spec['segments']
    # 1. durations: a segment lasts max(its VO line, its minimum) unless fixed
    for s in segs:
        vo = s.get('vo'); vd = VO_DUR.get(f'{reel}_{vo}', 0) if vo else 0
        s['dur'] = s.get('dur') or max(vd + s.get('tail', 0.5), s.get('min', 2.0))
    total = sum(s['dur'] for s in segs)
    # 2. video segments + concat
    clips = [segment_clip(s, i, s['dur']) for i, s in enumerate(segs)]
    lst = f'{TMP}/{reel}_concat.txt'
    open(lst, 'w').write(''.join(f"file '{c}'\n" for c in clips))
    base = f'{TMP}/{reel}_video.mp4'
    run(f'ffmpeg -y -loglevel error -f concat -safe 0 -i {lst} -c copy {base}')
    # 3. captions (PNG plates) with fade, placed in the safe zone
    t = 0; overlays = []; inputs = [f'-i {base}']; n = 1; srt = []; k = 1
    for s in segs:
        if s.get('caption'):
            plate = f'{TMP}/{reel}_cap_{n}.png'
            h = cards.caption_png(s['caption'], plate, size=s.get('cap_size', 60), accent=s.get('accent', False))
            y = s.get('cap_y', H - 320 - h)
            inputs.append(f'-i {plate}')
            a, b = t + 0.15, t + s['dur'] - 0.1
            overlays.append((n, y, a, b)); n += 1
        if s.get('vo'):
            vd = VO_DUR[f"{reel}_{s['vo']}"]
            srt.append((k, t + 0.05, t + vd, s.get('srt') or s['caption'] or '')); k += 1
        t += s['dur']
    fc = []; last = '[0:v]'
    for i, (idx, y, a, b) in enumerate(overlays):
        fc.append(f"{last}[{idx}:v]overlay=x=(W-w)/2:y={y}:enable='between(t,{a:.2f},{b:.2f})'[v{i}]"); last = f'[v{i}]'
    fc.append(f"{last}format=yuv420p[vout]")
    vid = f'{TMP}/{reel}_captioned.mp4'
    run(f'ffmpeg -y -loglevel error {" ".join(inputs)} -filter_complex "{";".join(fc)}" -map "[vout]" -r {FPS} -c:v libx264 -preset medium -crf 19 {vid}')
    # 4. audio: VO lines at their start times, music ducked by the voice
    t = 0; vo_in = []; delays = []
    for s in segs:
        if s.get('vo'):
            vo_in.append(f'{P.AUDIO}/vo_{reel}_{s["vo"]}.wav'); delays.append(int(t * 1000))
        t += s['dur']
    ins = ' '.join(f'-i {p}' for p in vo_in)
    parts = ''.join(f'[{i+2}:a]adelay={d}|{d},volume={vo_gain}[s{i}];' for i, d in enumerate(delays))
    mix = ''.join(f'[s{i}]' for i in range(len(delays)))
    af = (f'{parts}{mix}amix=inputs={len(delays)}:normalize=0:dropout_transition=0,aresample=48000,asplit=2[vo][sc];'
          f'[1:a]atrim=0:{total},asetpts=PTS-STARTPTS,volume={music_gain}dB,afade=t=out:st={total-2.5}:d=2.5[m];'
          f'[m][sc]sidechaincompress=threshold=0.015:ratio=8:attack=40:release=600:makeup=1[md];'
          f'[md][vo]amix=inputs=2:normalize=0,loudnorm=I=-16:TP=-1.5:LRA=11,alimiter=limit=0.95[aout]')
    final = f'{OUT}/{reel}.mp4'
    run(f'ffmpeg -y -loglevel error -i {vid} -i {music} {ins} -filter_complex "{af}" -map 0:v -map "[aout]" -t {total} -c:v copy -c:a aac -b:a 192k -movflags +faststart {final}')
    # 5. SRT
    def ts(x): h = int(x // 3600); m = int(x % 3600 // 60); s_ = x % 60; return f'{h:02d}:{m:02d}:{s_:06.3f}'.replace('.', ',')
    open(f'{OUT}/{reel}.srt', 'w').write(''.join(f'{i}\n{ts(a)} --> {ts(b)}\n{txt}\n\n' for i, a, b, txt in srt))
    print(reel, 'done', round(total, 1), 's ->', final)
    return final, total

def landscape(src, out):
    """16:9 version: the 9:16 reel centred over a blurred, darkened copy of itself."""
    run(f'ffmpeg -y -loglevel error -i {src} -filter_complex "[0:v]scale=1920:1080,boxblur=30:5,eq=brightness=-0.25[bg];[0:v]scale=-2:1080[fg];[bg][fg]overlay=(W-w)/2:0,format=yuv420p[v]" -map "[v]" -map 0:a -c:v libx264 -preset medium -crf 19 -c:a copy -movflags +faststart {out}')

if __name__ == '__main__':
    spec = json.load(open(sys.argv[1]))
    for reel, cfg in spec.items():
        f, total = build(reel, cfg, P.resolve(cfg.get('music', 'audio/music_drive.wav')), cfg.get('music_gain', -16))
        if cfg.get('landscape'): landscape(f, f.replace('.mp4', '_16x9.mp4'))
