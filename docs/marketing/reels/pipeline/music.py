"""Original, royalty-free background music synthesized from scratch (numpy/scipy).
Two tracks: 'drive' (upbeat tech, 122 BPM) and 'calm' (warm cinematic pad, 92 BPM).
Everything is generated here, nothing sampled, so the reels carry no licensing risk."""
import numpy as np, soundfile as sf, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import paths as P
from scipy.signal import butter, lfilter, fftconvolve
SR = 48000
rng = np.random.default_rng(7)

def note(n):  # midi -> Hz
    return 440.0 * 2 ** ((n - 69) / 12)

def lowpass(x, cutoff, order=2):
    b, a = butter(order, min(cutoff, SR/2 - 100) / (SR / 2))
    return lfilter(b, a, x)

def highpass(x, cutoff, order=2):
    b, a = butter(order, cutoff / (SR / 2), btype='high')
    return lfilter(b, a, x)

def env(n, a, d, s, r, dur):
    """ADSR in samples; dur = note length in samples (gate)."""
    a, d, r = int(a*SR), int(d*SR), int(r*SR)
    e = np.zeros(n)
    g = min(dur, n)
    att = np.linspace(0, 1, max(a,1)); e[:min(a,g)] = att[:min(a,g)]
    if g > a:
        dec = np.linspace(1, s, max(d,1)); seg = e[a:min(a+d,g)]; seg[:] = dec[:len(seg)]
    if g > a + d: e[a+d:g] = s
    rel_start = g
    if rel_start < n:
        start_val = e[rel_start-1] if rel_start > 0 else 0
        rel = np.linspace(start_val, 0, max(r,1)); seg = e[rel_start:min(rel_start+r, n)]; seg[:] = rel[:len(seg)]
    return e

def saw(f, n, detune=0.0):
    t = np.arange(n) / SR
    out = np.zeros(n)
    for d in (-detune, 0, detune) if detune else (0,):
        ph = (t * f * (1 + d)) % 1.0
        out += 2 * ph - 1
    return out / (3 if detune else 1)

def square(f, n, pw=0.5):
    t = np.arange(n) / SR
    return np.where((t * f) % 1.0 < pw, 1.0, -1.0)

def sine(f, n, phase=0):
    return np.sin(2 * np.pi * f * np.arange(n) / SR + phase)

def reverb(x, decay=1.8, mix=0.25, pre=0.02):
    n = int(decay * SR)
    ir = rng.standard_normal(n) * np.exp(-np.arange(n) / (decay * SR / 5))
    ir = lowpass(ir, 6000); ir /= np.abs(ir).sum() ** 0.5 * 40
    wet = fftconvolve(x, ir)[:len(x)]
    wet = np.concatenate([np.zeros(int(pre*SR)), wet])[:len(x)]
    return x * (1 - mix) + wet * mix

def kick(n):
    t = np.arange(n) / SR
    f = 48 + 120 * np.exp(-t * 28)
    ph = np.cumsum(2 * np.pi * f / SR)
    body = np.sin(ph) * np.exp(-t * 7)
    click = rng.standard_normal(n) * np.exp(-t * 400) * 0.6
    return np.tanh(1.8 * (body + click)) * 0.9

def hat(n, open_=False):
    t = np.arange(n) / SR
    x = highpass(rng.standard_normal(n), 7000, 4) * np.exp(-t * (6 if open_ else 55))
    return x * 0.35

def clap(n):
    t = np.arange(n) / SR
    x = np.zeros(n)
    for off in (0, 0.012, 0.024, 0.036):
        o = int(off * SR)
        burst = rng.standard_normal(n - o) * np.exp(-np.arange(n - o) / SR * (140 if off < 0.03 else 18))
        x[o:] += burst
    return highpass(lowpass(x, 5000), 900) * 0.5

def add(buf, start, x, gain=1.0):
    s = int(start); e = min(s + len(x), len(buf))
    if s < len(buf): buf[s:e] += x[:e - s] * gain

