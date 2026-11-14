# Formatting and readable layout

## Formatting authority

Preserve syntax, runtime behavior, prompt content, and other whitespace-sensitive
data first. Follow the target project's formatter and language conventions.
When no formatter is configured, follow readable local conventions.

Do not install a formatter, create configuration, or restyle unrelated files
merely because this skill is loaded. Existing formatters may be run by the agent
when relevant to the authorized edit.

## Layout decisions

- Use one statement per line and avoid unrelated variable declarations grouped
  merely to save space.
- Expand argument lists, payloads, signatures, and expressions when nesting,
  long items, or several responsibilities make the compact form hard to scan.
- Keep short, obvious calls and literals compact when the formatter allows it.
  Two arguments alone do not require multiline expansion.
- Use blank lines to separate meaningful steps. Prefer extraction to large
  comment banners that conceal mixed responsibilities.
- Treat embedded scripts and styles as their native languages. Preserve host
  template syntax and whitespace-sensitive content.
- Use the configured line width; otherwise aim for a readable width around
  100–120 characters without distorting the language's idioms.

## Optional alignment

Manual separator-column alignment is optional. It must not override a
configured formatter or trigger unrelated changes. Both ordinary formatter
output and an existing modestly aligned table can be readable.

Do not reapply padding that a formatter removed. Avoid excessive spacing,
alignment across unrelated declarations, and formatting exceptions invented
solely to preserve a preferred column.

## Completion check

Confirm touched code is readable, follows actual tooling, and preserves meaning.
Document a formatting exception only when a concrete constraint matters.
