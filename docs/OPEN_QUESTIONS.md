# Open Questions

[Documentation map](README.md) · [Workflow](WORKFLOW.md)

This register records decisions that affect repository documentation or workflow. GitHub Issues remain the source of task status and assignment. A responsible role below identifies who can resolve a question; it does not create an assignment. Entries are open unless explicitly marked resolved.

When resolving an entry, record the answer, date, and source decision here; update the authoritative document in the same change. Keep the stable question ID and link to any replacement decision. An unanswered question is not an accepted default.

## Q-001 — Workshop identifiers

- **Question/source:** [#30](https://github.com/studioforyou/zpi-studio/issues/30) names Workshop B in its title but D and E in its description.
- **Impact:** The intended workshop and scope cannot be determined consistently from the issue.
- **Responsible role:** The issue owner, with instructor clarification if needed.
- **Next step:** Confirm the intended workshop identifiers and align the title, description, and any dependent references without changing the intended requirements.

## Q-002 — Sprint rules

- **Question/source:** The current documentation preparation phase is described in the [project overview](../README.md#current-state); instructor guidance on implementation sprints is pending.
- **Impact:** Sprint cadence, planning, deliverables, and assessment rules must not be invented or presented as agreed. Documentation preparation can continue.
- **Responsible role:** Instructors provide the rules; the team records their application to this repository.
- **Next step:** After the semester briefing, link the authoritative guidance and update the workflow. Assign implementation work to sprints using those rules.

## Q-003 — Domain terminology

- **Question/source:** The shared domain glossary is the scope of [#21](https://github.com/studioforyou/zpi-studio/issues/21).
- **Impact:** New documentation may otherwise give different English names to the same domain concept or treat tentative definitions as final.
- **Responsible role:** The owner of #21 and team members validating domain requirements.
- **Next step:** Agree on terms and definitions in #21, publish its glossary artifact, and link it from the documentation index. Until then, explicitly identify tentative domain terms.

## Q-004 — Architecture decision acceptance

- **Question/source:** The [architecture document](ARCHITECTURE_DECISIONS.md) contains ADRs for [#4](https://github.com/studioforyou/zpi-studio/issues/4), including [requester-confirmed choices](ARCHITECTURE_DECISIONS.md#scope-and-status). A complete team agreement on architecture acceptance and supersession remains open.
- **Impact:** Requester confirmation, team review, and implementation evidence need to remain distinguishable. Proposed records must not be treated as accepted merely because a skeleton uses the technology.
- **Responsible role:** The owner of #4 and the team members reviewing architecture.
- **Next step:** Agree on acceptance and supersession responsibilities using the existing review workflow. Record the review source and date for each resulting status change, retaining the requester's confirmation as provenance for existing choices.

## Q-005 — AI-tool branch prefixes

- **Question/source:** [Naming conventions](NAMING_CONVENTIONS.md#1-branches) use `<type>/<issue-number>-<short-description>`. The current Codex task defaults to a `codex/` prefix, producing `codex/42-repository-context`. This is a task/tool default, not a Git requirement or an agreed team exception.
- **Impact:** A Codex-created task branch can differ from the documented branch types even while retaining its issue number and purpose.
- **Responsible role:** The team members maintaining repository workflow and the AI-tool users.
- **Next step:** Agree whether AI-assisted branches should use the existing task types or an explicit tool prefix. Record the agreed rule in the naming conventions and reference it from AI context; do not silently redefine the team convention.

## Q-006 — Web application technology

- **Status:** Resolved on 2026-10-09 by requester confirmation of the team's existing React selection.
- **Original question:** The [web README](../web/README.md) previously left the choice between React and Flutter open within the scope of [#4](https://github.com/studioforyou/zpi-studio/issues/4).
- **Answer/source:** ADR-005 in the [decision table](ARCHITECTURE_DECISIONS.md#decision-table), supported by the [requester confirmation](ARCHITECTURE_DECISIONS.md#scope-and-status).
- **Impact:** The web choice is documented; the web application is still not implemented. Detailed client tooling remains open in ADR-005.
- **Responsible role:** The team members defining the web requirements and architecture.
- **Follow-up:** Select implementation tooling and hosting in the scoped frontend work, keeping the web README consistent with ADR-005.

## Q-007 — Architecture backlog alignment

- **Question/source:** [#4](https://github.com/studioforyou/zpi-studio/issues/4) still requests ADR-001 through ADR-012, while the [confirmed scope](ARCHITECTURE_DECISIONS.md#scope-and-status) omits ADR-012. [#32](https://github.com/studioforyou/zpi-studio/issues/32) still has the title `Firebase Auth`, while ADR-006 in the [decision table](ARCHITECTURE_DECISIONS.md#decision-table) limits Firebase to push notifications and confirms the application's own authentication system.
- **Impact:** The backlog and the prepared architecture documents describe different scope. This documentation change does not establish completion of #4 or redefine #32 remotely.
- **Responsible role:** The owners of #4 and #32, with the team reviewing architecture.
- **Next step:** Align #4's checklist with the eleven-record scope and revise the authentication/push implementation tasks to match the confirmed decision. Record the agreement through team review.
