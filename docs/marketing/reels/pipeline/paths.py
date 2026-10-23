"""Where the kit reads and writes. Everything generated goes under one work folder (default
docs/marketing/reels/out, git-ignored); override with VD_REELS_WORK. Nothing here is machine-specific."""
import os
REELS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))     # docs/marketing/reels
REPO = os.path.abspath(os.path.join(REELS, '..', '..', '..'))
WORK = os.environ.get('VD_REELS_WORK', os.path.join(REELS, 'out'))
AUDIO, CARDS, TMP, OUT = (os.path.join(WORK, d) for d in ('audio', 'cards', 'tmp', '.'))
REC, SHOTS, TERM = (os.path.join(WORK, d) for d in ('rec', 'shots', 'term'))
TTS = os.environ.get('VD_TTS_DIR', os.path.expanduser('~/tts'))         # kokoro-v1.0.onnx + voices-v1.0.bin
PERSONAL_HOME = os.environ.get('VEGADUTA_PERSONAL_HOME', os.path.expanduser('~/.vegaduta/personal'))
LOGO = os.path.join(REPO, 'web', 'public', 'logo-mark.png')
for d in (AUDIO, CARDS, TMP, REC, SHOTS, TERM): os.makedirs(d, exist_ok=True)
def resolve(p):
    """A path in a spec: absolute as is, otherwise relative to the work folder."""
    return p if os.path.isabs(p) else os.path.join(WORK, p)
