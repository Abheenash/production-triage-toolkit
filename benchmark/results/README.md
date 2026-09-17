# Raw benchmark results

One JSON file per `scripts/benchmark.sh` run, holding every individual timing rather than only the
summary, so the spread stays visible. `docs/benchmark.md` is the write-up; these are the numbers
behind it.

| File | Dataset | Untuned | Tuned | What it shows |
|---|---:|---:|---:|---|
| `10000000-...-BEFORE-TUNING.json` | 10M | 2,154 ms | 2,647 ms | The first working version: DI002 as a self-join, nine indexes chosen by reading the queries. **The indexes made it slower.** |
| `10000000-...-AFTER-TUNING.json` | 10M | 2,259 ms | **1,069 ms** | DI002 rewritten as a window function; four unused indexes (410 MB) removed. |
| `1000000-...json` | 1M | 446 ms | 195 ms | Scaling |
| `100000-...json` | 100k | 238 ms | 79 ms | Scaling |

The BEFORE file is kept deliberately. A speedup claim is worth more when the failed attempt is
still on disk next to it, and that run is the reason the benchmark measures both an untuned and a
tuned pass instead of just reporting the tuned one.

Untuned numbers differ slightly between the two 10M runs because each run re-seeds; the data is
deterministic but the page cache and the machine's background load are not. The variation is
within the run-to-run spread recorded in each file.
