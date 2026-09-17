"""Aggregates the raw benchmark runs into one JSON file and a Markdown table.

Reports the median rather than the mean: a single scheduling hiccup on a laptop skews a mean of
five runs badly, and the median is the number that actually represents a typical run. The full
set of runs is kept in the JSON so the spread stays visible and nobody has to trust the summary.
"""
import glob
import json
import os
import statistics

rows = int(os.environ["ROWS"])
repeats = int(os.environ["REPEATS"])
out_path = os.environ["OUT"]


def load(pattern):
    return [json.load(open(p)) for p in sorted(glob.glob(pattern))]


def per_check(report):
    out = {}
    for key in ("findings", "passed", "couldNotRun"):
        for entry in report.get(key, []):
            out[entry["checkId"]] = entry["durationMs"]
    return out


def summarise(reports):
    totals = [r["durationMs"] for r in reports]
    checks = {}
    for r in reports:
        for check_id, ms in per_check(r).items():
            checks.setdefault(check_id, []).append(ms)
    return {
        "runs": totals,
        "medianMs": statistics.median(totals),
        "minMs": min(totals),
        "maxMs": max(totals),
        "perCheckMedianMs": {k: statistics.median(v) for k, v in sorted(checks.items())},
    }


untuned = summarise(load("/tmp/triage-untuned-*.json"))
tuned = summarise(load("/tmp/triage-tuned-*.json"))

result = {
    "bookings": rows,
    "repeats": repeats,
    "capturedAt": os.environ["STAMP"],
    "postgresVersion": os.environ["PGVER"].strip(),
    "databaseSize": os.environ["SIZE"].strip(),
    "untuned": untuned,
    "tuned": tuned,
    "speedup": round(untuned["medianMs"] / tuned["medianMs"], 1) if tuned["medianMs"] else None,
}

os.makedirs(os.path.dirname(out_path), exist_ok=True)
with open(out_path, "w") as f:
    json.dump(result, f, indent=2)

print()
print(f"## {rows:,} bookings, {result['databaseSize']}, PostgreSQL {result['postgresVersion']}")
print(f"Median of {repeats} runs, after one discarded warm-up per pass.")
print()
print(f"| | Untuned | Tuned | Speedup |")
print(f"|---|---:|---:|---:|")
print(f"| **Full run (15 checks)** | **{untuned['medianMs']:,} ms** | **{tuned['medianMs']:,} ms** "
      f"| **{result['speedup']}x** |")
print(f"| spread (min-max) | {untuned['minMs']:,}-{untuned['maxMs']:,} ms "
      f"| {tuned['minMs']:,}-{tuned['maxMs']:,} ms | |")
print()
print("| Check | Untuned | Tuned | Speedup |")
print("|---|---:|---:|---:|")
for check_id in sorted(tuned["perCheckMedianMs"]):
    u = untuned["perCheckMedianMs"].get(check_id, 0)
    t = tuned["perCheckMedianMs"][check_id]
    factor = f"{u / t:.1f}x" if t else "-"
    print(f"| {check_id} | {u:,} ms | {t:,} ms | {factor} |")
