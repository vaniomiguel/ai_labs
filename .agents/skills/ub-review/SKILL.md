---
name: ub-review
description: Run a user-requested review-and-repair cycle against the original request and accepted clarifications, using an independent reviewer and separate fixer. Invoke only when the user explicitly requests this cycle or names ub-review. Do not start for ordinary coding, a read-only review, or requests to create, edit, explain, or test the workflow itself.
---

# UB Review

## Invocation and authority

This skill is explicit-only. A user may invoke it by name or ask in chat:
“Run the review cycle against my original request.” Repository instructions
route that explicit request here by reading this file directly.

Invocation authorizes in-scope review, delegated repairs, and relevant existing
checks within the session's permissions. It does not authorize unrelated
refactoring, commits, publication, deployment, or a broader rewrite. In plan or
read-only mode, perform permitted review and report that repairs cannot run.
Do not bypass mode or permission restrictions through a subagent.

## Establish the review contract

Before delegating, recover:

1. The original user request, success criteria, and accepted clarifications.
   Later explicit user decisions govern changed requirements.
2. The current chat's implementation scope: affected files, relevant diffs,
   new files, interfaces, and behavioral dependencies.
3. Applicable repository instructions, `ub-quality`, language/framework skills,
   existing checks, and known verification limits.

Inspect the actual code, including staged/unstaged changes and relevant new
files. A repository diff alone does not establish ownership of unrelated work.
Read dependencies as needed, but restrict fixes to the agreed task.

If the original request or scope cannot be recovered reliably, ask for the
missing context before reviewing. Do not substitute commit messages or an
implementation summary for the user's requirements.

## Roles and delegation

The current agent coordinates; it does not approve its own implementation.
Use the available native subagent tools without requiring a user command or
installing an agent framework. Keep the parent's model/settings unless the
user has selected others.

- Start a fresh, independent **reviewer** for each pass. It inspects code and
  may run relevant existing checks, but must not edit source, waive findings
  for the user, or delegate further. Provide requirements and code access,
  not the author's preferred conclusion or defense of the implementation.
- Use a separate **fixer** for repairs; it must be distinct from every reviewer.
  Reuse that fixer for the second repair when useful. It may modify only the
  authorized scope and cannot approve its own fixes or launch review loops.
- Wait for each role's result before the next step. Never review a snapshot
  concurrently being repaired. If other changes invalidate a review, report
  the affected evidence as incomplete and re-establish scope before continuing.
- If independent subagents are unavailable, stop and explain the limitation.
  Do not silently replace independent review with a coordinator self-review.

Read [agent handoffs](references/agent-handoffs.md) before spawning either role.
Pass the named role instructions from that resource and a separate task packet
containing the actual requirements, scope, findings, and evidence.

## Findings and evidence

Question material decisions: requirement coverage, correctness, edge cases,
architecture, abstractions, responsibilities, naming, error handling,
compatibility, and tests. Challenge assumptions without inventing requirements,
findings, or a quota. A preference alone is not a defect.

Track each substantiated finding with:

- Stable ID, such as `R001`, retained across passes and restarts.
- Severity: critical, high, medium, or low, justified by concrete impact.
- Current file/line or a precise missing-behavior location.
- Violated requirement or applicable engineering principle.
- Evidence, triggering scenario, impact, and expected correction.
- State: open, disputed, blocked, verified, dismissed, or accepted.

Merge duplicate findings. A fixer's “fixed” response is a claim pending review;
only a reviewer can mark it verified after inspecting the code and relevant
evidence. A reviewer may dismiss a mistaken finding with a concrete reason;
dismissed means no defect was established, not that a repair occurred or the
user accepted a known issue. Keep uncertainty and optional suggestions separate
from actionable findings. Missing verification is not proof that behavior is
correct.

## Cycle budget

One cycle permits **three review passes and two repair rounds**:

1. **Review 1:** Independently inspect the contract and implementation. If no
   actionable findings remain and evidence supports the conclusion, finish.
2. **Repair 1:** Give the fixer all actionable findings and the review contract.
   It repairs in scope, runs relevant existing checks, and returns evidence.
3. **Review 2:** A fresh reviewer verifies proposed repairs, revisits unresolved
   or disputed findings, and searches for additional issues and regressions.
   Finish early if clean.
4. **Repair 2:** Give the fixer remaining and newly discovered findings, with
   the same contract and updated evidence.
5. **Review 3:** A fresh reviewer performs the final pass against the original
   contract and current code. No third repair round is authorized in this cycle.

Reappearing findings, newly introduced issues, and disputes consume the same
budget; they never reset it. Stop earlier for missing requirements, a scope or
permission decision, unavailable delegation, or a blocker that needs the user.
Report incomplete verification rather than claiming a clean outcome.

An incomplete review is not permission to launch an empty repair round. Resolve
an executable check within the existing scope when possible. If material
verification remains unavailable and no actionable correction can address it,
stop with an incomplete result, the concrete blocker, and any open findings.
Only resume on new evidence or explicit user direction; retain the cycle/pass
already reached. Never consume empty repair rounds just to reach Review 3.

## User decision and continuation

If actionable issues remain after Review 3, list them with their evidence and
impact, report verification gaps, and ask the user to choose:

- **Accept the current result:** stop with unresolved findings explicitly
  recorded as accepted in the chat report, not verified or fixed.
- **Restart the cycle:** begin a new three-review/two-repair cycle against the
  current implementation and carry forward unresolved IDs and the contract.

Use the host's question UI when available, preserving a free-text reply path.
Waiting, silence, a tool result, or the fixer's opinion is not a user decision.
Acceptance only ends this workflow; it does not commit or publish changes.
Resume only on the user's explicit restart or other clear direction.

Keep the requirement contract, current cycle/pass, findings, checks, and pending
decision in chat context and continuation summaries. Do not create report or
state files unless requested. If essential context is lost, ask rather than
resetting the budget or reconstructing requirements from guesses.

## Final report and validation

State the cycle/pass reached, verified fixes, material dismissals, unresolved
findings, and checks actually run or blocked. Say “no actionable issues found
in the reviewed scope” when justified; never claim exhaustive correctness.

Use [validation scenarios](references/validation-scenarios.md) when authoring or
testing this skill. Testing the workflow itself does not invoke it on the
application or authorize edits outside an isolated fixture.
