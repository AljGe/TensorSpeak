#!/usr/bin/env python3
"""Check `PhonemizerCompat.kt` and the trimmed espeak-ng-data without a device.

The Android parity test (`EspeakParityTest`) needs hardware. This script closes most of that
gap on the host: it transliterates PhonemizerCompat.kt *back* into Python, drives it with the
raw `espeak_TextToPhonemes` C API - the exact call the JNI shim makes - reading the trimmed
`espeak-ng-data` that ships in the APK, and diffs the result against the golden corpus
produced by the real `phonemizer`.

So it verifies two things the JVM tests cannot:

  1. the preserve / postprocess / restore algorithm was ported correctly;
  2. the trimmed voice data is sufficient for en-us.

What it does *not* cover is the Kotlin transliteration itself and the JNI marshalling; those
still need `./gradlew :app:connectedDebugAndroidTest` on a device.

Prerequisites: `scripts/export_frontend_golden.py` and
`scripts/export_android_assets.py --espeak-data`.
"""
import ctypes
import json
import os
import re
import sys

import espeakng_loader

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The same 1.52.0 build the sandbox phonemizes with, and the vendored native espeak-ng is
# pinned to match - so any difference here is the algorithm, not the engine.
lib = ctypes.CDLL(espeakng_loader.get_library_path())
data = os.path.join(ROOT, "android/app/src/main/assets/espeak-ng-data")
if not os.path.isdir(data):
    sys.exit(f"missing {data} - run scripts/export_android_assets.py --espeak-data")
lib.espeak_Initialize.restype = ctypes.c_int
assert lib.espeak_Initialize(0x02, 0, data.encode(), 0) > 0
assert lib.espeak_SetVoiceByName(b"en-us") == 0
lib.espeak_TextToPhonemes.restype = ctypes.c_char_p
lib.espeak_TextToPhonemes.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_int, ctypes.c_int]


def text_to_phonemes(text):
    ptr = ctypes.c_char_p(text.encode())
    cur = ctypes.cast(ctypes.pointer(ptr), ctypes.POINTER(ctypes.c_void_p))
    out = []
    while cur.contents.value is not None:
        r = lib.espeak_TextToPhonemes(cur, 1, (ord("_") << 8) | 0x02)
        if r:
            out.append(r.decode())
    return " ".join(out)


DEFAULT_MARKS = ';:,.!?¡¿—…"«»“”(){}[]'
MARKS_RE = re.compile(r"(\s*[" + re.escape(DEFAULT_MARKS) + r"]+\s*)+")
WORD = " "


def preserve(line):
    matches = list(MARKS_RE.finditer(line))
    if not matches:
        return [line], []
    if len(matches) == 1 and matches[0].group() == line:
        return [], [(0, line, "A")]
    marks = []
    for i, m in enumerate(matches):
        pos = "I"
        if i == 0 and line.startswith(m.group()):
            pos = "B"
        elif i == len(matches) - 1 and line.endswith(m.group()):
            pos = "E"
        marks.append((0, m.group(), pos))
    chunks = []
    remainder = line
    for _, mark, _p in marks:
        split = remainder.split(mark)
        chunks.append(split[0])
        remainder = mark.join(split[1:])
    chunks.append(remainder)
    return [c for c in chunks if c], marks


def restore(text, marks):
    text = list(text)
    marks = list(marks)
    out = []
    pos = 0
    while text or marks:
        if not marks:
            out.extend(text)
            text = []
        elif not text:
            out.append("".join(m[1] for m in marks).replace(" ", WORD))
            marks = []
        else:
            index, mark_text, position = marks[0]
            if index != pos:
                out.append(text.pop(0))
                pos += 1
                continue
            marks.pop(0)
            mark = mark_text.replace(" ", WORD)
            if text[0].endswith(WORD):
                text[0] = text[0][: -len(WORD)]
            if position == "B":
                text[0] = mark + text[0]
            elif position == "E":
                out.append(text.pop(0) + mark)
                pos += 1
            elif position == "A":
                out.append(mark)
                pos += 1
            else:
                if len(text) == 1:
                    text[0] = text[0] + mark
                else:
                    first = text.pop(0)
                    text[0] = first + mark + text[0]
    return out


def postprocess(raw):
    line = raw.strip().replace("\n", " ").replace("  ", " ")
    line = re.sub(r"_+", "_", line)
    line = re.sub(r"_ ", " ", line)
    if not line:
        return ""
    return WORD.join(w.strip().replace("_", "") for w in line.split(" "))


OVERRIDES = {"sˈæskɐtʃˌuːən": "sɐskˈætʃəwən", "flʊɹɹˈɛsənt": "flʊˈɹɛsənt"}


def phonemize_line(line):
    chunks, marks = preserve(line)
    phonemized = [postprocess(text_to_phonemes(c)) for c in chunks]
    result = restore(phonemized, marks)
    text = result[0] if result else ""
    for s, d in OVERRIDES.items():
        text = text.replace(s, d)
    return re.sub(r"\s+", " ", text).strip()


golden = os.path.join(ROOT, "android/app/src/test/resources/frontend_golden.json")
if not os.path.isfile(golden):
    sys.exit(f"missing {golden} - run scripts/export_frontend_golden.py")
with open(golden, encoding="utf-8") as handle:
    rows = json.load(handle)
bad = []
for row in rows:
    actual = phonemize_line(row["normalized"])
    if actual != row["phonemes"]:
        bad.append((row["text"], row["phonemes"], actual))

print(f"{len(rows) - len(bad)}/{len(rows)} rows match")
for text, expected, actual in bad[:15]:
    print(f"\n  input:    {text}\n  expected: {expected}\n  actual:   {actual}")
sys.exit(1 if bad else 0)
