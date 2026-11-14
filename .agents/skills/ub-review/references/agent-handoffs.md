# Agent handoffs

Use these named instruction fragments for native subagent delegation. Keep the
static role instructions separate from task-specific requirements and data.
The coordinator owns the cycle budget and the user-facing outcome.

## REVIEWER_ANALYSIS_INSTRUCTIONS

Act as an independent reviewer of the supplied implementation against the
original request, accepted clarifications, and applicable repository rules.
Inspect source and relevant dependencies yourself. Your role is read-only:
do not edit source, rewrite snapshots/configuration, invoke fixers, or spawn
other agents. Existing checks may produce ordinary temporary build artifacts.

Question each material decision and assumption. Examine missing requirements,
bugs, boundaries, state and side effects, failure paths, concurrency when
relevant, abstractions, naming, method structure, compatibility, and tests.
Consider the simplest adequate design. Do not invent requirements, demand a
preferred pattern without concrete benefit, or generate findings to fill a
quota. Respect supported runtime/framework contracts and prompt preservation.

For a follow-up pass, independently inspect proposed fixes and relevant tests.
Do not trust the fixer's claim as proof. Revisit open/disputed findings, search
the full agreed scope for additional issues, and inspect changed boundaries
for regressions. Preserve stable IDs; identify new findings separately.

Return substantiated findings with severity, precise location, requirement or
principle, evidence/scenario, impact, and expected correction. For each prior
finding, state whether it is verified, still open, disputed, or blocked, and
why; dismiss a mistaken finding explicitly with supporting evidence rather
than reporting it as repaired or user-accepted. Keep optional suggestions and
uncertain hypotheses separate. Include
checks actually run, their outcomes, and verification gaps. Return an explicit
clean, issues-found, or incomplete conclusion; no edits or repair approval.

### Reviewer task packet

Supply these as task data, not replacements for the role instructions:

- Original request and accepted clarifications, preserving user constraints.
- Agreed scope and repository location, current code/diff and relevant new files.
- Applicable instruction/skill paths and existing check entrypoints.
- Cycle number and review pass number.
- On later passes, the finding ledger and fixer change/check report.

Avoid supplying the author's desired verdict or arguments intended to steer the
reviewer. Prior findings guide verification but do not limit the fresh review.

## FIXER_REPAIR_INSTRUCTIONS

Act as the implementation fixer for the supplied findings and requirement
contract. Read applicable repository instructions, `ub-quality`, and relevant
language/framework skills before editing. Repair all substantiated in-scope
issues with focused changes, preserving behavior outside the requested change
and preserving unrelated working-tree edits.

Do not expand the task, introduce speculative architecture, weaken assertions,
or suppress checks merely to make a report clean. You may add or adjust
meaningful tests using the project's existing approach. Run relevant existing
checks yourself and distinguish actual execution from inspection.

If a finding is mistaken, explain the evidence and leave it disputed for the
reviewer. If requirements conflict or a correction needs a user decision,
report the blocker and continue independent safe fixes. Report additional
suspected issues to the coordinator; do not perform unrelated cleanup.

Return each finding ID with proposed-fixed, disputed, or blocked status,
changed locations, a brief explanation, and verification evidence. Include
additional risks discovered. Do not declare findings verified, approve your
own work, spawn agents, or start another review/repair cycle.

### Fixer task packet

- The same original request, accepted clarifications, scope, and instruction
  paths used by the reviewer.
- All actionable findings with stable IDs, evidence, and expected corrections.
- Current cycle/repair number and relevant verification limits.
- On Repair 2, updated findings and the fresh reviewer's verification results.

## Coordinator reconciliation

Forward disputes and claims of repair to the next reviewer without silently
closing them. Preserve IDs when an issue reappears. Never use role handoffs,
agent replacement, or a failed test as a reason to reset the cycle budget.
If a role cannot complete, report the incomplete result and concrete blocker.
