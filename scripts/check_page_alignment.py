#!/usr/bin/env python3
"""Fail when a 64-bit native library in an APK is not 16 KB-page aligned.

Android 15+ runs 64-bit devices with 16 KB memory pages. A shared object whose
first PT_LOAD segment is aligned to the old 4 KB makes the system show a
`PageSizeMismatchDialog` over the app, and Play rejects such uploads at recent
target levels (issue #14).

This is a CI gate rather than a one-time fix: the offending libraries came from a
prebuilt dependency, so the next dependency bump can silently reintroduce them.
It parses the ELF program headers directly instead of shelling out to `readelf`,
so it needs nothing beyond a Python interpreter on the runner.

Only 64-bit ABIs are checked. Page size does not apply to `armeabi-v7a` or `x86`,
and those keep whatever alignment their toolchain produced.

Usage:
    check_page_alignment.py <apk-or-aar> [more…]
"""

from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import Path

# ABIs to which the 16 KB requirement applies.
SIXTY_FOUR_BIT = ("arm64-v8a", "x86_64")
REQUIRED_ALIGNMENT = 16 * 1024

PT_LOAD = 1


def first_load_alignment(data: bytes) -> int | None:
    """Return the p_align of the first PT_LOAD segment of a 64-bit little-endian ELF."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    if data[4] != 2:  # EI_CLASS: 2 = ELFCLASS64
        return None
    if data[5] != 1:  # EI_DATA: 1 = little-endian, which every Android ABI is
        return None

    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)

    for i in range(e_phnum):
        offset = e_phoff + i * e_phentsize
        if offset + 56 > len(data):
            return None
        p_type, = struct.unpack_from("<I", data, offset)
        if p_type == PT_LOAD:
            p_align, = struct.unpack_from("<Q", data, offset + 48)
            return p_align
    return None


def check(archive: Path) -> list[str]:
    """Return one message per non-compliant library; empty means the archive is clean."""
    problems: list[str] = []
    checked = 0

    with zipfile.ZipFile(archive) as zf:
        for name in zf.namelist():
            if not name.endswith(".so"):
                continue
            # APKs store libraries under lib/<abi>/, AARs under jni/<abi>/.
            if not any(f"/{abi}/" in name for abi in SIXTY_FOUR_BIT):
                continue

            alignment = first_load_alignment(zf.read(name))
            checked += 1
            if alignment is None:
                problems.append(f"{name}: could not read a 64-bit ELF program header")
            elif alignment < REQUIRED_ALIGNMENT:
                problems.append(
                    f"{name}: first LOAD segment aligned to {alignment} bytes "
                    f"(0x{alignment:x}), needs at least {REQUIRED_ALIGNMENT} (0x4000)"
                )

    if checked == 0:
        # An archive with no 64-bit libraries at all is far more likely to mean the
        # path is wrong than that the app genuinely ships none — so it fails loudly
        # rather than passing on an empty set.
        problems.append("no 64-bit native libraries found — is this the right archive?")

    return problems


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2

    failed = False
    for arg in argv[1:]:
        archive = Path(arg)
        if not archive.is_file():
            print(f"FAIL  {archive}: not a file", file=sys.stderr)
            failed = True
            continue

        problems = check(archive)
        if problems:
            failed = True
            print(f"FAIL  {archive}", file=sys.stderr)
            for problem in problems:
                print(f"        {problem}", file=sys.stderr)
        else:
            print(f"ok    {archive}: every 64-bit library is 16 KB-aligned")

    if failed:
        print(
            "\n16 KB page alignment is required by Android 15+ and by Play at recent\n"
            "target levels. If this broke after a dependency change, that dependency\n"
            "ships 4 KB-aligned prebuilt libraries — see issue #14 and the fork at\n"
            "https://github.com/nolte/AndroidUSBCamera for how the last one was handled.",
            file=sys.stderr,
        )
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
