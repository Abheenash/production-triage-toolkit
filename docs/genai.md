# Generative AI in a production triage tool

Where it belongs, where it does not, and how the one place it does belong was built and evaluated.

## The short version

The tool has a `--format prompt` output that emits every finding, its sample rows, and the **full
text of the relevant runbook**, followed by a structured instruction to reason only from that
material. It does not call a model. The grounding is the hard part and the tool does it; the
inference is left as an explicit choice the operator makes by piping the output somewhere.

```bash
java -jar triage.jar --format prompt | pbcopy
```

## Why an assistant helps here at all

The slow part of triage is almost never the query. It is the twenty minutes spent reassembling
context at 2am: what this check means, which of the sample rows matter, what the runbook says to
do first, and -- the genuinely hard one -- whether two findings are actually one problem.

That last question is the one the tool structurally cannot answer. The fifteen checks are
independent by design, which is what makes them individually trustworthy, and it is also why
nothing in the tool knows that a stuck calendar sync (OPS001) is very often the *cause* of the
duplicate events it will find next (DI007), or that an idle-in-transaction session (DBH005) is
usually the root of both the lock queue (DBH003) and the dead tuples (DBH004). A human on-call
learns those correlations over months. A model can propose them in seconds, if it is told the
facts.

So the ask in the prompt is deliberately narrow, and item 4 is the one that earns its place:

```
  1. A two-sentence summary of what is wrong right now.
  2. The single finding to act on first, and why that one.
  3. The first three concrete steps, taken from the runbooks below.
  4. Any finding that is plausibly a CONSEQUENCE of another, and which.
  5. What you would need to see to confirm or rule out each hypothesis.
```

Items 1 to 3 are compression, which models are reliably good at. Item 4 is the correlation the
tool cannot do. Item 5 exists because a hypothesis without a falsification test is a guess, and an
on-call engineer needs to know which one they have been handed.

## Why the tool does not call a model itself

This is the decision worth defending, because adding an API call would have been easier and would
have looked more impressive.

1. **A diagnostic pointed at production must not acquire an outbound dependency.** The moment
   triage needs a third-party API to work, an outage at that provider becomes an outage in the
   ability to diagnose your own outage. The failure modes compose in exactly the wrong direction.

2. **Findings contain real row data** -- employee names, emails, badge numbers. Which service that
   may be sent to is the operator's compliance boundary, not a default a tool should pick on their
   behalf. Emitting text they choose to paste somewhere keeps that decision where it belongs.

3. **Exit codes 0/1/2 are a contract** that cron, systemd and CI depend on. Nothing
   non-deterministic belongs upstream of them. A model that occasionally rephrases a severity would
   make the exit code a coin flip.

4. **It stays testable.** `--format prompt` is a pure function of the report, so it is asserted in
   the suite like every other format -- no credentials, no network, no flake. Fourteen tests cover
   it. A format that called out to a model could not be tested this way, and in practice would be
   mocked, which tests nothing.

The general principle: **put the model where a wrong answer is cheap, and never where a wrong
answer is silent.** In a tool whose entire purpose is to be trusted about production, inference
belongs outside the trust boundary, and context assembly belongs inside it.

## The prompting practices it applies

Each of these is a deliberate choice, and each is covered by a test.

| Practice | What it does here | Why |
|---|---|---|
| **Ground, do not describe** | The full runbook text is inlined, not linked | A model given a filename invents the contents. A model given the file quotes it. |
| **Negative instruction** | "Do not invent table names, column names, remedies, or causes that do not appear here" | Hallucinated remedies during an incident are worse than no help. |
| **Name the gap** | "Say which specific piece is missing instead of guessing" | Converts a confident wrong answer into a useful request. |
| **State what is ruled out** | Passing checks are listed as "do not suggest investigating these" | Without it, a model cheerfully suggests checking things the tool already checked. |
| **Structure the output** | Five numbered asks, in priority order | An unstructured "what do you think?" produces an essay. A numbered ask produces a checklist. |
| **Carry the units** | Thresholds echoed verbatim | "Is 3 a lot?" is unanswerable without knowing 3 was compared against 30. |
| **Bound the context** | Sample rows capped at 10 per finding | 200 near-identical rows teach a model nothing 200 times, and crowd out the runbook. |
| **Declare degradation** | A missing runbook emits "do not substitute your own remedy for it" | The dangerous failure is a prompt that *looks* complete. Silence there invites invention. |
| **Flag low confidence** | Checks that could not run are marked "this run is incomplete" | A model reasoning from partial data should say so rather than conclude from absence. |

