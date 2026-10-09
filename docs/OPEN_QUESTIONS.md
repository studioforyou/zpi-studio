# Open Questions

[Documentation map](README.md) · [Workflow](WORKFLOW.md)

This register records unresolved decisions that affect repository documentation or workflow. GitHub Issues remain the source of task status and assignment. A responsible role below identifies who can resolve a question; it does not create an assignment. All entries below are **open**.

When resolving an entry, record the answer, date, and source decision here; update the authoritative document in the same change. Keep the stable question ID and link to any replacement decision. An unanswered question is not an accepted default.

## Q-001 — Workshop identifiers

- **Question/source:** [#30](https://github.com/studioforyou/zpi-studio/issues/30) names Workshop B in its title but D and E in its description.
- **Impact:** The intended workshop and scope cannot be determined consistently from the issue.
- **Responsible role:** The issue owner, with instructor clarification if needed.
- **Next step:** Confirm the intended workshop identifiers and align the title, description, and any dependent references without changing the intended requirements.

## Q-002 — Sprint rules

- **Question/source:** The current documentation preparation phase is described in [WORKFLOW.md](WORKFLOW.md#current-phase-and-status); instructor guidance on implementation sprints is pending.
- **Impact:** Sprint cadence, planning, deliverables, and assessment rules must not be invented or presented as agreed. Documentation preparation can continue.
- **Responsible role:** Instructors provide the rules; the team records their application to this repository.
- **Next step:** After the semester briefing, link the authoritative guidance and update the workflow. Assign implementation work to sprints using those rules.

## Q-003 — Domain terminology

- **Question/source:** The shared domain glossary is the scope of [#21](https://github.com/studioforyou/zpi-studio/issues/21).
- **Impact:** New documentation may otherwise give different English names to the same domain concept or treat tentative definitions as final.
- **Responsible role:** The owner of #21 and team members validating domain requirements.
- **Next step:** Agree on terms and definitions in #21, publish its glossary artifact, and link it from the documentation index. Until then, explicitly identify tentative domain terms.

## Q-004 — Architecture decision acceptance

- **Question/source:** Architecture decisions are covered by [#4](https://github.com/studioforyou/zpi-studio/issues/4) and [#25](https://github.com/studioforyou/zpi-studio/issues/25); a documented acceptance procedure and accepted ADR artifacts are not yet available in the repository.
- **Impact:** A proposed architecture could be mistaken for an accepted decision or an implemented capability.
- **Responsible role:** The owner of #25 and the team members reviewing architecture.
- **Next step:** Define how an ADR is accepted and superseded, record the decision and reviewers, and distinguish acceptance from implementation. Publish and index ADRs through #25.

## Q-005 — AI-tool branch prefixes

- **Question/source:** [Naming conventions](NAMING_CONVENTIONS.md#1-branches) use `<type>/<issue-number>-<short-description>`. The current Codex task defaults to a `codex/` prefix, producing `codex/42-repository-context`. This is a task/tool default, not a Git requirement or an agreed team exception.
- **Impact:** A Codex-created task branch can differ from the documented branch types even while retaining its issue number and purpose.
- **Responsible role:** The team members maintaining repository workflow and the AI-tool users.
- **Next step:** Agree whether AI-assisted branches should use the existing task types or an explicit tool prefix. Record the agreed rule in the naming conventions and reference it from AI context; do not silently redefine the team convention.

## Q-006 — Web application technology

- **Question/source:** [web/README.md](../web/README.md) leaves the choice between React and Flutter for the web application open. This relates to the architecture scope of [#4](https://github.com/studioforyou/zpi-studio/issues/4).
- **Impact:** Documentation and models must not assume that a web framework has been selected or implemented.
- **Responsible role:** The team members defining the web requirements and architecture.
- **Next step:** Establish the web requirements, compare the options, and record the accepted choice and rationale in an ADR. Update the web README and documentation index to reference it.
