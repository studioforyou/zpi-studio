# Repository Workflow

[Documentation map](README.md) · [Naming conventions](NAMING_CONVENTIONS.md) · [Open questions](OPEN_QUESTIONS.md)

## Current phase and status

The current project phase is documentation preparation. Application implementation and automation are deferred to the implementation sprints, whose rules will be clarified by the instructors. This describes the current phase; it is not a permanent restriction on the project. See [Q-002](OPEN_QUESTIONS.md#q-002--sprint-rules).

This document preserves the team's existing workflow: a single integration branch (`main`), short-lived task branches, pull requests, review by at least one team member, and squash merges. A separate long-lived `dev` branch is not part of this workflow.

| Mechanism | Status |
| --- | --- |
| Main/task-branch/PR workflow | Documented team convention. |
| Protection of `main` | Required by the documented workflow; repository settings must be checked separately to establish actual enforcement. |
| Local PostgreSQL and backend through Docker Compose | Configuration exists. See [local development](LOCAL_DEVELOPMENT.md) for scope and limitations. |
| Automated build, linting, tests and architecture checks | Planned; no GitHub Actions workflow is currently tracked. CI implementation belongs to [#29](https://github.com/studioforyou/zpi-studio/issues/29). |
| Automatic staging deployment and production release flow | Planned; deployment automation is not currently tracked in the repository. |

Update these status statements when the corresponding implementation or configuration is verified. A described future check or deployment must not be treated as an existing capability.

## 1. Start a task

1. Read the issue, its dependencies, and the relevant documents linked from the [documentation map](README.md).
2. Confirm the issue's scope and assignment on GitHub. Assign yourself when taking responsibility for the work.
3. Inspect the working tree and branch before changing Git state. Preserve unrelated local work; resolve any conflict with the task's authorized scope before proceeding.
4. Create a short-lived task branch from an up-to-date `main`, following [naming conventions](NAMING_CONVENTIONS.md). AI-tool branch prefixes remain an [open question](OPEN_QUESTIONS.md#q-005--ai-tool-branch-prefixes).
5. Work within the issue's scope. For the current documentation phase, use [#42](https://github.com/studioforyou/zpi-studio/issues/42) for repository navigation and shared AI context; [#43](https://github.com/studioforyou/zpi-studio/issues/43) covers the future workflow assistant's specification.

The relationship is **issue → task branch → one or more commits → pull request → `main`**. AI-assisted work follows the same workflow; see [shared AI context](AI_CONTEXT.md).

## 2. Prepare changes and a pull request

1. Review the actual diff and stage only the intended changes. Use the commit format in [naming conventions](NAMING_CONVENTIONS.md).
2. Perform the validation appropriate to the change. For documentation, check relative links, navigation, diagram rendering, terminology, and consistency with the repository. Application checks are relevant when application code changes.
3. Record which checks ran, their results, and any checks that could not run. An absent CI workflow is not a successful CI run.
4. Push the task branch and open a PR targeting `main`, using the documented PR title format. Describe the change, relevant validation, and unresolved questions. Include `Closes #<issue-number>` when the PR completes that issue; otherwise describe the remaining scope and link the issue without closing it.
5. Obtain review approval from at least one team member. Address review feedback on the same branch.

Once CI is implemented, it should run the configured build, linting, tests, and relevant architecture checks. Fix failing checks before merging. Do not report planned checks as completed validation.

## 3. Merge and finish

1. Merge after team review approval and successful applicable validation, including required CI checks when configured. Use **Squash and merge**.
2. Keep the PR title in its documented format, then set the final squash commit title to `<type>(<scope>): <description> (#<issue-number>)`. Include `Closes #<issue-number>` in the squash commit body when the issue is complete. Do not assume GitHub's default message matches both conventions.
3. Check that the intended issue is closed only if its acceptance criteria are met.
4. Delete the merged task branch on GitHub and clean up its local branch after confirming that the relevant work is integrated. Bring the local `main` up to date before starting another task.

## 4. Planned deployment workflow

These are the previously documented deployment intentions, not operational instructions:

- **Staging:** a merge to `main` is intended to trigger deployment for integration and manual acceptance testing with test data.
- **Production:** a release/tag is intended to follow successful staging verification and approval.

Deployment configuration, responsible roles, and release criteria must be documented with the relevant implementation. Creating a PR or merging documentation does not currently imply that a deployment has occurred.
