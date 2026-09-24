# docs/NAMING_CONVENTIONS.md

> **Note:** All project documentation, commit messages, and PR descriptions must be written in English.

## 1. Branches
We use short-lived task branches (feature branches). Branch names must include the type, the GitHub issue number, and a brief description.

**Format:** `<type>/<issue-number>-<short-description>`
* `feature/12-user-registration` – for new features.
* `fix/34-payment-crash` – for bug fixes.
* `refactor/45-booking-module` – for structural changes and refactoring.
* `chore/55-github-actions` – for technical tasks and maintenance.

## 2. Commits
We operate in a monorepo, so specifying the `scope` in commit messages is mandatory. Use the imperative mood.

**Format:** `<type>(<monorepo-scope>): <description> (#<issue-number>)`
* `feat(backend): add pass discount validation (#12)`
* `fix(mobile): fix calendar date parsing error (#34)`
* `infra(terraform): add bucket for profile pictures`

## 3. Pull Requests (PR)
The PR title will become the main commit message on the target branch after a "Squash and merge" operation.

**Format:** `[#<issue-number>] <Business-oriented description of the change>`
* `[#12] New customer registration with email verification`
* **Important:** Always include `Closes #<issue-number>` in the PR description to automatically close the associated issue on the project board once merged.