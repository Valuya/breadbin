#!/usr/bin/env python3
"""Checks the store listings against Play's field limits.

Play truncates silently in some places and rejects in others, and neither is
something to discover after uploading. Run it before touching the Console.
"""
import pathlib
import re
import sys

# Section heading -> limit. The patterns take the other languages too, so adding
# a translated listing later needs no change here.
LIMITS = [
    (re.compile(r"^## (App name|Nom de l'application|App-naam)", re.I), "name", 30),
    (re.compile(r"^## (Short description|Description courte|Korte beschrijving)", re.I), "short", 80),
    (re.compile(r"^## (Full description|Description complète|Volledige beschrijving)", re.I), "full", 4000),
]

def sections(text):
    """Yields (heading, body) for every '## ' section."""
    parts = re.split(r"^(## .*)$", text, flags=re.M)
    for i in range(1, len(parts), 2):
        yield parts[i], parts[i + 1].strip()

def main():
    failed = False
    for path in sorted(pathlib.Path("store").glob("listing-*.md")):
        text = path.read_text(encoding="utf-8")
        seen = set()
        for heading, body in sections(text):
            for pattern, field, limit in LIMITS:
                if not pattern.match(heading):
                    continue
                seen.add(field)
                n = len(body)
                status = "ok " if n <= limit else "OVER"
                if n > limit:
                    failed = True
                print(f"{status} {path.name:16} {field:6} {n:5}/{limit}")
        missing = {f for _, f, _ in LIMITS} - seen
        if missing:
            failed = True
            print(f"OVER {path.name:16} missing sections: {', '.join(sorted(missing))}")
    return 1 if failed else 0

if __name__ == "__main__":
    sys.exit(main())
