#!/usr/bin/env python3
"""Print one version's release notes from CHANGELOG.md.

    changelog.py 0.1.0                 Markdown body (GitHub release text)
    changelog.py 0.1.0 --play          plain text, at most 500 characters (Google Play "What's new")

Exits non-zero if the version has no "## [X.Y.Z] - YYYY-MM-DD" section or the section is empty.
"""
import argparse
import pathlib
import re
import sys

PLAY_LIMIT = 500


def section(text: str, version: str) -> str:
    heading = re.compile(r"^## \[" + re.escape(version) + r"\] - (\d{4}-\d{2}-\d{2})\s*$", re.M)
    m = heading.search(text)
    if not m:
        sys.exit(f"CHANGELOG.md has no dated '## [{version}] - YYYY-MM-DD' section")
    nxt = re.compile(r"^## ", re.M).search(text, m.end())
    body = text[m.end():nxt.start() if nxt else len(text)].strip()
    if not body:
        sys.exit(f"CHANGELOG.md section for {version} is empty")
    return body


def plain(md: str, limit: int) -> str:
    """Markdown -> short plain text: '### X' -> 'X:', bullets -> '•', inline markup dropped."""
    lines = []
    for raw in md.splitlines():
        line = raw.rstrip()
        if not line.strip():
            if lines and lines[-1] != "":
                lines.append("")
            continue
        line = re.sub(r"\*\*(.+?)\*\*|__(.+?)__", lambda m: m.group(1) or m.group(2), line)
        line = re.sub(r"`([^`]+)`", r"\1", line)
        line = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line)
        h = re.match(r"^#{1,6}\s+(.*)$", line.strip())
        b = re.match(r"^\s*[-*+]\s+(.*)$", line)
        if h:
            lines.append(h.group(1) + ":")
        elif b:
            lines.append("• " + b.group(1))
        elif lines and lines[-1] != "":
            lines[-1] += " " + line.strip()          # wrapped continuation line
        else:
            lines.append(line.strip())
    out = "\n".join(lines).strip()
    if len(out) <= limit:
        return out
    # cut at a line boundary and say where the rest is
    more = "\n…full notes on GitHub"
    cut = out[: limit - len(more)]
    cut = cut[: cut.rfind("\n")] if "\n" in cut else cut
    return cut.rstrip() + more


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("version")
    ap.add_argument("--play", action="store_true")
    ap.add_argument("--changelog", default=str(pathlib.Path(__file__).resolve().parents[2] / "CHANGELOG.md"))
    a = ap.parse_args()
    body = section(pathlib.Path(a.changelog).read_text(encoding="utf-8"), a.version)
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    sys.stdout.write((plain(body, PLAY_LIMIT) if a.play else body) + "\n")


if __name__ == "__main__":
    main()
