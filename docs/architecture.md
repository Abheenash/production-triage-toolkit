# How it works

Small enough to hold in your head, which is deliberate: a tool that gets pointed at production
should be one you can read in an afternoon.

```
                  triage --host db --check DI002 --format json
                                    |
                     +--------------v---------------+
                     |        TriageCommand         |   picocli: parse, validate,
                     |  (cli/)                      |   pick checks, pick reporter
                     +--------------+---------------+
                                    |
              +---------------------+---------------------+
              |                                           |
   +----------v-----------+                   +-----------v-----------+
   |   CheckCatalog       |                   |   ConnectionFactory   |
   |   (core/)            |                   |   (db/)               |
   |                      |                   |                       |
   | 15 CheckSpecs        |                   | read-only session     |
   | loads .sql resources |                   | verified, not assumed |
   | substitutes          |                   | named in              |
   |   thresholds         |                   |   pg_stat_activity    |
   | SqlSafety.assert     |                   | password from env     |
   |   ReadOnly           |                   |   only                |
   +----------+-----------+                   +-----------+-----------+
              |                                           |
              +---------------------+---------------------+
                                    |
                     +--------------v---------------+
                     |        TriageRunner          |   one short read-only
                     |        (core/)               |   transaction per check,
                     |                              |   SET LOCAL statement_timeout,
                     |                              |   always rolled back
                     +--------------+---------------+
                                    |
                     +--------------v---------------+
                     |          RunReport           |   rank by severity, then
                     |          (core/)             |   match count, then id;
                     |                              |   derive the exit code
                     +--------------+---------------+
                                    |
                     +--------------+---------------+
                     |                              |
           +---------v---------+        +-----------v---------+
           |   TextReporter    |        |    JsonReporter     |
           |   (report/)       |        |    (report/)        |
           +-------------------+        +---------------------+
                                    |
                          exit 0 / 1 / 2
```

## Where the SQL lives, and why it is not in Java

Each check is a `.sql` file on the classpath, not a string constant. Three reasons, in order of
how much they matter:

1. **Somebody on call has to be able to read it.** A check nobody can read is a check nobody
   trusts, and `--show-sql DI002` prints the exact text, thresholds already substituted, ready to
   paste into psql.
2. **It can be reviewed as SQL.** Diffs on a `.sql` file are legible; diffs on a Java text block
   are not.
3. **It can be scanned.** `SqlSafety` runs over the file at load time, and a test runs it over all
   fifteen at build time.

`CheckCatalog` holds the metadata -- severity, group, escalation threshold, runbook path, the
one-line description of what a user notices -- because those are decisions about the check rather
than part of the query.

## The wrapper query

Each check's SQL is wrapped before it runs:

```sql
WITH __triage_finding AS MATERIALIZED (
    <the check>
)
SELECT (SELECT count(*) FROM __triage_finding) AS __total_count, __triage_finding.*
FROM __triage_finding
LIMIT ?
```

One round trip returns both the exact number of matching rows and a bounded sample.

`MATERIALIZED` is not decoration. Without it PostgreSQL may inline the CTE into both references
and evaluate the check twice, which would double the cost of the most expensive thing the tool
does. With it, the check runs once, is counted, and is sampled.

The count is over the whole result while `LIMIT` bounds only what comes back. Reporting "5 rows
found" because the sample cap happened to be 5 would be worse than useless during an incident.

## Safety, in four layers

The objective "safe to run against production at any time" is the one that would do real damage if
it were merely believed, so it does not rest on a single mechanism.

| Layer | Where | What it stops |
|---|---|---|
| Static scan | `SqlSafety`, at load time | A check containing a write, a second statement, or a function like `pg_terminate_backend` |
| Read-only session | `ConnectionFactory` | Any write on this connection, whatever the SQL says |
| Verified, not assumed | `ConnectionFactory.verifyReadOnly` | A driver, pooler or future edit that silently loses the guarantee -- it asks the server `SHOW transaction_read_only` and closes the connection if the answer is not `on` |
| Statement timeout | `TriageRunner`, `SET LOCAL` per check | A check that would hold resources on a struggling server |

Each is individually sufficient. Having four is the point.

Two smaller things in the same spirit: `lock_timeout` is set to one second, so a diagnostic never
joins the lock queue it is reporting on; and every transaction is rolled back and short, so the
tool reporting DBH005 can never itself be the idle-in-transaction session.

`SafetyIT` tries to break each layer -- attempting writes, handing the guard a genuinely writable
session, running a query built to be slow -- and a checksum test proves a full run changes nothing.

## Exit codes, and the one distinction that matters

| Code | Meaning |
|---|---|
| 0 | Every selected check ran and found nothing at or above `--fail-on` |
| 1 | Every selected check ran; at least one found something |
| 2 | The run was incomplete: bad arguments, no connection, or a check that failed or timed out |

A check that could not run **outranks every finding**, so one broken check produces exit 2 even
alongside a CRITICAL finding. "I looked and found problems" and "I could not look" must never be
the same signal, or a broken deployment of the toolkit reads as a healthy database.

The same rule shapes the JSON: passed checks are listed explicitly, so "DI003 ran and found
nothing" is distinguishable from "DI003 is missing from the output".

## Thresholds

Eight numeric knobs, substituted into the SQL as `${name}` before the statement is prepared.

Interpolating text into SQL is normally how injection happens, so the rules are narrow: the name
must already exist in `Thresholds.DEFAULTS`, and the value must parse as a non-negative number.
Anything else is rejected before it reaches the database, and `SqlSafety` re-scans the assembled
statement afterwards. `--threshold 'booking_window_days=7; DROP TABLE bookings'` fails with "must
be a number", and there is a test for it.
