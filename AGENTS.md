# Repository instructions

## Begin with context

1. Read [README.md](README.md) for the current phase and inspection baseline.
2. Use [docs/README.md](docs/README.md) to find the task's authoritative sources. Read the relevant issue and acceptance criteria when access is available; report missing access instead of inventing its contents.
3. Before working in an area, read its README and any nested `AGENTS.md` or tool-specific instruction file. Do not assume that changing directories automatically loads new instructions.
4. Follow the current user's task scope. The project is currently preparing documentation; application implementation and operational automation belong to future, explicitly scoped sprint work.

## Shared working rules

- Follow [naming and language conventions](docs/NAMING_CONVENTIONS.md) and the [team workflow](docs/WORKFLOW.md). Keep repository content in English; conversation may use the user's preferred language.
- Keep one source for each rule. Link to requirements, the glossary, and ADRs instead of recreating them in instructions or session summaries.
- Distinguish implemented behavior, planned capabilities, and proposed decisions. Use [open questions](docs/OPEN_QUESTIONS.md) for unresolved agreements. Do not approve business rules, architecture, or course requirements on the team's behalf.
- Inspect the current branch, working tree, and task boundaries before changing files. Preserve unrelated work. Explicit authorization in the current task determines which operations may be performed; these instructions do not grant permission to publish or discard work.
- If instructions conflict, state the conflict and its sources. Do not rewrite a team convention silently to match a tool's defaults. Continue independent work when the unresolved point does not affect it.
- Update related documentation in the same change, following the [maintenance rules](docs/README.md#keeping-documentation-current).

## Verification and handoff

- Use the checks appropriate to the task. For documentation, check links, source consistency, English wording, and diagrams.
- Consult [local development notes](docs/LOCAL_DEVELOPMENT.md) before reporting setup or test commands as working. Preserve the distinction between configuration validation and running the application.
- Report the result, affected documents, checks actually performed, and unresolved questions. Do not claim that an issue is complete or a command passed without evidence.

Tool loading details and a read-only context check are in [docs/AI_CONTEXT.md](docs/AI_CONTEXT.md). A future workflow assistant is specified separately in [#43](https://github.com/studioforyou/zpi-studio/issues/43).
