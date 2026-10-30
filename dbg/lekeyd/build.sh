#!/bin/sh
# Builds /system/bin/lekeyd for the LeEco X4 (arm64, Android 6 / API 23).
#
# The TV runs Android 6, whose linker rejects the DF_1_PIE bit that current lld puts in
# DT_FLAGS_1 ("unsupported flags DT_FLAGS_1=0x8000001" - the program then never starts), so
# fix-flags1.py clears that bit after linking. Both steps are required.
#
# Paths are for this machine; adjust NDK / PY if the toolchain moves.
set -e
cd "$(dirname "$0")"

NDK="${NDK:-C:/Users/FISH/AppData/Local/Android/Sdk/ndk/28.2.13676358}"
PY="${PY:-C:/Users/FISH/WorkBuddy/ClearTV/cleanyourtv/tools/venv/Scripts/python.exe}"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android23-clang"

"$CC" -O2 -Wall -o lekeyd lekeyd.c
"$PY" fix-flags1.py lekeyd
ls -l lekeyd
