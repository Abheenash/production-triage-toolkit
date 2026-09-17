# Running this in production

The tool is a single jar that opens one read-only connection, prints, and exits. Everything below
is about the boring parts that decide whether a scheduled diagnostic is useful at 3am or ignored
by the end of the week.

Every artifact referenced here is in [`deploy/`](../deploy), and all of them are validated in CI:
the systemd units with `systemd-analyze verify`, the Kubernetes manifests with `kubeconform` in
strict mode, and the Terraform with `terraform validate` and `terraform fmt -check`.

## The database role it should connect as

Make a role that cannot write even if every other control failed. This is the layer that does not
depend on the tool being correct.

```sql
CREATE ROLE triage_readonly LOGIN PASSWORD '...';

GRANT CONNECT ON DATABASE bookings TO triage_readonly;
GRANT USAGE ON SCHEMA public TO triage_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO triage_readonly;

-- Tables created later must be covered too, or the check that matters most is the one that
-- silently starts failing six months from now.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO triage_readonly;

-- The five database-health checks read pg_stat_activity. Without this the role sees only its own
-- sessions, so DBH001-DBH003 and DBH005 report nothing and look perfectly healthy -- the exact
-- silent failure this project exists to prevent. Grant the monitoring role, not superuser.
GRANT pg_monitor TO triage_readonly;

-- Belt and braces: even a bug in this tool cannot write as this role.
ALTER ROLE triage_readonly SET default_transaction_read_only = on;

-- Bound anything this role runs, independently of the tool's own --timeout-ms.
ALTER ROLE triage_readonly SET statement_timeout = '30s';
ALTER ROLE triage_readonly SET idle_in_transaction_session_timeout = '60s';
```

`pg_monitor` is the one grant people miss. Without it the tool runs, exits 0, and tells you
nothing is wrong with a database it cannot actually see.

## Exit codes are the integration

| Code | Meaning | What a scheduler should do |
|---|---|---|
| 0 | Ran, nothing found | Nothing |
| 1 | Ran, found problems | Record it; alert per your severity policy |
| 2 | Could not complete | **Alert.** The result is not trustworthy |

The trap every scheduler falls into is treating exit 1 as a job failure. It is not: it is a
successful run that found something. A cron line or a Kubernetes Job that retries on exit 1 will
retry forever against a database that has a real problem, and the alerts get muted within a week.

Every artifact in `deploy/` handles this explicitly -- `SuccessExitStatus=0 1` in the systemd unit,
`backoffLimit: 0` in the CronJob, `maximum_retry_attempts = 0` in the EventBridge target.

Use `--fail-on HIGH` when you want exit 1 reserved for things worth waking someone for. The
findings below that threshold are still reported; only the exit code changes.

## Linux and Unix hosts

### systemd (preferred)

[`deploy/triage.service`](../deploy/triage.service) and
[`deploy/triage.timer`](../deploy/triage.timer).

```bash
sudo cp deploy/triage.service deploy/triage.timer /etc/systemd/system/
printf 'PGPASSWORD=...\n' | sudo tee /etc/triage/triage.env
sudo chown root:triage /etc/triage/triage.env && sudo chmod 640 /etc/triage/triage.env
sudo systemctl daemon-reload
sudo systemctl enable --now triage.timer

systemctl list-timers triage.timer     # when it next fires
journalctl -u triage -n 50             # the run history
```

Chosen over cron for reasons that show up in practice rather than on paper:

- **`Persistent=true`** runs a schedule missed while the host was down. Cron simply skips it, and
  the gap is invisible.
- **`RandomizedDelaySec=300`** stops a fleet connecting at exactly :00. Fifty hosts running a
  diagnostic simultaneously is a load spike against the database you are trying to observe.
- **`SuccessExitStatus=0 1`** keeps "found problems" out of `systemctl --failed`.
- **journald** means `journalctl -u triage` is the history. Cron's answer is mail nobody reads.

The unit is also locked down well past what a read-only diagnostic needs -- `ProtectSystem=strict`,
an empty `CapabilityBoundingSet`, an empty `ReadWritePaths`, `SystemCallFilter=@system-service`.
It opens a TCP socket and writes to stdout; it should be able to do nothing else.

### Plain cron

