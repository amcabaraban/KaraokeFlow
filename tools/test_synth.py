#!/usr/bin/env python3
"""Build and test the exact JNI bridge with original, temporary synthetic SF2 data.

Linux/macOS needs a JDK and C++ compiler. Windows can pass a Clang compiler and
JAVA_HOME. No downloaded music or SoundFont is used, committed, or put in the APK.
"""
import argparse
import math
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def chunk(name, data):
    return name.encode("ascii") + struct.pack("<I", len(data)) + data + (b"\0" if len(data) % 2 else b"")


def synth_font():
    """One sine instrument, one square instrument, and a bank-128 drum preset."""
    count = 4410
    samples = []
    for square in (False, True):
        for i in range(count):
            wave = math.sin(2 * math.pi * 440 * i / 44100)
            value = (1 if wave >= 0 else -1) if square else wave
            samples.append(round(10000 * value))
        samples.extend([0] * 46)  # SF2 interpolation guard samples.
    pcm = struct.pack("<" + "h" * len(samples), *samples)
    presets = b"".join(struct.pack("<20sHHHIII", name, program, bank, bag, 0, 0, 0)
                        for name, program, bank, bag in
                        [(b"Original sine", 0, 0, 0), (b"Original square", 1, 0, 1),
                         (b"Original drum", 0, 128, 2), (b"EOP", 0, 0, 3)])
    pbag = b"".join(struct.pack("<HH", i, 0) for i in range(4))
    pgen = b"".join(struct.pack("<HH", 41, instrument) for instrument in (0, 1, 0)) + b"\0" * 4
    instruments = b"".join(struct.pack("<20sH", name, bag)
                            for name, bag in [(b"Sine", 0), (b"Square", 1), (b"EOI", 2)])
    ibag = b"".join(struct.pack("<HH", i, 0) for i in (0, 4, 8))
    igen = b""
    for sample in (0, 1):
        for operation, amount in [(54, 1), (34, -12000), (38, -2400), (53, sample)]:
            igen += struct.pack("<HH", operation, amount & 0xffff)
    igen += b"\0" * 4
    headers = b""
    for i, name in enumerate((b"Sine", b"Square")):
        start = i * (count + 46)
        headers += struct.pack("<20sIIIIIBbHH", name, start, start + count, start,
                               start + count, 44100, 69, 0, 0, 1)
    headers += struct.pack("<20sIIIIIBbHH", b"EOS", len(samples), len(samples),
                           len(samples), len(samples), 0, 0, 0, 0, 1)
    pdta = b"pdta" + b"".join(chunk(name, value) for name, value in
                              [("phdr", presets), ("pbag", pbag), ("pmod", b"\0" * 10),
                               ("pgen", pgen), ("inst", instruments), ("ibag", ibag),
                               ("imod", b"\0" * 10), ("igen", igen), ("shdr", headers)])
    # Original odd INFO text and an ignored odd auxiliary chunk exercise RIFF pads.
    info = chunk("LIST", b"INFO" + chunk("INAM", b"Original tone"))
    body = b"sfbk" + info + chunk("LIST", b"sdta" + chunk("smpl", pcm) + chunk("sm24", b"\0" * 3)) + chunk("LIST", pdta)
    return chunk("RIFF", body)


def run(command):
    print("Running", " ".join(str(x) for x in command), flush=True)
    subprocess.run([str(x) for x in command], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--compiler", default=os.environ.get("CXX", "g++"))
    args = parser.parse_args()
    javac = shutil.which("javac")
    if not javac:
        raise SystemExit("JNI synthesis tests require a JDK (javac).")
    java_home = Path(os.environ.get("JAVA_HOME", Path(javac).resolve().parent.parent))
    platform = "win32" if sys.platform == "win32" else ("darwin" if sys.platform == "darwin" else "linux")
    with tempfile.TemporaryDirectory(prefix="karaokeflow-synth-") as directory:
        work = Path(directory)
        sf2 = synth_font()
        (work / "original.sf2").write_bytes(sf2)
        (work / "invalid.sf2").write_bytes(b"This is not a SoundFont.")
        (work / "truncated.sf2").write_bytes(sf2[:-1])
        invalid_reference = bytearray(sf2)
        # Corrupt a sample-ID generator without changing otherwise valid RIFF sizes.
        at = invalid_reference.index(b"igen") + 8 + 3 * 4 + 2
        invalid_reference[at:at + 2] = struct.pack("<H", 65535)
        (work / "bad-reference.sf2").write_bytes(invalid_reference)
        invalid_link = bytearray(sf2)
        at = invalid_link.index(b"phdr") + 8 + 24
        invalid_link[at:at + 2] = struct.pack("<H", 65535)
        (work / "bad-link.sf2").write_bytes(invalid_link)
        lib = work / ("karaokeflow_synth.dll" if platform == "win32" else
                      ("libkaraokeflow_synth.dylib" if platform == "darwin" else "libkaraokeflow_synth.so"))
        command = [args.compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-shared", "-pthread",
                   "-I" + str(java_home / "include"), "-I" + str(java_home / "include" / platform),
                   ROOT / "app/src/main/cpp/karaokeflow_synth.cpp", "-o", lib]
        if platform != "win32":
            command.extend(["-fPIC", "-lm"])
        run(command)
        classes = work / "classes"
        classes.mkdir()
        run([javac, "--release", "8", "-d", classes,
             ROOT / "app/src/main/java/com/karaokeflow/app/MidiSequence.java",
             ROOT / "app/src/main/java/com/karaokeflow/app/NativeSynth.java",
             ROOT / "app/src/test/host/com/karaokeflow/app/NativeSynthSmoke.java"])
        run([java_home / "bin" / ("java.exe" if platform == "win32" else "java"),
             "-Djava.library.path=" + str(work), "-cp", classes,
             "com.karaokeflow.app.NativeSynthSmoke", work])


if __name__ == "__main__":
    main()
