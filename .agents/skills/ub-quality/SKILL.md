---
name: ub-quality
description: Guide code generation, edits, refactoring, and review toward cohesive methods, clear names, typed boundaries, and simple maintainable architecture. Apply alongside the relevant language or framework skill, including for repository scripts and embedded code. Also use for documentation structure and readable technical explanations. Ordinary use performs a self-check; it does not invoke the separate ub-review repair cycle.
---

# UB Quality

## Core coding contract

Apply this baseline before writing or reviewing code. The user does not need
to invoke it explicitly when repository instructions route the task here.

- Give each method one cohesive responsibility and keep one level of abstraction
  at a time. Separate transport, orchestration, business policy, and persistence
  when their responsibilities differ.
- Aim for 20–30 code lines per method. Before finishing, inspect new or changed
  callables against defaults of 50 nonblank/noncomment code lines, cyclomatic
  complexity 10, and control-flow nesting depth 3.
- Refactor a violation or explain a concrete reason to retain it. These are
  generation and review defaults; do not claim measurements or automated gates
  unless an actual configured tool produced them.
- Use guard clauses and named decisions to flatten complicated control flow.
  Do not compress statements, move complexity into anonymous callbacks, or
  create meaningless helpers merely to meet a size target.
- Name intermediate results when they explain an opaque expression, preserve a
  meaningful domain concept, or make a substantial transformation readable.
  Keep obvious property access, simple arguments, and straightforward
  expressions inline.
- Extract complex callbacks, repeated domain policy, substantial payload
  construction, and calculations that deserve independent names or tests.
  Keep helpers close to their callers until they have a real reuse boundary.
- Name meaningful domain constants and fixed model instructions. Keep runtime
  configuration external where appropriate. Do not turn every literal into a
  constant or move dynamic user/document data into instruction resources.
- Use typed contracts at boundaries; validate external data before trusting it.
  Keep dependencies explicit, state ownership clear, and side effects visible.
- Prefer composition and existing project patterns. Add an interface, layer,
  factory, or dependency only when it solves a concrete current problem.
- Preserve behavior and unrelated work. Improve touched legacy code
  incrementally without increasing existing size or complexity debt.
- Let configured formatters own whitespace. Use idiomatic readable formatting
  when none exists. Separator alignment and fold regions are optional.
- Verify relevant behavior using the project's existing checks yourself;
  report material verification gaps without requiring the user to run commands.

## References by task

Load only references that affect the work, before producing dependent output:

- [Design and extraction](references/design-patterns.md): implementation,
  architecture, abstraction, dependency, and refactoring decisions.
- [Formatting](references/formatting-alignment.md): source/configuration layout
  or examples, including embedded script and style blocks.
- [Documentation and structure](references/documentation-structure-refactoring.md):
  documentation, public contracts, comments, file organization, or large files.
- [Tags and generated documents](references/important-tags-and-doc-generation.md):
  special comment tags or generated documentation.
- [Response readability](references/response-readability.md): substantial
  explanations, plans, reviews, or comparisons.
- [Freshness and portability](references/freshness-portability.md): distinctions
  between repository requirements, house defaults, and portable guidance.

Language and framework skills own their native idioms and runtime constraints.
Repository instructions and real configuration determine what is active.

## Completion self-check

Perform this internally during ordinary coding:

1. Compare the implementation with the original request and accepted
   clarifications. Check omissions, scope drift, and changed behavior.
2. Inspect touched callables for mixed responsibilities, size, decision paths,
   nesting, dense expressions, duplication, and hidden side effects.
3. Check boundary types, dependency direction, error handling, cancellation or
   resource cleanup where relevant, and meaningful names.
4. Check formatting against real configuration and preserve prompt text or
   other whitespace-sensitive data.
5. Run relevant existing checks and fix introduced failures within scope.
   State when checks could not run; never report inspection as executed tests.
6. Report the result, relevant validation, and material retained debt or
   justified exceptions concisely.

This self-check stays within the ordinary task. It does not load `ub-review`,
spawn reviewer/fixer agents, or begin an iterative repair cycle.

## Decisions and output

For a meaningful unresolved design choice, compare the viable approaches and
recommend the simplest one that meets the constraints. Do not invent
alternatives or reopen decisions the user has already settled.

Let names and code explain mechanics; use comments for rationale and constraints.
Keep short answers short. Use structure only when it helps readers assess the
result, verification, and any remaining issues.