[`deploy/crontab.example`](../deploy/crontab.example), for hosts without systemd. Two failure
modes it defends against, both of which are silent:

- Cron runs with a near-empty environment, so `java` is usually not on `PATH` and the job fails at
  3am with no output anyone sees. `PATH` is set explicitly.
- The wrapper alerts only on exit 2, so a database with findings does not generate a mail every
  hour until the rule gets filtered.

The password comes from a root-owned env file, never from the crontab: crontabs are readable by
more people than their authors expect.

## Kubernetes

[`deploy/kubernetes-cronjob.yaml`](../deploy/kubernetes-cronjob.yaml).

```bash
kubectl -n triage create secret generic triage-db --from-literal=PGPASSWORD='...'
kubectl apply -f deploy/kubernetes-cronjob.yaml
kubectl -n triage get cronjob triage
kubectl -n triage logs job/<name>
```

The settings that matter are the failure semantics, for the same reason as above:

- `backoffLimit: 0` -- exit 1 means findings, and a retry finds them again.
- `concurrencyPolicy: Forbid` -- a run that overruns skips the next rather than stacking two
  diagnostics against one database.
- `activeDeadlineSeconds: 300` -- a hung run must not hold the schedule open.
- `cpu: "1"` -- taken from the measurement, not guessed: a full run at 10 million bookings takes
  1,228 ms on a single core. See [benchmark.md](benchmark.md).

The pod runs non-root with a read-only root filesystem, all capabilities dropped, and no service
account token mounted. It needs none of them.

## AWS

[`deploy/aws/main.tf`](../deploy/aws/main.tf) -- EventBridge Scheduler invoking an ECS Fargate
task, with a CloudWatch metric filter on `exitCode: 2` and an alarm on it.

Two decisions worth stating:

**Fargate rather than Lambda.** Lambda is the reflex for "small thing on a schedule", but this is
a JVM process that already finishes in about a second of real work, so a cold start on every
invocation is a poor trade, and per-invocation pricing is not cheaper at one run an hour. The
deciding factor is that an ECS task's exit code maps directly onto something EventBridge and
CloudWatch can alarm on, with no wrapper to write and maintain.

**The task role is empty.** Not minimal -- empty. The tool makes no AWS API calls at all: it opens
a database connection and writes to stdout. The *execution* role has exactly two permissions, pull
the image and read the secret, and those belong to the ECS agent rather than to the process.

The alarm watches for exit code 2 only. A run that finds problems is working correctly; a run that
could not complete is the thing that silently stops protecting you.

> **Status: validated, not deployed.** `terraform validate` and `terraform fmt -check` run in CI,
> so the configuration is known to be correct. It has not been applied, because a scheduled
> Fargate task and its log group cost money every hour whether or not anyone is reading the
> output, and this project does not need to be running continuously to demonstrate anything. The
> same build-and-prove-then-tear-down posture is used in
> [aws-eks-platform](https://github.com/Abheenash/aws-eks-platform). Applying it needs an image in
> ECR, private subnets that can reach the database, and the secret ARN.

## Consuming the output

```bash
# Everything, one JSON object per run, appended.
java -jar triage.jar --format json --no-color >> /var/log/triage.jsonl

# What an alert needs, and nothing else.
java -jar triage.jar --format json | jq -r '.findings[] | "\(.severity) \(.checkId) \(.matchCount) \(.runbook)"'

# Did anything critical appear?
java -jar triage.jar --format json | jq -e '.summary.bySeverity.CRITICAL > 0' >/dev/null && page-oncall

# Grounded context for an assistant, when you want a first-pass hypothesis fast.
java -jar triage.jar --format prompt | pbcopy
```

`passed` is included in the JSON on purpose. "DI003 ran and found nothing" and "DI003 is absent
because it crashed" must not look the same to whatever is consuming this, which is the same
principle as exit code 2.

## What to watch for once it is running

- **Exit code 2 more than once.** Something is wrong with the tool's access or the database's
  health, and until it is fixed the green runs mean nothing.
- **A check's duration growing.** `durationMs` is per check in the JSON. Steady growth usually
  means an index was dropped or a table outgrew its plan.
- **A finding count that only ever grows.** Nobody is working the runbook.
- **Findings that vanish without anyone acting.** Usually means a threshold was widened, not that
  the problem was fixed.
