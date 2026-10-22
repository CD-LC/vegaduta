"""Replace an installed certifi with a hash-pinned wheel, in place.

Used by deploy/postgres/Dockerfile on the CloudNativePG base, whose barman-cloud is
pip-installed and pip then removed, so `pip install --upgrade` is not available.
Arguments: <version> <sha256 of certifi-<version>-py3-none-any.whl>. No-op when the
installed certifi already is that version.
"""

import glob
import hashlib
import os
import shutil
import sys
import urllib.request
import zipfile

import certifi


def main(want: str, sha: str) -> int:
    have = certifi.__version__
    if have == want:
        return 0
    site = os.path.dirname(os.path.dirname(certifi.__file__))
    url = f"https://files.pythonhosted.org/packages/py3/c/certifi/certifi-{want}-py3-none-any.whl"
    data = urllib.request.urlopen(url, timeout=60).read()
    if hashlib.sha256(data).hexdigest() != sha:
        print(f"certifi {want}: sha256 mismatch", file=sys.stderr)
        return 1
    shutil.rmtree(os.path.join(site, "certifi"))
    for info in glob.glob(os.path.join(site, "certifi-*.dist-info")):
        shutil.rmtree(info)
    wheel = os.path.join("/tmp", f"certifi-{want}-py3-none-any.whl")
    with open(wheel, "wb") as f:
        f.write(data)
    with zipfile.ZipFile(wheel) as z:
        z.extractall(site)
    os.remove(wheel)
    print(f"certifi {have} -> {want} in {site}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
