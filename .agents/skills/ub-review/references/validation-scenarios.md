# Validation scenarios

Use isolated fixtures for behavioral authoring checks. Do not activate the
application review cycle merely because the user asks to create or test this
skill. Do not add a runtime framework, command-based harness, or production
changes for these checks.

## Routing

1. **Ordinary coding:** “Add a small Java endpoint.” Apply `ub-quality` and
   `ub-java` automatically; perform the normal self-check without `ub-review`.
2. **Angular work:** “Fix this Angular loading state.” Apply `ub-quality`,
   `ub-ts`, and `ub-angular`; load CSS only for actual styling changes.
3. **Read-only review:** “Review this diff; do not edit.” Report findings without
   starting repairs or loading the cycle skill.
4. **Authoring:** “Implement/test the review skill.” Change or validate the
   instruction artifacts; do not review and repair the application.
5. **Explicit cycle:** “Run the review cycle against my original request,” or a
   direct `ub-review` invocation. Establish the contract and delegate roles.

## Cycle transitions

6. **Clean first pass:** A reviewer finds no actionable issue with adequate
   evidence. Stop at Review 1 without creating a fixer or unnecessary edits.
7. **Repair verified:** Review 1 finds a concrete mismatch; Repair 1 corrects
   it; Review 2 independently verifies it and finds no other issues. Stop.
8. **Disputed repair:** A fixer claims a finding is invalid or fixed. The next
   reviewer checks the actual code/evidence and retains the ID until verified
   as fixed or dismissed with evidence as a mistaken finding. Dismissal does
   not count as a repair or user acceptance.
9. **Regression/new issue:** Review 2 finds an introduced regression or a new
   original-scope issue. Repair 2 addresses it; do not reset the cycle budget.
10. **Persistent issue:** Review 3 still finds an actionable issue. List it and
    ask for acceptance or restart; never launch Repair 3 automatically.
11. **Acceptance:** The user accepts outstanding issues. Record accepted status
    in the chat report, stop, and do not claim they were fixed or publish code.
12. **Restart:** The user explicitly restarts. Carry forward the contract and
    unresolved IDs; reset only the per-cycle three-review/two-repair budget.

## Boundaries and evidence

13. **Unrecoverable request/scope:** Ask for missing context before review;
    do not use a commit message or unrelated dirty files as the requirement.
14. **Unavailable subagents or read-only mode:** Explain the limitation and
    perform only permitted work; never simulate independent repair by self-edit.
15. **Unrelated changes:** Read dependencies when needed, but do not fix another
    task's files without authorization.
16. **Blocked check:** Distinguish “not run” from pass/fail; do not claim a
    fully verified clean outcome when material evidence remains unavailable.
    If no actionable correction can address the remaining verification blocker,
    stop incomplete without creating an empty repair round or resetting budget.
17. **No user response:** Remain stopped. Silence does not authorize restart.
18. **Style preference:** Reject an unsupported preference as an actionable
    defect; the reviewer must identify a violated contract or concrete harm.

Validate metadata and reference links separately from behavioral scenarios.
Report which scenarios were actually exercised and which were only inspected.
