#!/usr/bin/env python3
"""
Parse dream journal files and generate a JSON object with dream counts per day.
Handles two date formats:
  - M/D/YY  (e.g. 7/13/24)
  - YYYY-MM-DD (e.g. 2025-10-21)

Date headers may optionally be followed by a timestamp (HH:MM:SS) which is
ignored and NOT counted as dream body content.

Dream count logic:
  - After a date header, each non-empty "chunk" of text separated by blank lines
    or ,,, separator lines counts as one dream.
  - If there are no chunks before the next date, count is 0.
"""

import re
import json
from datetime import date, timedelta

# ── File paths ────────────────────────────────────────────────────────────────
FILES = [
    "/home/twain/noteVault/drmz.md",
    "/home/twain/noteVault/Dreams need made.md",
]

# ── Date range ────────────────────────────────────────────────────────────────
START_DATE = date(2020, 3, 16)
END_DATE   = date.today()          # include everything up to today

# ── Regex patterns ────────────────────────────────────────────────────────────
# Matches  7/13/24  or  07/13/24  etc. (M/D/YY or MM/DD/YY)
SHORT_DATE_RE = re.compile(
    r'(?<![/\d])(\d{1,2})/(\d{1,2})/(\d{2})(?![/\d])'
)
# Matches  2025-10-21  or  2025-3-14  (1 or 2 digit month/day)
LONG_DATE_RE = re.compile(
    r'(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)'
)
# Matches a trailing timestamp like  07:12:48  (to strip from date header lines)
TIMESTAMP_RE = re.compile(r'^\d{2}:\d{2}:\d{2}$')


def parse_date_from_match(m, fmt):
    """Return a date object or None if the parsed date is invalid."""
    try:
        if fmt == 'short':
            month, day, year_2 = int(m.group(1)), int(m.group(2)), int(m.group(3))
            year = 2000 + year_2
            return date(year, month, day)
        else:  # long
            year, month, day = int(m.group(1)), int(m.group(2)), int(m.group(3))
            return date(year, month, day)
    except ValueError:
        return None


def line_is_date_header(line):
    """
    Return (date_obj, rest_of_line) if the line *starts with* a date pattern,
    otherwise return (None, None).
    We require the date to appear at the very beginning of the line
    (possibly after whitespace / markdown heading markers like #).
    Trailing timestamps (HH:MM:SS) are stripped and not returned as body text.
    """
    stripped = line.strip().lstrip('#').strip()

    # Try short format first
    m = SHORT_DATE_RE.match(stripped)
    if m:
        d = parse_date_from_match(m, 'short')
        if d:
            rest = stripped[m.end():].strip()
            # Strip trailing timestamp if that's all that remains
            if TIMESTAMP_RE.match(rest):
                rest = ''
            return d, rest

    # Try long format
    m = LONG_DATE_RE.match(stripped)
    if m:
        d = parse_date_from_match(m, 'long')
        if d:
            rest = stripped[m.end():].strip()
            # Strip trailing timestamp if that's all that remains
            if TIMESTAMP_RE.match(rest):
                rest = ''
            return d, rest

    return None, None


def is_separator(line):
    """Return True if the line is a chunk separator (blank or ,,,)."""
    s = line.strip()
    return s == '' or s == ',,,'


def count_dreams_in_block(lines):
    """
    Given a list of lines that belong to a single date's entry,
    count the number of dream 'chunks' (paragraphs separated by blank lines
    or ,,, separator lines).
    A chunk must contain at least one non-blank, non-separator line.
    """
    in_chunk = False
    count = 0
    for line in lines:
        if is_separator(line):    # blank line or ,,, separator
            in_chunk = False
        else:                     # real content line
            if not in_chunk:
                count += 1
                in_chunk = True
    return count


def parse_file(filepath):
    """
    Parse one file and return a dict  {date: dream_count}.
    A date may appear multiple times across files; counts will be merged later.
    """
    results = {}

    try:
        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            raw_lines = f.readlines()
    except FileNotFoundError:
        print(f"WARNING: File not found: {filepath}")
        return results

    # We'll do a two-pass approach:
    # 1. Walk through lines, detect date headers, collect body lines per date.
    # 2. Count chunks in each body.

    current_date = None
    current_body = []

    def flush():
        nonlocal current_date, current_body
        if current_date is not None:
            cnt = count_dreams_in_block(current_body)
            if current_date in results:
                results[current_date] += cnt
            else:
                results[current_date] = cnt
        current_date = None
        current_body = []

    for line in raw_lines:
        d, rest = line_is_date_header(line)
        if d is not None:
            flush()
            current_date = d
            # If there's text on the same line as the date, treat it as
            # the first line of the body.
            current_body = [rest] if rest else []
        else:
            if current_date is not None:
                current_body.append(line.rstrip('\n'))

    flush()  # handle last entry

    return results


def merge_results(all_results):
    """Merge a list of {date: count} dicts, summing counts for the same date."""
    merged = {}
    for r in all_results:
        for d, cnt in r.items():
            merged[d] = merged.get(d, 0) + cnt
    return merged


def build_full_range(dream_counts):
    """
    Build an ordered dict covering every day from START_DATE to END_DATE.
    Days with no dreams get 0.
    """
    output = {}
    current = START_DATE
    while current <= END_DATE:
        key = current.strftime('%Y-%m-%d')
        output[key] = dream_counts.get(current, 0)
        current += timedelta(days=1)
    return output


def main():
    all_results = []
    for fp in FILES:
        print(f"Parsing: {fp}")
        r = parse_file(fp)
        print(f"  → found {len(r)} dated entries")
        all_results.append(r)

    merged = merge_results(all_results)
    print(f"\nTotal unique dates with dreams: {len(merged)}")
    print(f"Date range: {START_DATE} → {END_DATE}")

    full = build_full_range(merged)

    total_dreams = sum(full.values())
    days_with_dreams = sum(1 for v in full.values() if v > 0)
    print(f"Total dream entries: {total_dreams}")
    print(f"Days with at least one dream: {days_with_dreams}")
    print(f"Total days in output: {len(full)}")

    output_obj = {"Dream Recorded": full}

    out_path = "/home/twain/AndroidStudioProjects/tail/dream_recorded_history.json"
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(output_obj, f, indent=4)

    print(f"\nOutput written to: {out_path}")


if __name__ == '__main__':
    main()
