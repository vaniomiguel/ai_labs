# Repository guidance

## Model prompts and instructions

- Give every fixed model instruction a descriptive, purpose-specific name. Never embed instruction prose inline in execution methods or payload-building code. A named `static final` constant is the minimum for Java prompts; name it for its goal and role, such as `REPAIR_ANALYSIS_INSTRUCTIONS`, rather than `PROMPT` or `TEXT`.
- Store multi-sentence or shared instructions in dedicated, purpose-named prompt resources and refer to them through named constant identifiers. Reuse the application's existing prompt catalog or loader.
- In the accounting backend, keep prompts and instruction fragments in `accounting/backend/src/main/resources/prompts/`, define fragment identifiers in `PromptCatalog`, and load them through `PromptCatalog.prompt(...)` so `PROMPT_DIRECTORY` overrides and bundled fallback remain available. Supplementary fragments use the task's response schema and do not need their own schema.
- Apply these rules to system prompts, user-message templates, supplementary instructions, and retry or validation feedback, including instructions stored inside JSON payloads. Dynamic document content and user-supplied data remain data.
- When changing a prompt or model request, inspect related call sites for inline instructions. For extraction-only refactors, preserve wording, composition order, separators, and whitespace that affect requests, prompt hashes, provenance, or caching; verify the assembled text and run the relevant existing checks.
- Document new accounting prompt resources and override filenames alongside the prompt configuration in `accounting/README.md`.
