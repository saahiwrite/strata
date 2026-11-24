#!/usr/bin/env python3
"""Print a line-coverage table from each module's JaCoCo CSV (#47).

Usage: python3 .github/scripts/coverage_summary.py [repo root]
Reads */target/site/jacoco/jacoco.csv, written by `mvn verify`, and prints a
Markdown table (one row per module plus a total) for the GitHub job summary.
"""
import csv
import glob
import os
import sys


def module_totals(csv_path):
    """Return (covered, missed) for lines and branches in one module's report."""
    lines = [0, 0]
    branches = [0, 0]
    with open(csv_path, newline="") as f:
        for row in csv.DictReader(f):
            lines[0] += int(row["LINE_COVERED"])
            lines[1] += int(row["LINE_MISSED"])
            branches[0] += int(row["BRANCH_COVERED"])
            branches[1] += int(row["BRANCH_MISSED"])
    return lines, branches


def pct(covered, missed):
    total = covered + missed
    return "n/a" if total == 0 else f"{100 * covered / total:.1f}%"


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    reports = sorted(glob.glob(os.path.join(root, "**/target/site/jacoco/jacoco.csv"), recursive=True))
    if not reports:
        print("No JaCoCo reports found. Did `mvn verify` run?")
        return 1

    print("| Module | Line coverage | Branch coverage |")
    print("|---|---|---|")
    all_lines = [0, 0]
    all_branches = [0, 0]
    for path in reports:
        module = os.path.relpath(path, root).split("/target/")[0]
        lines, branches = module_totals(path)
        print(f"| {module} | {pct(*lines)} | {pct(*branches)} |")
        for i in range(2):
            all_lines[i] += lines[i]
            all_branches[i] += branches[i]
    print(f"| **Total** | **{pct(*all_lines)}** | **{pct(*all_branches)}** |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
