#!/usr/bin/env python3
"""Fails if the documentation no longer describes the project.

A repo with this much prose drifts silently: a check gets added and the README still says
fifteen, the benchmark is re-run and the headline figure is stale, a runbook is renamed and a
link dies. Those are exactly the errors nobody notices, because nothing executes a README.

This does. It is wired into CI, so a claim that stops being true breaks the build in the same
way a failing test does -- which is the standard the checks themselves are held to.

What it deliberately ignores in the captured sample: generated timestamps, per-check
millisecond timings, and the Target line. All three are properties of where the tool happened to
run -- the seeded data is dated from now(), timings vary, and CI reaches PostgreSQL on a
different host and port from a developer's sandbox. Asserting on them would make this a nuisance
rather than a guard, and a guard that fires on noise gets switched off.

What it does still assert about that sample is everything that matters: the findings present,
their order, their severities, the columns, the values, the runbook lines and the exit line.
"""
import csv
import glob
import json
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
os.chdir(ROOT)

failures = []
checks = 0


def check(label, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print(f"  ok    {label}")
    else:
        print(f"  FAIL  {label}" + (f" -- {detail}" if detail else ""))
        failures.append(label)


def normalise_sample(text):
    """Strips the parts of a captured run that legitimately differ between environments."""
    out = []
    for line in text.strip().split("\n"):
        stripped = line.strip()
        # Where and when it ran, and how long it took, are not claims about the project.
        if stripped.startswith(("Started", "Elapsed", "Target")):
            continue
        line = re.sub(r"\d{4}-\d{2}-\d{2}T[\d:.]+Z", "<timestamp>", line)
        line = re.sub(r"checked in \d+ ms", "checked in <n> ms", line)
        out.append(line.rstrip())
    return out


readme = pathlib.Path("README.md").read_text()

# ---------------------------------------------------------------- counts on disk
sql = glob.glob("src/main/resources/checks/sql/*.sql")
runbooks = glob.glob("runbooks/*.md")
scenarios = glob.glob("scenarios/*.sql")

check("15 check queries on disk", len(sql) == 15, f"found {len(sql)}")
check("15 runbooks on disk", len(runbooks) == 15, f"found {len(runbooks)}")
check("6 scenarios on disk", len(scenarios) == 6, f"found {len(scenarios)}")
check("README says 15 checks", "15 read-only SQL diagnostics" in readme)

# Catalogue and files must agree.
catalogue = pathlib.Path("src/main/java/com/abheenash/triage/core/CheckCatalog.java").read_text()
ids = sorted(set(re.findall(r'new CheckSpec\("([A-Z0-9]+)"', catalogue)))
check("catalogue declares 15 checks", len(ids) == 15, f"found {len(ids)}")
for cid in ids:
    check(f"{cid} is documented in the README", cid in readme)

# ---------------------------------------------------------------- links
bad_links = []
for md in pathlib.Path(".").rglob("*.md"):
    if "target/" in str(md):
        continue
    for _, link in re.findall(r"\[([^\]]+)\]\(([^)]+)\)", md.read_text()):
        if link.startswith(("http://", "https://", "#", "mailto:")):
            continue
        if not (md.parent / link.split("#")[0]).resolve().exists():
            bad_links.append(f"{md} -> {link}")
check("every relative documentation link resolves", not bad_links, "; ".join(bad_links))

# ---------------------------------------------------------------- benchmark figures
results = {}
for f in glob.glob("benchmark/results/*.json"):
    with open(f) as fh:
        data = json.load(fh)
    key = "1cpu" if "1CPU" in f else ("before" if "BEFORE" in f else str(data["bookings"]))
    results[key] = data

benchmark_doc = pathlib.Path("docs/benchmark.md").read_text()
for key, label in [("10000000", "8-core 10M"), ("1cpu", "1-CPU 10M")]:
    if key not in results:
        check(f"{label} result file present", False, "missing")
        continue
    ms = results[key]["tuned"]["medianMs"]
    pretty = f"{ms:,} ms"
    check(f"{label} figure {pretty} appears in docs/benchmark.md", pretty in benchmark_doc)
    check(f"{label} figure {pretty} appears in the README", pretty in readme)

# ---------------------------------------------------------------- test counts and coverage
surefire = glob.glob("target/surefire-reports/TEST-*.xml")
failsafe = glob.glob("target/failsafe-reports/TEST-*.xml")
if surefire and failsafe:
    def total(paths):
        return sum(int(re.search(r'tests="(\d+)"', open(p).read()).group(1)) for p in paths)

    unit, integration = total(surefire), total(failsafe)
    claimed = re.search(r"\*\*(\d+) tests: (\d+) unit, (\d+) integration", readme)
    check("README states a test count", claimed is not None)
    if claimed:
        c_total, c_unit, c_int = (int(g) for g in claimed.groups())
        check(f"README unit count matches ({c_unit})", c_unit == unit, f"actual {unit}")
        check(f"README integration count matches ({c_int})", c_int == integration, f"actual {integration}")
        check(f"README total matches ({c_total})", c_total == unit + integration,
              f"actual {unit + integration}")
else:
    print("  skip  test counts (run 'mvn verify' first)")

cov = pathlib.Path("target/site/jacoco/jacoco.csv")
if cov.exists():
    rows = list(csv.DictReader(cov.open()))
    missed = sum(int(r["LINE_MISSED"]) for r in rows)
    covered = sum(int(r["LINE_COVERED"]) for r in rows)
    actual = round(100 * covered / (missed + covered), 1)
    claimed_cov = re.search(r"([\d.]+)% line coverage", readme)
    check("README states line coverage", claimed_cov is not None)
    if claimed_cov:
        # Within half a point: coverage moves slightly with unrelated edits, and a doc guard that
        # fires on noise gets disabled.
        stated = float(claimed_cov.group(1))
        check(f"README coverage {stated}% matches measured {actual}%", abs(stated - actual) <= 0.5,
              f"measured {actual}%")
else:
    print("  skip  coverage (run 'mvn verify' first)")

# ---------------------------------------------------------------- captured sample output
sample_path = os.environ.get("TRIAGE_SAMPLE_OUTPUT")
if sample_path and pathlib.Path(sample_path).exists():
    block = re.search(r"```\n(Production Triage Toolkit 1\.0\.0.*?)\n```", readme, re.S)
    check("README contains a captured sample run", block is not None)
    if block:
        expected = normalise_sample(block.group(1))
        actual_sample = normalise_sample(pathlib.Path(sample_path).read_text())
        check("captured sample matches current output", expected == actual_sample,
              "re-capture it; structure or column order changed")
else:
    print("  skip  sample output (set TRIAGE_SAMPLE_OUTPUT to a captured run)")

print(f"\n{checks - len(failures)}/{checks} documentation claims verified")
if failures:
    print("\nDocumentation is out of date:")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
