---
tags:
  - idroid
  - documentation
  - standards
created: 2026-10-09
modified: 2026-10-09
status: active
---

# Documentation Standards

## Structure

Top-level documentation folders use a numeric prefix to follow the project lifecycle:

1. Project overview
2. Getting started
3. Architecture
4. Development
5. Deployment
6. Features
7. API reference
8. Troubleshooting
9. Project management
10. Resources

Keep the structure shallow, preferably no more than two levels. Add a subfolder only when at least three stable documents share a distinct topic and do not fit naturally alongside their siblings. Every folder must have a `README.md` that describes its purpose, contents, and related sections.

## Naming

- Use descriptive `kebab-case.md` filenames, such as `deployment-guide.md`.
- Name ADRs with a sequence and subject, such as `ADR-0001-storage-choice.md`.
- Use absolute dates in dated documents: `YYYY-MM-DD`.
- Avoid generic names such as `notes.md` and dumping-ground folders such as `misc/`.

## Frontmatter and status

Every maintained document starts with YAML frontmatter:

```yaml
---
tags:
  - idroid
  - <category>
created: YYYY-MM-DD
modified: YYYY-MM-DD
status: draft
---
```

Use `draft`, `in-review`, `active`, or `archived` for general documents. Update `modified` when content changes. Archive superseded guidance so its history remains available, and clearly link to its replacement.

## Writing and navigation

- Give each document one clear purpose and identify its intended audience.
- Prefer actionable steps, concrete examples, and explicit prerequisites.
- Link related project documents using relative Markdown links.
- Keep README content inventories current as documents are added.
- Never include credentials, tokens, or other secrets in examples; use obvious placeholders.

## Templates

Use the closest matching document in [Templates](./templates/README.md), then remove sections that do not apply and replace every placeholder before publishing.
