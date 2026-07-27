#!/usr/bin/env python3
"""Emit the golden corpus the Android frontend port is graded against.

Writes, for every sentence below:

  {"text", "normalized", "phonemes", "tokens"}

to two places:

  android/app/src/test/resources/frontend_golden.json    (JVM: normalizer only)
  android/app/src/androidTest/assets/frontend_golden.json (device: full chain)

The corpus is built to exercise the *branches* of `normalize_text`, not to read naturally -
every regex in it should be hit by at least one row, plus each ABBREVIATIONS, WORD_OVERRIDES
and LETTER_NAMES entry and both PHONEME_OVERRIDES triggers.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from inflect_sandbox.frontend import (  # noqa: E402
    _install_upstream_path,
    boundary_pause_seconds,
    phonemes_to_tokens,
    sanitize_phonemes,
    split_text,
    text_to_phonemes,
)

TEST_RESOURCES = ROOT / "android" / "app" / "src" / "test" / "resources"
ANDROID_TEST_ASSETS = ROOT / "android" / "app" / "src" / "androidTest" / "assets"

MONEY = [
    "It costs $5.",
    "It costs $1 exactly.",
    "The invoice was for $1,234.50 in total.",
    "She paid $0.99 for it.",
    "A rounding error of $12.05 showed up.",
    "They raised $2,500,000 last year.",
    "The fee is $1.01 per request.",
]

DATES = [
    "The launch is on 7/4/2026.",
    "It shipped 12/25/1999 without fanfare.",
    "Everything changed on 1/1/2020.",
    "The invalid date 2/30/2021 should stay as written.",
    "Leap day 2/29/2024 is real.",
    "Leap day 2/29/2023 is not.",
]

TIMES = [
    "The meeting starts at 9:00.",
    "We land at 3:05 PM.",
    "Doors open at 10:30 a.m. sharp.",
    "He called at 11:59 p.m.",
    "Be there at 7 AM.",
    "It ends at 5 p.m.",
    "The alarm went off at 12:07.",
]

PHONE_AND_IDS = [
    "Call 555-0143 when you arrive.",
    "Dial 867-5309 for a good time.",
    "Meet me in room 12.",
    "Your flight is 103 to Denver.",
    "Go to gate B7.",
    "Check locker 042 by the door.",
    "Suite 300 is on the left.",
    "Apartment 4B has the better view.",
    "Order 1234 has shipped.",
    "Invoice 07 remains unpaid.",
    "Take aisle 9 to the back.",
    "Extension 250 reaches the desk.",
    "We live at 742 North Evergreen Terrace.",
    "The office is at 221 East Baker Street.",
]

VERSIONS_AND_DECIMALS = [
    "We upgraded to 1.2.3 last night.",
    "Version 10.15.7 fixed it.",
    "The build is 2.0.0.1 now.",
    "It weighs 3.5 kilograms.",
    "Accuracy reached 99.75 percent.",
    "Pi is roughly 3.14159.",
    "The reading was 0.5 volts.",
]

ORDINALS_AND_NUMBERS = [
    "She finished 1st in her heat.",
    "He came 2nd by a hair.",
    "They took 3rd place.",
    "It was my 4th attempt.",
    "The 21st century is well underway.",
    "The 102nd floor is closed.",
    "There were 7 people waiting.",
    "About 42 birds landed.",
    "We counted 1,024 entries.",
    "The population is 2,500 or so.",
    "Serial 98765 is on the label.",
    "The year 2024 was busy.",
    "By 2030 things will differ.",
    "The code is 10011 exactly.",
    "Exactly 100 items remain.",
    "Precisely 1000 were shipped.",
    "A total of 1000000 records.",
    "There are 0 left.",
]

ABBREVIATIONS = [
    "Dr. Chen will see you now.",
    "Mr. Adams left a message.",
    "Mrs. Patel signed the form.",
    "Ms. Okafor is presenting.",
    "Prof. Lindqvist wrote the paper.",
    "We met on St. Vincent Street.",
    "It was Ali vs. Frazier again.",
    "Bring pens, paper, etc. tomorrow.",
    "Some fruits, e.g. apples, keep well.",
    "The result, i.e. the total, was wrong.",
]

WORD_OVERRIDES = [
    "We benchmarked Qwen3 overnight.",
    "The Qwen family is large.",
    "It runs on PyTorch these days.",
    "We store it in SQLite.",
    "Plug in the USB-C cable.",
    "The RTX 3060 is enough.",
    "An RTX 3090 would be faster.",
    "The RTX 4090 draws more power.",
    "An RTX 5080 is rumoured.",
    "The RTX 5090 tops the chart.",
]

ACRONYMS_AND_INITIALS = [
    "The NASA feed went quiet.",
    "She works for the FBI.",
    "He lives in the U.S.A. now.",
    "The J.R.R. estate approved it.",
    "Send it over HTTP for now.",
    "The GPU and the CPU disagreed.",
    "AB testing is not A testing.",
]

LETTERS = [
    f"Section {letter} comes next."
    for letter in "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
]

PUNCTUATION = [
    "Hello, world.",
    "Is this working? It really should be!",
    "Wait; there is more: quite a lot more.",
    "“Quoted speech,” she said.",
    "He paused — then continued.",
    "An en dash – like this – reads as a hyphen.",
    "Trailing off… like that.",
    "A parenthetical (which was long) interrupted him.",
    "Brackets [like these] and braces {like those} vanish.",
    "It’s a contraction test.",
    "!",
    "...",
    "?!",
    "Ending without punctuation",
    "Multiple   internal    spaces collapse.",
    "Comma before period ,. is cleaned up.",
    "Doubled commas,, collapse too.",
    "Space before punctuation , like this .",
    "No space after a comma,like this.",
]

PHONEME_OVERRIDES = [
    "We drove through Saskatchewan in July.",
    "The fluorescent light flickered.",
]

GENERAL = [
    "A small voice can still have something meaningful to say.",
    "The quick brown fox jumps over the lazy dog.",
    "She sells sea shells by the sea shore.",
    "Nothing here needs any normalization at all.",
    "The rain in Spain stays mainly in the plain.",
    "Testing one two three.",
    "A thoroughly unremarkable English sentence.",
    "Read the manual before you begin.",
    "He read the manual before he began.",
    "Colonel Mustard, in the library, with a wrench.",
]

MIXED = [
    "Dr. Chen paid $1,234.50 on 7/4/2026 at 3:05 PM.",
    "Call 555-0143 about invoice 07 for $99.99.",
    "The RTX 4090 hit 99.75 percent utilization at 11:59 p.m.",
    "Flight 103 departs gate B7 at 7 AM on 12/25/1999.",
    "Version 1.2.3 shipped to 1,024 users, e.g. the beta group.",
    "Suite 300, 742 North Evergreen Terrace, costs $2,500 per month.",
]

# Loanwords where eSpeak emits a nasal vowel: the base vowel carries U+0303 COMBINING
# TILDE, which is not one of the 178 symbols. These used to fail the whole utterance;
# `sanitize_phonemes` now drops the mark, and these rows pin that both sides drop it the
# same way. See the note on `frontend.sanitize_phonemes`.
NASAL_VOWELS = [
    "She ordered a croissant and a glass of vin blanc.",
    "We drove through Provence on the way to Besancon.",
    "The denouement was a rapprochement between the two sides.",
    "A nonchalant entrepreneur with a certain penchant for ambiance.",
]

CORPUS = (
    MONEY
    + DATES
    + TIMES
    + PHONE_AND_IDS
    + VERSIONS_AND_DECIMALS
    + ORDINALS_AND_NUMBERS
    + ABBREVIATIONS
    + WORD_OVERRIDES
    + ACRONYMS_AND_INITIALS
    + LETTERS
    + PUNCTUATION
    + PHONEME_OVERRIDES
    + GENERAL
    + MIXED
    + NASAL_VOWELS
)


# Texts chosen to hit every branch of `split_text`: the sentence regex, the internal-mark
# fallback, the whitespace fallback, the `limit // 2` guards and the mid-word cut, the
# first-chunk TTFA budget, plus each BOUNDARY_PAUSES key. `TextChunkerTest` grades the
# Kotlin port against these.
CHUNKING_CORPUS = [
    # Short enough to stay whole.
    "Hello world.",
    "",
    "   ",
    # Whitespace collapsing.
    "Multiple   spaces\tand\nnewlines   collapse.",
    # Every boundary-pause mark, so the pause table is covered.
    "Is this a question? Yes it is. Really! Wait; hold on: now, go.",
    # No terminal punctuation at all - falls to DEFAULT_PAUSE.
    "No punctuation here just words",
    # Longer than the first-chunk / subsequent limits, with commas to break on.
    ("A very long sentence that keeps going and going without stopping, "
     "and then continues past the limit with another clause, and still more text "
     "after that clause so the splitter has to make a second cut somewhere sensible, "
     "and finally it ends here after quite a lot of words indeed."),
    # Longer than the subsequent limit with no internal marks - whitespace fallback.
    ("word " * 90).strip(),
    # Longer than the limit with no spaces at all - mid-word cut.
    "x" * 400,
    # A mark sitting in the first half of the window, so the `limit // 2` guard rejects it.
    "short, " + ("y" * 400),
    # Abbreviations should not force sentence splits.
    "Bring pens, paper, etc. tomorrow before 9:00.",
    "The package is bound for the U.S.A. and should arrive tomorrow.",
    "Dr. Chen met Prof. Adler on St. Vincent Street.",
    # First-chunk budget: one long sentence that must emit >1 chunk under FIRST_CHUNK_LIMIT.
    ("This opening clause is intentionally long enough that the first synthesis chunk "
     "must stop before the old 280-character budget, leaving a continuation for later."),
]


def _chunking_rows() -> list[dict]:
    rows = []
    for text in CHUNKING_CORPUS:
        chunks = split_text(text)
        rows.append(
            {
                "text": text,
                "chunks": chunks,
                "pauses": [boundary_pause_seconds(chunk) for chunk in chunks],
            }
        )
    return rows


def main() -> None:
    _install_upstream_path()
    from inflect_nano_v2_frontend import normalize_text

    rows = []
    sanitized_rows = []
    for text in CORPUS:
        normalized = normalize_text(text)
        phonemes = text_to_phonemes(text)
        # `phonemes` is stored raw, tilde and all - it is what the Android phonemizer must
        # reproduce. `tokens` comes from the sanitized form, because both sides drop
        # unrepresentable characters inside their tokenizer. See `sanitize_phonemes`.
        _, dropped = sanitize_phonemes(phonemes)
        if dropped:
            sanitized_rows.append((text, dropped))
        tokens = phonemes_to_tokens(phonemes)[0].tolist()
        rows.append(
            {
                "text": text,
                "normalized": normalized,
                "phonemes": phonemes,
                "tokens": tokens,
            }
        )

    payload = json.dumps(rows, ensure_ascii=False, indent=2)
    for destination in (TEST_RESOURCES, ANDROID_TEST_ASSETS):
        destination.mkdir(parents=True, exist_ok=True)
        (destination / "frontend_golden.json").write_text(payload)

    # Chunking is pure string handling, so only the JVM test needs it - no device required.
    chunking = json.dumps(_chunking_rows(), ensure_ascii=False, indent=2)
    (TEST_RESOURCES / "chunking_golden.json").write_text(chunking)

    print(f"wrote {len(rows)} rows to:")
    print(f"  {TEST_RESOURCES / 'frontend_golden.json'}")
    print(f"  {ANDROID_TEST_ASSETS / 'frontend_golden.json'}")
    if sanitized_rows:
        print(f"\n{len(sanitized_rows)} rows dropped phonemes outside the symbol table:")
        for text, dropped in sanitized_rows:
            marks = ", ".join(f"U+{ord(c):04X}" for c in dropped)
            print(f"  {text!r}: {marks}")


if __name__ == "__main__":
    main()
