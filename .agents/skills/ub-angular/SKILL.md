---
name: ub-angular
description: Guide Angular component, template, service, dependency-injection, state, and test design during code generation, edits, refactoring, and review. Apply with ub-quality and ub-ts when the target uses Angular. Do not activate for plain browser JavaScript, Vue/Nuxt, or TypeScript compiler work without Angular concerns.
---

# UB Angular

## Automatic application

Apply with `ub-quality` and `ub-ts` for Angular work without requiring a user
skill invocation. Inspect the installed Angular version, compiler options,
component patterns, API services, and tests before choosing APIs.

Preserve framework-supported decorator and dependency-injection semantics.
Do not replace Angular's compiler configuration with a generic TypeScript
starter, or migrate signals, RxJS, forms, or component style incidentally.

## Responsibilities and state

- Keep components focused on presentation and interaction. Move transport to
  typed API services and substantial business transformations to named
  functions or focused services with a concrete responsibility.
- Split components when independently understandable UI/state responsibilities
  emerge. Do not create a service for every handler or a global store for
  purely local state.
- Keep event handlers and lifecycle methods small. Separate request selection,
  loading, response transformation, state updates, and error presentation when
  a method otherwise becomes a mixed-responsibility workflow.
- Give each piece of state a clear owner. Prefer derived values to duplicated
  state that must be synchronized manually; use the project's established
  signal/RxJS patterns and maintain cancellation and cleanup.
- Represent loading, success, empty, and failure states deliberately. Prevent
  stale async responses from overwriting newer state where requests can race.
- Use typed inputs, outputs, API contracts, and component state. Narrow unknown
  boundary values rather than spreading `any` or unchecked assertions through
  templates and business logic.

## Templates and browser behavior

- Keep templates declarative. Name complex conditions and transformations;
  avoid repeated expensive work or side effects in template expressions.
- Preserve strict template checking and the installed framework's supported
  control-flow, forms, and tracking patterns.
- Preserve keyboard operation, labels, focus behavior, and meaningful feedback
  for loading or errors. Use native HTML controls when appropriate.
- Apply `ub-css` only to actual styling work and `ub-tailwind` only when the
  target task uses Tailwind. Preserve the current design/token system.

## Example extraction boundary

An event handler may select a request, await an API service, and update a
well-typed view state. If it also assembles several payload variants, reconciles
records, computes counters, and formats domain errors, extract those meaningful
steps. A direct assignment or a simple `items.map(item => item.id)` can remain
inline.

## Verification and completion

Run the relevant existing Angular build/type/template checks and configured
component or browser tests yourself. Use public interaction and rendered
outcomes to verify behavior. Check loading/error paths and asynchronous state
when they changed; do not add a new test framework merely to use this skill.

Apply the shared method-size and complexity self-check. Report material
retained debt or verification gaps, without launching the `ub-review` cycle.

## Sources

Use official guidance matching the installed Angular version:

- [Angular style guide](https://angular.dev/style-guide)
- [Dependency injection](https://angular.dev/guide/di)
- [Signals](https://angular.dev/guide/signals)
