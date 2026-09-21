"""Download Termux proot .deb (aarch64) and extract the static binary."""
import re, tarfile, urllib.request, gzip, io, os

MIRRORS = [
    "http://packages.termux.org/apt/termux-main",
    "https://grimler.se/termux/termux-main",
    "https://termux.mentality.rip/termux-main",
]
UA = {"User-Agent": "Debian APT-HTTP/1.3 (arm64)"}

def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=120).read()

print("fetching Packages index...", flush=True)
raw = None
errs = []
for m in MIRRORS:
    try:
        raw = fetch(f"{m}/dists/stable/main/binary-aarch64/Packages")
        MIRROR = m
        print("mirror OK:", m, flush=True)
        break
    except Exception as e:
        errs.append(f"{m}: {e!r}")
        continue
assert raw is not None, "all mirrors failed: " + "; ".join(errs)
text = raw.decode("utf-8", "replace")
blocks = text.split("\n\n")
deb = None
for b in blocks:
    if re.search(r"^Package:\s*proot\s*$", b, re.M):
        m = re.search(r"^Filename:\s*(\S+)\s*$", b, re.M)
        if m:
            deb = m.group(1)
            break
assert deb, "proot entry not found in Packages index"
url = f"{MIRROR}/{deb}"
print("downloading", url, flush=True)
data = fetch(url)
open("/tmp/proot.deb", "wb").write(data)
# .deb = ar archive: debian-binary, control.tar.*, data.tar.*
import subprocess
out = subprocess.run(["ar", "t", "/tmp/proot.deb"], capture_output=True, text=True).stdout
member = [l for l in out.splitlines() if l.startswith("data.tar")][0]
subprocess.run(["ar", "p", "/tmp/proot.deb", member], stdout=open("/tmp/data.tar", "wb"), check=True)
tf = tarfile.open("/tmp/data.tar")
names = [n for n in tf.getnames() if n.endswith("/bin/proot") or n == "./data/data/com.termux/files/usr/bin/proot" or n.endswith("bin/proot")]
print("candidates:", names[:5])
src = [n for n in names if "proot" in n and "proot-distro" not in n and "termux-auth" not in n][0]
f = tf.extractfile(src)
open("/tmp/proot", "wb").write(f.read())
os.chmod("/tmp/proot", 0o755)
print("saved /tmp/proot", os.path.getsize("/tmp/proot"), "bytes")
