# docs/WORKFLOW.md

## Code Lifecycle and Deployment (Workflow)

Our team works based on a single protected main branch (`main`), short-lived task branches, and Pull Requests. The process does not involve maintaining a separate `dev` branch in order to minimize team overhead and operational costs.

### Step 1: Picking up a task and starting work
* Start by assigning yourself to the issue on GitHub.
* Always create a new branch from the up-to-date `main` branch:
  ```bash
  git fetch --all
  git checkout main
  git pull origin main
  git checkout -b feature/12-customer-registration
  ```
* During development, use the `local` environment (local PostgreSQL database via Docker Compose, third-party mocks). Run it using the command in the root directory:
  ```bash
  docker-compose up -d --build
  ```

### Step 2: Submitting changes (Pull Request)
* Commit your changes adhering to naming conventions, then push the branch to the remote repository:
  ```bash
  git add .
  git commit -m "feat(backend): add registration endpoint (#12)"
  git push -u origin feature/12-customer-registration
  ```
* Once pushed, open a Pull Request (PR) targeting `main` via the GitHub interface.
* The CI pipeline (GitHub Actions) automatically triggers the build process, linting, and tests (including modular architecture tests).
* Wait for code review approval from at least one team member.
* If CI tests fail, apply fixes, create new commits, and push them (`git push`) to the same branch — under no circumstances should untested or failing code be merged.

### Step 3: Integration (Merge to main)
* After obtaining approval and passing all CI checks, merge the PR using exclusively the **Squash and merge** option on GitHub.
* Verify that the title and description of the final squash commit follow project naming conventions (including `Closes #12`).
* After merging, delete the task branch on GitHub and clean up your local repository:
  ```bash
  git checkout main
  git pull origin main
  git branch -d feature/12-customer-registration
  ```

### Step 4: Deployments (Staging and Production)
* **Staging:** Deployment to staging happens automatically after merging into `main`. This environment is used for end-to-end system integration and manual acceptance testing by stakeholders using test data.
* **Production:** Production deployment to the live studio is performed after verification on the staging environment. A release/tag for production is created via the GitHub interface once approved.