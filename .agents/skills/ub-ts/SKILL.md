---
name: ub-ts
description: Guide TypeScript and JavaScript code generation, edits, refactoring, and review, including browser scripts, boundary types, tsconfig, module resolution, and compiler errors. Apply with ub-quality; add ub-angular for Angular components, templates, services, and state. Preserve the target framework/runtime contract. Do not introduce TypeScript syntax or a module migration into plain JavaScript merely because this skill is loaded.
---

# UB TS

## Automatic application and ownership

Apply with `ub-quality` before TypeScript or JavaScript work when repository
instructions route here. Add `ub-angular` for Angular architecture and behavior;
this skill owns typing, compiler, module, and expression-level decisions.

Inspect package/lock files, actual compiler options, framework configuration,
source conventions, and existing checks. Work against supported versions.
Version-sensitive API or migration decisions need matching official guidance;
ordinary implementation does not authorize upgrades or replacement configs.

## Code structure and boundary types

- Apply the shared cohesion, method-size, complexity, and nesting defaults.
  Separate request construction, transformation, state changes, and I/O when
  they obscure the caller's responsibility.
- Name meaningful intermediate results, boolean decisions, and substantial
  transformations. Keep simple accessors and short mapping callbacks inline.
- Extract nested or branching callbacks into domain-named functions rather than
  hiding complex logic in chains. Avoid generic `utils` modules without a real
  common responsibility.
- Use typed contracts and meaningful unions for known shapes. Accept `unknown`
  at untrusted boundaries and validate/narrow it before domain use.
- Avoid new `any`, unchecked non-null assertions, and assertion chains where
  narrowing or an explicit model can represent the contract.
- Use `satisfies`, generics, or discriminated unions when they solve a concrete
  type problem; do not introduce them as ceremony.
- Keep promises, cancellation, cleanup, and state ownership explicit. Handle
  failures at the boundary that can make the correct recovery decision.

## Framework and module compatibility

- Preserve Angular-supported decorators, dependency injection, and compiler
  options, including `experimentalDecorators` when the framework requires it.
  Supported framework syntax is not a temporary legacy exception.
- Preserve imports needed at runtime for decorators, DI tokens, and side
  effects; use type-only imports for genuinely erased types.
- Match module/moduleResolution to the actual runtime or bundler. Do not force
  Node settings into Angular, or bundler-only settings into published libraries.
- Preserve an existing strict configuration. Consider additional safety flags
  during explicit compiler work; do not enable project-wide flags as incidental
  cleanup during a focused feature edit.
- Load migration guidance only when a migration is actually requested or a
  deprecated construct must be changed for the task.

## Plain browser JavaScript

- Emit JavaScript, not TypeScript annotations. Use JSDoc only where it improves
  a non-obvious contract and fits the project's existing approach.
- Preserve classic-script versus module behavior, load order, DOM readiness,
  global integration, and event/subscription cleanup.
- Apply these rules inside HTML `<script>` blocks too. Do not migrate a plain
  page to Angular or another framework to improve its structure.
- Inspect host templates before changing interpolation or embedded strings.
  Preserve fixed model instructions and whitespace-sensitive content.

## References and optional assets

Read the relevant resource before dependent decisions:

- [Modern patterns](references/ts-modern-patterns.md): typing, runtime/module,
  and compiler decisions.
- [Migration](references/ts-legacy-to-modern-migration.md): actual modernization.
- [Config resolution](references/ts-config-resolution.md): config discovery or
  explicitly requested scaffolding.
- [Task bundle](references/task-bundle.md): only when the user requests that
  optional automation overlay.
- [Shared authoring](../ub-authoring/references/authoring-conventions.md):
  changes to skill routing or shared authoring behavior.

The existing `assets/` and `scripts/scaffold_ts_baseline.py` are optional
starters. Use them only when explicitly setting up missing tooling. Never
silently install packages, add CI/hooks, or overwrite a framework's config.
Instruction-only coding guidance needs none of those setup steps.

## Completion

Perform the normal `ub-quality` self-check and run relevant existing compiler,
template, lint, build, and behavior checks yourself. Report what changed,
material type/runtime decisions, retained debt, and checks run or blocked.
Keep routine reports concise; do not force an alternative design discussion
for a settled or trivial choice.

Ordinary completion does not invoke `ub-review` or spawn a repair cycle.