def drive_track(seconds):
    bpm = 122; beat = 60 / bpm; bar = 4 * beat
    n = int(seconds * SR); total = np.zeros(n)
    # I - V - vi - IV in A major (A E F#m D), bass roots and a bright arpeggio
    chords = [(57, 61, 64, 68), (52, 56, 59, 64), (54, 57, 61, 66), (50, 54, 57, 62)]
    bars = int(np.ceil(seconds / bar))
    pad = np.zeros(n); arp = np.zeros(n); bass = np.zeros(n); drums = np.zeros(n)
    for b in range(bars):
        ch = chords[b % 4]; t0 = b * bar * SR; L = int(bar * SR)
        # pad: detuned saws, slow attack, lowpass
        for m in ch:
            x = saw(note(m), L + int(0.6*SR), detune=0.006) * env(L + int(0.6*SR), 0.35, 0.3, 0.7, 0.5, L)
            add(pad, t0, lowpass(x, 1800), 0.09)
        # bass: root an octave down, 8th notes with pluck envelope
        root = note(ch[0] - 12)
        for k in range(8):
            Ln = int(beat / 2 * SR)
            x = (saw(root, Ln) * 0.5 + sine(root, Ln)) * env(Ln, 0.004, 0.12, 0.35, 0.05, int(Ln*0.8))
            add(bass, t0 + k * Ln, lowpass(x, 500 if k % 2 else 900), 0.5)
        # arpeggio: 16th-note pattern climbing through the chord, pluck synth
        pat = [0, 1, 2, 3, 2, 1, 3, 2, 0, 2, 1, 3, 2, 3, 1, 2]
        for k in range(16):
            Ln = int(beat / 4 * SR); m = ch[pat[k]] + 12
            x = square(note(m), Ln * 2, 0.3) * env(Ln * 2, 0.002, 0.18, 0.0, 0.05, Ln)
            add(arp, t0 + k * Ln, lowpass(x, 3500 + 1500 * np.sin(b + k / 4)), 0.16 if b >= 2 else 0.07)
        # drums
        for k in range(4):
            add(drums, t0 + k * beat * SR, kick(int(0.4 * SR)), 1.0)
        for k in range(8):
            add(drums, t0 + (k * beat / 2 + beat / 4) * SR, hat(int(0.15 * SR), open_=(k % 4 == 3)), 1.0)
        if b >= 1:
            for k in (1, 3):
                add(drums, t0 + k * beat * SR, clap(int(0.25 * SR)), 0.8)
    # sidechain pump on pad/bass/arp from the kick
    sc = np.ones(n)
    for b in range(bars):
        for k in range(4):
            s = int((b * bar + k * beat) * SR); L = int(0.22 * SR)
            if s < n:
                seg = sc[s:s + L]; seg[:] = np.minimum(seg, np.linspace(0.25, 1.0, L)[:len(seg)])
    mix = (reverb(pad, 2.2, 0.35) + bass + reverb(arp, 1.2, 0.22)) * sc + drums
    return mix

def calm_track(seconds):
    bpm = 92; beat = 60 / bpm; bar = 4 * beat
    n = int(seconds * SR)
    chords = [(48, 55, 59, 62), (45, 52, 57, 60), (41, 48, 53, 57), (43, 50, 55, 59)]  # Cmaj7 Am7 Fmaj7 G
    bars = int(np.ceil(seconds / bar))
    pad = np.zeros(n); keys = np.zeros(n); bass = np.zeros(n); perc = np.zeros(n)
    for b in range(bars):
        ch = chords[b % 4]; t0 = b * bar * SR; L = int(bar * SR)
        for m in ch:
            x = saw(note(m), L + SR, detune=0.004) * env(L + SR, 0.8, 0.5, 0.8, 0.9, L)
            add(pad, t0, lowpass(x, 1200), 0.07)
        root = note(ch[0] - 12)
        x = (sine(root, L) + 0.3 * sine(root * 2, L)) * env(L, 0.02, 0.4, 0.6, 0.3, int(L * 0.9))
        add(bass, t0, x, 0.35)
        # soft electric-piano style keys on beats 1 and 3.5
        for k, off in enumerate((0, 1.5, 2.5)):
            Ln = int(1.2 * SR); m = ch[(k + b) % 4] + 12
            x = (sine(note(m), Ln) + 0.4 * sine(note(m) * 2, Ln) * np.exp(-np.arange(Ln) / SR * 4)) * env(Ln, 0.005, 0.5, 0.3, 0.3, int(0.8 * SR))
            add(keys, t0 + off * beat * SR, x, 0.22)
        # light shaker
        for k in range(8):
            add(perc, t0 + k * beat / 2 * SR, hat(int(0.08 * SR)), 0.35 if k % 2 else 0.5)
        add(perc, t0, kick(int(0.3 * SR)), 0.45); add(perc, t0 + 2 * beat * SR, kick(int(0.3 * SR)), 0.4)
    return reverb(pad, 3.0, 0.45) + reverb(keys, 2.0, 0.35) + bass + perc * 0.6

def master(x, seconds, fade_in=0.05, fade_out=2.0, target_rms_db=-18.0):
    x = x[:int(seconds * SR)]
    x = highpass(x, 30)
    x = np.tanh(x / (np.abs(x).max() + 1e-9) * 1.4) * 0.9
    rms = np.sqrt(np.mean(x ** 2)); x *= 10 ** (target_rms_db / 20) / (rms + 1e-9)
    x = np.clip(x, -0.98, 0.98)
    fi, fo = int(fade_in * SR), int(fade_out * SR)
    x[:fi] *= np.linspace(0, 1, fi); x[-fo:] *= np.linspace(1, 0, fo)
    return x

if __name__ == "__main__":
    secs = float(sys.argv[1]) if len(sys.argv) > 1 else 60
    sf.write(f"{P.AUDIO}/music_drive.wav", master(drive_track(secs + 4), secs), SR)
    sf.write(f"{P.AUDIO}/music_calm.wav", master(calm_track(secs + 4), secs), SR)
    print("ok")
