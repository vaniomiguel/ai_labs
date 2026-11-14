---
name: ub-python
description: Guide Python code generation, edits, refactoring, and review, including function design, typing, boundaries, tests, packaging, and configured tooling. Apply with ub-quality for Python source, dataclasses/Pydantic, scripts, and services. Preserve the project's interpreter and dependency contract; do not use it as the primary owner of non-Python framework or workflow work.
---

# UB Python

## Automatic application

Apply with `ub-quality` before Python work when repository instructions route
here. Inspect the target's interpreter requirement, project metadata, lockfiles,
entrypoints, and tests. A document worker, evaluation script, and repository
helper can have different environments; do not assume system `python3` is the
supported interpreter for all of them.

Use supported runtime/library APIs and preserve behavior. Consult matching
official documentation for version-sensitive choices. A coding request does
not authorize runtime upgrades, new quality tooling, or a packaging migration.

## Functions and data flow

- Apply the shared cohesion, size, complexity, and nesting defaults before
  finishing. Split parsing, policy, transformation, I/O, and report assembly
  when they represent separate responsibilities.
- Keep comprehensions readable. Extract branching or nested transformations
  into named functions, or use a clear loop; do not put a workflow into a
  comprehension or callback to shorten its apparent line count.
- Name intermediate values that explain calculations, units, decisions, or
  substantial payloads. Keep simple expressions inline.
- Keep helpers close to callers until they have a real reuse boundary. Prefer a
  function or small value type to a class that only holds arbitrary helpers.
- Type non-trivial signatures and known contracts. Keep `Any` near genuinely
  dynamic library/input boundaries and narrow before domain use.
- Prefer dataclasses for internal values and existing Pydantic models or explicit
  validation at external boundaries. Do not add a model library for a trivial
  internal container or spread untyped dictionaries where the contract is known.

## Native correctness

- Use type syntax supported by the actual interpreter floor; newer aliases and
  typing features require that floor or an appropriate existing backport.
- Avoid mutable defaults and hidden module-level state ownership. Prefer
  immutable values when mutation has no domain purpose.
- Use context managers for resources, specific exceptions, and causal chaining.
  Avoid catch-all recovery that obscures failures.
- Keep blocking I/O out of an async event loop; preserve cancellation, timeouts,
  bounded concurrency, and cleanup in asynchronous flows.
- Validate untrusted HTTP/file/environment inputs at entry points. Preserve
  encoding, precision, serialization, and public error semantics during refactors.
- Keep fixed instructions in named resources/identifiers and preserve prompt
  composition; user/document content remains input data.

## References by task

- [Python standards](references/python-standards.md): relevant native typing,
  model, function, and boundary guidance.
- [Repository workflows](references/repository-python-workflows.md): interpreter
  resolution and existing packaging/check entrypoints.
- [Ruff config resolution](references/ruff-config-resolution.md): when actual
  lint configuration or explicitly requested scaffolding is involved.
- [Task bundle](references/task-bundle.md): only when the user requests that
  optional automation overlay.
- [Shared authoring](../ub-authoring/references/authoring-conventions.md):
  changes to skill routing or shared authoring behavior.

Load only references that affect the current work. Existing Ruff assets and
`scripts/scaffold_ruff.py` are optional starters; do not run scaffolding, install
packages, add hooks/CI, or generate configs merely because this skill applies.
A `[build-system]` is needed when packaging/building a distribution, not for
every standalone script.

## Existing checks and completion

Run relevant existing checks yourself using the project's interpreter or
environment. Use configured Ruff/type checks when present and focused pytest
or unittest coverage for changed behavior. Start narrow and broaden for new
failures or affected boundaries.

When tooling is absent or blocked, state material verification limits without
claiming a gate exists or asking the user to run commands. Propose tooling setup
only when requested or necessary to complete the actual task.

Perform the `ub-quality` self-check, preserve unrelated changes, and avoid
worsening touched legacy size/complexity debt. Explain material exceptions,
behavioral results, and checks run or blocked concisely. Ordinary completion
does not invoke `ub-review`.
