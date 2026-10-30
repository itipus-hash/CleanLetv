#!/usr/bin/env python3
"""Pull a file off the TV through the shell channel (this firmware's adbd sync protocol is
broken - it hangs or fails), by base64-encoding it on the device and decoding locally.
The shell channel truncates very large outputs, so the base64 text is fetched in chunks."""
import base64
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tv import connect  # noqa: E402

remote = sys.argv[1]
local = sys.argv[2]
chunk = int(sys.argv[3]) if len(sys.argv) > 3 else 262144  # bytes of base64 text per read

dev = connect()
# base64 first: one pass over the file, then read the text in chunks
dev.shell(f"busybox base64 {remote} > /data/local/tmp/_b64pull.tmp")
size = int(dev.shell("busybox wc -c < /data/local/tmp/_b64pull.tmp").strip())
parts = []
pos = 0
while pos < size:
    take = min(chunk, size - pos)
    out = dev.shell(
        f"dd if=/data/local/tmp/_b64pull.tmp bs=1 skip={pos} count={take} 2>/dev/null",
        read_timeout_s=300.0,
    )
    if len(out) < take:
        raise SystemExit(f"short read at {pos}: got {len(out)} of {take}")
    parts.append(out)
    pos += take
    print(f"  {pos}/{size}", flush=True)
dev.shell("rm -f /data/local/tmp/_b64pull.tmp")
dev.close()
data = base64.b64decode("".join("".join(parts).split()))
with open(local, "wb") as f:
    f.write(data)
print(f"pulled {remote} -> {local} ({len(data)} bytes)")
