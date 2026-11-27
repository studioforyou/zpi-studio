#!/usr/bin/env python3
"""Checks the numbers of the backend's Flyway migrations and seeds.

Every migration is named V<version>__<description>.sql and every seed R__seed_<NN>_<description>.sql;
Flyway silently skips a file whose name does not match. No two migrations share a version and no two
seeds share a number.

With --base REF, the files the current tree adds compared with REF have to continue REF's numbering
without a gap: after V26 the next migration is V27, after seed 26 the next seed is 27. Flyway refuses
to start on a database that already ran V27 when a migration with a lower version shows up, and runs
the seeds in the order of their numbers.

Usage: python3 scripts/check_db_numbering.py [--base origin/main]
"""

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB = "backend/src/main/resources/db"


@dataclass(frozen=True)
class Kind:
    name: str
    directory: str
    pattern: re.Pattern
    example: str
    width: int

    def number(self, path):
        match = self.pattern.fullmatch(os.path.basename(path))
        return int(match.group(1)) if match else None

    def label(self, number):
        return f"V{number}" if self.name == "migration" else f"seed {number:0{self.width}d}"

    def renamed(self, path, number):
        name = os.path.basename(path)
        match = self.pattern.fullmatch(name)
        return f"{name[:match.start(1)]}{number:0{self.width}d}{name[match.end(1):]}"


KINDS = [
    Kind(
        name="migration",
        directory=f"{DB}/migration",
        pattern=re.compile(r"V([1-9][0-9]*)__\w+\.sql", re.ASCII),
        example="V27__room_notes.sql",
        width=1,
    ),
    Kind(
        name="seed",
        directory=f"{DB}/seed",
        pattern=re.compile(r"R__seed_([0-9]{2})_\w+\.sql", re.ASCII),
        example="R__seed_27_room_notes.sql",
        width=2,
    ),
]


def checked(path):
    name = os.path.basename(path)
    return not name.startswith(".") and not name.endswith(".md")


def current_files(kind):
    directory = ROOT / kind.directory
    if not directory.is_dir():
        return []
    return sorted(
        path.relative_to(ROOT).as_posix()
        for path in directory.rglob("*")
        if path.is_file() and checked(path.name)
    )


def files_at(ref, kind):
    result = subprocess.run(
        ["git", "-C", str(ROOT), "ls-tree", "-r", "--name-only", ref, "--", f"{kind.directory}/"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        sys.exit(f"error: cannot list {kind.directory} at {ref}: {result.stderr.strip()}")
    return [path for path in result.stdout.splitlines() if checked(path)]


def check(kind, base, errors):
    files = current_files(kind)
    by_number = {}
    for path in files:
        number = kind.number(path)
        if number is None:
            errors.append((path, f"not a valid {kind.name} file name, expected e.g. {kind.example}"))
        else:
            by_number.setdefault(number, []).append(path)

    for number, paths in sorted(by_number.items()):
        if len(paths) > 1:
            names = ", ".join(os.path.basename(path) for path in paths)
            for path in paths:
                errors.append((path, f"{kind.label(number)} is used by more than one file: {names}"))

    summary = f"{len(files)} {kind.name}s, last {kind.label(max(by_number))}" if by_number else f"no {kind.name}s"
    if base is None:
        return summary

    base_files = set(files_at(base, kind))
    last = max((n for n in map(kind.number, base_files) if n is not None), default=0)
    added = sorted((number, path) for number, paths in by_number.items() for path in paths if path not in base_files)
    expected = last + 1
    for number, path in added:
        if number != expected:
            errors.append((
                path,
                f"expected {kind.label(expected)}: the last {kind.name} on {base} is {kind.label(last)} "
                f"and new {kind.name}s continue from it without gaps. Rename it to {kind.renamed(path, expected)}",
            ))
        expected += 1
    if added:
        summary += f", added {', '.join(kind.label(number) for number, _ in added)}"
    return summary


def report(path, message):
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::error file={path},title=Migration and seed numbers::{message}")
    else:
        print(f"error: {path}: {message}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", help="git ref whose numbering new files continue, e.g. origin/main")
    args = parser.parse_args()

    errors = []
    summaries = [check(kind, args.base, errors) for kind in KINDS]
    for path, message in errors:
        report(path, message)
    if errors:
        print(f"{len(errors)} problem(s) with migration and seed numbers", file=sys.stderr)
        return 1
    against = f" (compared with {args.base})" if args.base else ""
    print(f"Migration and seed numbers OK{against}: {'; '.join(summaries)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
