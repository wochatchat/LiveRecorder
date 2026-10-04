#!/usr/bin/env python3
"""Generate bilingual release notes from changelog/latest.json.

Usage: gen_changelog.py <versionName>

- Reads changelog/latest.json; if its "version" matches <versionName>,
  prints markdown notes (**中文** / **English** bullet lists).
- Otherwise (missing file / stale version / malformed JSON) exits 1 and
  the caller falls back to the placeholder text.

Alignment: PocketHub template (changelog/latest.json + scripts/gen_changelog.py),
adapted for LiveRecorder's updater which renders the first 20 non-# lines
of the release body as in-app release notes.
"""
import json
import sys


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: gen_changelog.py <versionName>", file=sys.stderr)
        return 1
    version = sys.argv[1]
    try:
        with open("changelog/latest.json", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        print(f"changelog/latest.json unreadable: {e}", file=sys.stderr)
        return 1
    if data.get("version") != version:
        print(
            f"version mismatch: changelog={data.get('version')!r} build={version!r}",
            file=sys.stderr,
        )
        return 1
    lines = []
    for lang, title in (("zh", "中文"), ("en", "English")):
        items = data.get(lang) or []
        if items:
            lines.append(f"**{title}**")
            lines.extend(f"- {it}" for it in items)
            lines.append("")
    body = "\n".join(lines).strip()
    if not body:
        return 1
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