## How it is evaluated

Evaluating a prompt by reading model outputs is subjective, unreproducible, and stops being done
after the second week. So the evaluation here targets the thing that is actually deterministic:
**context completeness.**

The claim under test is not "the model gives good answers." It is narrower and checkable: *for
every question the prompt asks, is the material needed to answer it actually present?* If the
answer is no, the model must either hallucinate or refuse, and no amount of prompt tuning fixes
it. If the answer is yes, prompt quality becomes a question about the model rather than about this
tool.

`PromptCompletenessTest` asserts exactly that, question by question:

| The prompt asks for | Evidence it must therefore contain | Asserted |
|---|---|---|
| A summary of what is wrong | Every finding's title, severity and match count | yes |
| Which to act on first | Severities, counts, and user impact for each | yes |
| The first three steps | The Confirm and Fix sections of each runbook | yes |
| Which finding causes another | All findings in one prompt, plus their groups | yes |
| How to confirm or rule out | The runbook's Confirm SQL, verbatim | yes |
| (implicitly) what not to chase | The list of checks that passed | yes |

This is a weaker claim than "the output is good" and a much stronger one than "we wrote a nice
prompt", and it is the part that stays true as models change. It also fails usefully: renaming a
runbook section from `## Confirm` breaks the test, because the prompt would then promise steps it
no longer carries.

What is deliberately **not** claimed: no model-output quality benchmark was run, no A/B of prompt
variants, no measurement of answer accuracy. Those need a labelled incident corpus this project
does not have, and inventing one would produce a number that looks rigorous and means nothing.

## Where this could go, in order of value

1. **Correlation across runs, not just within one.** The prompt currently carries a single run.
   The genuinely hard on-call question is "what changed since yesterday?" -- feeding the last N
   JSON runs would let a model spot a finding count that has been climbing for a week, which no
   individual run reveals.
2. **Runbook maintenance.** Every runbook prescribes a permanent fix. A model comparing the
   runbooks against the schema could flag the ones whose fix has already been applied -- a runbook
   telling you to add a constraint that now exists is worse than no runbook.
3. **Drafting the sixteenth check.** New checks follow a tight, well-documented shape: a single
   read-only SELECT, a threshold, a severity, a runbook with four fixed sections. That is a good
   generation target *because* the guard rails are mechanical -- `SqlSafety` rejects anything that
   can write, and `CheckFiresIT` refuses to let a check ship without proving it fires. The review
   burden is real but bounded.
4. **Natural-language to check.** "Show me rooms booked over capacity next week" becoming a
   read-only query. Attractive, and the one to be most careful with: it moves generated SQL into
   the path that touches production. It would have to route through the same static write-scan and
   read-only session as everything else, with no exception, and even then the output should be
   shown for approval rather than run.

## What would be a mistake

- **A model in the read-only check path.** Non-deterministic SQL against production, for a tool
  whose selling point is that it is safe to point at production. The static scanner would catch a
  write, but "the guard rail caught it" is not an argument for driving at the guard rail.
- **A model deciding severity.** Severity drives the exit code, and the exit code drives whether
  someone is woken up. That has to be a rule a person can read, argue with, and change.
- **Summarising instead of reporting.** The sample rows are the evidence. A summary that replaces
  them removes the on-call engineer's ability to check the reasoning, which is the one thing they
  must always be able to do.
