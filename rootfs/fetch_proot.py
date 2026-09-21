"""Download Termux proot .deb (aarch64) and extract the static binary."""
import re, tarfile, urllib.request, gzip, io, os

MIRROR = "http://packages.termux.org/apt/termux-main"
PKGS = f"{MIRROR}/dists/stable/main/binary-aarch64/Packages"

print("fetching Packages index...", flush=True)
raw = urllib.request.urlopen(PKGS, timeout=120).read()
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
data = urllib.request.urlopen(url, timeout=300).read()
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
