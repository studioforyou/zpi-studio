# Scripts

- `db/create_dev_account.sql`: creates an active local account for an existing Firebase user. Usage in the main README, section "Authentication".
- `check_db_numbering.py`: checks the names and numbers of the backend's Flyway migrations and seeds. Backend CI runs it with `--base` set to the pull request's target branch, so a new migration or seed has to take the next free number there. Run it locally before opening a pull request: `python3 scripts/check_db_numbering.py --base origin/main` (after `git fetch`).
