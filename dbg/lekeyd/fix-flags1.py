#!/usr/bin/env python3
"""Clear the DF_1_PIE bit from an ELF64 executable's DT_FLAGS_1 entry.

NDK r26+ (lld) marks PIE executables with DT_FLAGS_1 = DF_1_NOW | DF_1_PIE (0x8000001).
Android 6's linker (bionic before ~Android 8) rejects any DT_FLAGS_1 bit it does not know:

    WARNING: linker: /system/bin/lekeyd: unsupported flags DT_FLAGS_1=0x8000001

and refuses to load the program, so the daemon never starts. DF_1_PIE is purely
informational (ET_DYN + PT_INTERP is what makes it a PIE), so clearing the bit is safe and
leaves a binary the old linker accepts.

Usage: fix-flags1.py <elf file>
"""
import struct
import sys

DT_FLAGS_1 = 0x6FFFFFFB
DF_1_PIE = 0x08000000
PT_DYNAMIC = 2


def main(path):
    with open(path, "r+b") as f:
        data = bytearray(f.read())
        if data[:4] != b"\x7fELF" or data[4] != 2:
            sys.exit("not an ELF64 file: " + path)
        e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
        e_phnum = struct.unpack_from("<H", data, 0x38)[0]

        dyn_off = dyn_size = None
        for i in range(e_phnum):
            base = e_phoff + i * e_phentsize
            p_type = struct.unpack_from("<I", data, base)[0]
            if p_type == PT_DYNAMIC:
                dyn_off = struct.unpack_from("<Q", data, base + 0x08)[0]
                dyn_size = struct.unpack_from("<Q", data, base + 0x20)[0]
        if dyn_off is None:
            sys.exit("no PT_DYNAMIC segment: " + path)

        for off in range(dyn_off, dyn_off + dyn_size, 16):
            tag, val = struct.unpack_from("<qQ", data, off)
            if tag == DT_FLAGS_1:
                if val & DF_1_PIE:
                    struct.pack_into("<Q", data, off + 8, val & ~DF_1_PIE)
                    print("DT_FLAGS_1: 0x%x -> 0x%x" % (val, val & ~DF_1_PIE))
                else:
                    print("DT_FLAGS_1 already clean: 0x%x" % val)
                break
        else:
            sys.exit("no DT_FLAGS_1 entry: " + path)

        f.seek(0)
        f.write(data)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
