# TypeScript Legacy-to-Modern Migration

> Verify migration paths against the latest official TypeScript documentation via web search.

## Purpose

Map legacy TypeScript patterns to modern alternatives and define bounded migration exception policy.

Prefer forward-compatible modernization paths over compatibility layers or
legacy fallbacks. When the modern replacement depends on a newer TypeScript
version or runtime capability than the current project supports, keep the
repo-safe path, record the migration path, and surface `OFFICIAL_CONFLICT`
when official guidance and current project truth materially diverge.

## Replacement Map

| Legacy pattern | Modern replacement | Why replace |
| -------------- | ------------------ | ----------- |
| Implicit type-only imports | `import type` and `export type` with `verbatimModuleSyntax` | Avoid runtime import confusion and preserve emit intent. |
| Loose module detection | `moduleDetection: force` in module-based repos | Prevent accidental script-mode behavior and phantom globals. |
| Broad assertion chains (`as any as ...`) | Narrowing with guards, discriminated unions, and `unknown` boundaries | Improve soundness and reviewability. |
| Inference hacks for generic APIs | Built-in `NoInfer` and clearer generic parameter design | Reduce surprising inference outcomes. |
| Widened config object literals | `satisfies` plus optional `as const` where needed | Preserve literals while validating shape. |
| Namespace-heavy architecture | ES modules with explicit exports | Align with modern tooling and runtime semantics. |
| Framework-independent legacy decorator mode | Standard decorators when supported by the runtime and framework | Preserve actual decorator and metadata semantics. |

## Explicit Bans For New Code

- Do not create new `namespace`-centric architecture.
- Do not use implicit type-only imports.
- Do not introduce assertion-heavy shortcuts where narrowing is feasible.
- Preserve framework-required decorator semantics in new and existing modules.
  Angular-supported `experimentalDecorators` is not a migration exception.
  Consider standard decorators only when the framework/runtime supports them
  and a migration is actually in scope.
- Do not add ambiguous ESM and CJS interop hacks without runtime-driven justification.

## Migration Exception Format

Allow temporary retention only when all fields are captured:

1. `retained_pattern`: exact legacy construct being kept.
2. `scope`: file or module boundary where it remains.
3. `constraint`: concrete compatibility reason.
4. `fallback_evaluated`: modern option considered and why deferred.
5. `retirement_trigger`: condition that allows removal.
6. `follow_up_change`: concrete modernization task.

## Example Exception Record

```md
retained_pattern: experimental decorator usage in auth module
scope: src/auth/decorators.ts
constraint: framework plugin currently depends on legacy metadata flow
fallback_evaluated: standard decorators tested, plugin rejects metadata shape
retirement_trigger: plugin vNext with standards-compliant decorator metadata
follow_up_change: migrate decorators and remove legacy compiler flags
```

## Review Checklist

Supported framework syntax is outside this migration-only exception process.

1. Confirm the construct is actually deprecated or unsupported for the target,
   rather than merely different from a generic TypeScript preference.
2. Confirm modern replacement was evaluated.
3. Confirm exception scope is narrowly bounded.
4. Confirm follow-up task is concrete and testable.
5. Confirm no additional legacy surface area was added.

## Practical Migration Strategy

1. Harden module and import hygiene first.
2. Replace assertion-heavy typing with boundary validation and unions.
3. Migrate shared utilities to modern generic and literal-preserving patterns.
4. Remove retained exceptions as soon as constraints clear.
