# AI context guide

Status: documentation draft for [#42](https://github.com/studioforyou/zpi-studio/issues/42). This guide describes how to use the shared instructions; it does not configure an operational agent.

## Shared context

[AGENTS.md](../AGENTS.md) is the shared instruction source. It links to the project overview, documentation map, and team agreements. [CLAUDE.md](../CLAUDE.md) imports that file rather than copying its contents. Keep tool-specific setup details here; keep team rules in their owning documents.

Read only the additional documents relevant to the task. Requirements and ADRs remain pending until the corresponding documents are accepted. Personal chat history and machine-local agent memory are not team sources of truth.

## Codex

Codex discovers project instructions along the path from the repository root to the working directory when a run starts. Its automatic discovery does not scan every descendant directory. The shared instructions therefore explicitly require reading local guidance before working in an area. After changing instruction files, use a fresh run/session for the loading check.

Source: [OpenAI's AGENTS.md guide](https://learn.chatgpt.com/docs/agent-configuration/agents-md).

## Claude Code

The root `CLAUDE.md` contains an `@AGENTS.md` import. This provides a common entry point, including for sessions without native `AGENTS.md` support. It also avoids maintaining two copies of the repository rules. Use a fresh session and inspect `/context` or `/memory` to check loaded files.

Native `AGENTS.md` loading depends on the Claude Code version and configuration. Do not assume that every Claude product or chat interface automatically reads repository files. For a plain chat, provide the shared instructions and relevant documents explicitly.

Source: [Claude Code's project memory and shared-file guidance](https://code.claude.com/docs/en/memory#share-one-file-with-other-coding-tools).

## Read-only loading and navigation check

Run this check when introducing or changing shared or local instructions. It does not require application builds, database access, or file changes.

1. Open a fresh session at the repository root in the tool being checked.
2. Ask the tool to answer the prompt below without changing files or remote resources.
3. Inspect the tool's loaded-instruction listing where available. Compare its answers with the source table below, not with the wording of another model's answer.
4. Record the tool/version, starting directory, source commit or draft, loaded files, date, and result in the documentation PR. If a tool is unavailable, mark it **Not run** and have a teammate using it complete the check.

> Without modifying files or GitHub, identify the instruction files you loaded. What is the current project phase? Where are the requirements, domain glossary, and architecture decisions? Are CI and staging implemented in this repository? Which documents define branch and commit naming? Which instructions would you read before working in the backend? Cite repository paths or issue links for each answer, and distinguish unavailable information from confirmed facts.

| Check | Expected source / interpretation |
| --- | --- |
| Current phase | [Root README](../README.md): documentation preparation; detailed sprint rules pending. |
| Domain and architecture knowledge | [Pending knowledge](README.md#pending-knowledge): follow #20, #21, #4/#25; do not invent accepted documents. |
| CI and staging | [Workflow](WORKFLOW.md) and current repository files: planned capabilities, not established automated services. |
| Branch and commit naming | [Naming conventions](NAMING_CONVENTIONS.md); tool-prefix uncertainty remains in [open questions](OPEN_QUESTIONS.md). |
| Backend context | Root instructions -> [backend README](../backend/README.md) -> relevant issue and linked setup notes; inspect for any nested instructions before work. |

Repeat a navigation check from the backend directory when testing directory-scoped loading. Today there are no backend-specific agent instruction files. Add them only when a real local rule cannot be expressed clearly by linking to existing documentation.

## Verification record for this draft

The documentation author checked the official loading guidance and the `CLAUDE.md` import target. A fresh Codex session and a teammate's Claude session still need to complete the loading check above before cross-tool behavior is treated as verified. A model merely reading these Markdown files in an existing conversation is not that check.
