# Design and extraction

## Cohesion before patterns

Keep one responsibility and one level of abstraction in a callable. An
orchestration method can name the steps of a use case while collaborators own
validation, domain decisions, transformations, and I/O.

Split when responsibilities change, not at an arbitrary line boundary. Do not
replace a large method with a sequence of vaguely named helpers such as
`processPartOne` and `handleData`.

Prefer the simplest current solution. Reuse a domain concept when its meaning
is actually shared; similar syntax alone does not justify a general framework.
Use composition instead of inheritance unless a real subtype contract exists.

## Size and complexity review

Use the defaults in `ub-quality/SKILL.md` during the completion self-check.
Code lines exclude blank and comment-only lines. Cyclomatic complexity counts
independent decision paths using the configured analyzer's definition when one
exists; otherwise inspect branching and label any count as an estimate.
Nesting means nested control-flow structures within a callable, not the
surrounding class/module or data-literal indentation.

Consider generated code, declarative tables, cohesive algorithms, and clear test
scenarios individually. A longer linear declaration or scenario can justify an
exception; a method mixing policy, persistence, and response assembly cannot be
justified solely because splitting it takes work.

When touching an existing violation, avoid increasing its size or complexity.
If the requested behavior inherently needs more work, extract a meaningful
responsibility or explain the concrete exception. Leave unrelated legacy
cleanup outside the task.

## Naming versus inlining

Introduce a local name when it explains meaning, exposes units or policy,
separates a substantial transformation, or prevents repeated work. Avoid
temporary names that merely repeat an accessor.

For example, prefer an explicit decision:

```ts
const matchesSelectedAccount = movement.accountId === selectedAccountId;
const canReconcile = matchesSelectedAccount && !movement.excluded;
```

over an opaque condition repeated inside several nested callbacks. Simple
expressions such as `render(user.displayName)` can remain inline.

Extract a callback when it contains branching, side effects, nested transforms,
or enough logic that its caller becomes difficult to read. A one-line
`items.map(item => item.id)` does not need a separate helper.

Use named domain constants for opaque policy values or repeated meaningful
literals. Keep ordinary loop counters and obvious local literals readable.
Fixed model instructions belong in purpose-named resources or identifiers;
user-supplied data remains data.

## Architecture and boundaries

- Keep transport and persistence details at their boundaries. Domain policy
  should not require a controller, browser component, or database session to
  explain its decisions.
- Make dependencies explicit. Prefer a focused collaborator to a global
  service locator or a generic dependency bag.
- Use immutable values and pure transformations where they clarify state.
  Keep ownership and mutation explicit when mutable state is necessary.
- Validate untrusted input at entry points and use meaningful return/error
  types. Preserve failure causes and clean up resources.
- Handle concurrency, cancellation, timeouts, and retries deliberately when
  the task involves them. Avoid blanket catch-and-continue behavior.
- Add a reusable abstraction after identifying the shared contract, not in
  anticipation of hypothetical future consumers.
- Use supported runtime/framework features. Upgrades and compatibility
  changes require a task-relevant reason, not a blanket latest-version rule.

## Verification

Test observable behavior, important boundaries, and failure paths proportional
to the change. Do not add tests that only mirror private implementation steps
or restate compiler guarantees. Run existing relevant checks yourself; add
tooling or benchmarks only when the task actually calls for them.
