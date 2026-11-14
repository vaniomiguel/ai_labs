# Documentation, structure, and refactoring

## Information hierarchy

Use code and names for mechanics, types for shapes and contracts, documentation
for usage boundaries and invariants, and comments for non-obvious rationale.

Document public, reusable, risky, or domain-heavy callables when a future reader
would otherwise need to reconstruct their contract. Include only useful
parameters, return semantics, errors, constraints, or examples. Trivial private
helpers do not need prose that repeats their names.

Explain business constraints, units, surprising transformations, and deliberate
tradeoffs close to the relevant code. Preserve useful issue tags and remove
comments that merely narrate obvious operations.

## File and module organization

Group code by responsibility and domain. Keep related declarations and helpers
near their callers using the language's idiomatic order. Group imports according
to the formatter or project conventions; annotate only unusual side effects or
dependencies whose role is not evident.

Split a file when independent responsibilities, state ownership, or dependency
boundaries make it difficult to understand. Do not wait for a fixed file-length
threshold, and do not create tiny files that fragment a cohesive concept.

Prefer decomposition over mandatory banners or fold markers. Regions are
optional navigation aids for a format/editor that supports them. Preserve or
rebalance existing useful regions when moving code, and never put comments into
strict JSON.

## Refactoring triggers

- A function mixes orchestration, policy, transport, storage, and presentation.
- A callback hides branching, side effects, or nested transformations.
- An expression or payload requires horizontal reconstruction to understand it.
- A boolean decision or calculation lacks a meaningful domain name.
- A class accumulates unrelated state or collaborators.
- Repeated code represents the same policy rather than merely similar syntax.

Use the size/complexity defaults and extraction criteria in
[the design reference](design-patterns.md). Extract a named responsibility,
not an arbitrary range of lines. Keep straightforward expressions inline.

## Safe incremental changes

1. Establish the requested behavior and relevant existing checks.
2. Preserve interfaces, data shapes, failure semantics, and prompt composition
   unless changing them is part of the task.
3. Make focused edits and preserve unrelated working-tree changes.
4. Improve touched legacy structure without worsening existing size/complexity
   debt. Report concrete exceptions or material deferred issues.
5. Verify behavior after meaningful changes and review the final diff.

Do not turn a focused fix into an unsolicited architecture migration.
Avoid compatibility wrappers without a real supported-platform requirement.
Use specific errors, cleanup, and diagnostic context where they improve the
actual failure path; entry/exit logging is not a default requirement.

## Output

Describe what changed and why, relevant verification, and material limitations.
Do not generate separate manuals, summaries, or refactoring reports unless the
user requested them or they are necessary deliverables of the task.
