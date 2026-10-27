"""
Puts the docling service root on sys.path so tests can `from app.x import y`
regardless of where pytest is invoked from (repo root or docling/).
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
