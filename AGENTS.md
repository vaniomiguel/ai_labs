# Repository guidance

## Automatic coding guidance

- Before generating, editing, refactoring, or reviewing code, read and apply
  `.agents/skills/ub-quality/SKILL.md` and its task-relevant references. Do this
  automatically; the user does not need to name a skill or run a command.
- Load the relevant language/framework skills before the dependent work:
  - Java, including Spring Boot: `.agents/skills/ub-java/SKILL.md`.
  - TypeScript or JavaScript, including embedded browser scripts:
    `.agents/skills/ub-ts/SKILL.md`.
  - Angular components, templates, services, or state: also load
    `.agents/skills/ub-angular/SKILL.md`.
  - Python: `.agents/skills/ub-python/SKILL.md`.
  - CSS or style blocks: `.agents/skills/ub-css/SKILL.md`.
  - Tailwind: `.agents/skills/ub-tailwind/SKILL.md` only when the task actually
    concerns Tailwind or the target project uses it.
- Prefer cohesive methods around 20–30 code lines. Inspect new or changed
  methods against defaults of 50 nonblank/noncomment code lines, cyclomatic
  complexity 10, and control-flow nesting depth 3. Refactor violations or
  explain a concrete exception; these are generation/review defaults, not
  installed build gates.
- Name meaningful intermediate results and domain constants. Extract complex
  callbacks, opaque calculations, repeated policy, and substantial payload
  construction. Keep straightforward expressions inline and avoid abstractions
  introduced only to satisfy a size target.
- Respect configured formatters and existing runtime/framework contracts.
  Preserve behavior and unrelated changes. Improve touched legacy code
  incrementally without worsening existing size or complexity debt.
- Before finishing, internally compare the implementation with the request,
  inspect structure and boundaries, and run relevant existing checks yourself.
  Report material exceptions or checks that could not run. This self-check
  does not start the separate review-and-repair workflow.
- Apply this baseline to repository automation and embedded source as well as
  application code. Load other skills only when their actual task applies;
  ordinary implementation does not require workflow/governance scaffolding.

## Explicit review-and-repair cycle

- Activate `.agents/skills/ub-review/SKILL.md` only when the user explicitly invokes
  `ub-review` or asks for an iterative review-and-repair cycle, for example:
  “Run the review cycle against my original request.” For that explicit chat
  request, read the skill directly even though implicit invocation is disabled.
- A request to create, edit, explain, or test the review skill is not an
  invocation of the workflow; its files may be read for that authoring work.
  An ordinary implementation request or read-only code review also does not
  authorize the repair cycle.
- Once explicitly invoked, follow the skill's delegation contract: use an
  independent read-only reviewer and a separate fixer, with at most three
  reviews and two sequential repair rounds per cycle. Stop early when clean.
- If issues remain after the final review, list them and wait for the user's
  choice to accept the current result or restart. Never restart automatically.

## Model prompts and instructions

- Give every fixed model instruction a descriptive, purpose-specific name. Never embed instruction prose inline in execution methods or payload-building code. A named `static final` constant is the minimum for Java prompts; name it for its goal and role, such as `REPAIR_ANALYSIS_INSTRUCTIONS`, rather than `PROMPT` or `TEXT`.
- Store multi-sentence or shared instructions in dedicated, purpose-named prompt resources and refer to them through named constant identifiers. Reuse the application's existing prompt catalog or loader.
- In the accounting backend, keep prompts and instruction fragments in `accounting/backend/src/main/resources/prompts/`, define fragment identifiers in `PromptCatalog`, and load them through `PromptCatalog.prompt(...)` so `PROMPT_DIRECTORY` overrides and bundled fallback remain available. Supplementary fragments use the task's response schema and do not need their own schema.
- Apply these rules to system prompts, user-message templates, supplementary instructions, and retry or validation feedback, including instructions stored inside JSON payloads. Dynamic document content and user-supplied data remain data.
- When changing a prompt or model request, inspect related call sites for inline instructions. For extraction-only refactors, preserve wording, composition order, separators, and whitespace that affect requests, prompt hashes, provenance, or caching; verify the assembled text and run the relevant existing checks.
- Document new accounting prompt resources and override filenames alongside the prompt configuration in `accounting/README.md`.
