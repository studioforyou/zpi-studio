package studioforyou.pl.backend.scheduling.internal;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.identity.AccountRoles;
import studioforyou.pl.backend.scheduling.internal.ScheduleConflicts.Assignments;
import studioforyou.pl.backend.scheduling.internal.ScheduleConflicts.Occurrence;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

import org.springframework.stereotype.Component;

/** Checks an offer window in batches, using the same availability rules as the schedule. */
@Component
class SubstitutionEligibility {

	private final StaffProfiles staff;
	private final AccountRoles accounts;
	private final ScheduleConflicts conflicts;
	private final StudioSettings studio;

	SubstitutionEligibility(StaffProfiles staff, AccountRoles accounts, ScheduleConflicts conflicts,
			StudioSettings studio) {
		this.staff = staff;
		this.accounts = accounts;
		this.conflicts = conflicts;
		this.studio = studio;
	}

	record Key(UUID requestId, UUID staffId) {
	}

	record Result(@Nullable String reason, List<String> warnings) {
		boolean available() { return this.reason == null; }
	}

	Map<Key, Result> assess(List<SubstitutionManagement.Row> offers, List<StaffMember> candidates) {
		Map<Key, Result> result = new HashMap<>();
		if (offers.isEmpty() || candidates.isEmpty()) return result;
		List<UUID> ids = candidates.stream().map(StaffMember::id).toList();
		var accountIds = this.staff.findAccountIds(ids);
		var activeAccounts = this.accounts.activeTrainers(accountIds.values());
		var qualifications = this.staff.findQualifications(ids);
		var zone = this.studio.zone();
		var occurrences = offers.stream().map((r) -> new Occurrence(LocalDate.ofInstant(r.startsAt(), zone),
				r.startsAt(), r.endsAt(), false)).toList();
		var assignments = new Assignments(ids.stream().map((id) -> new EventStaffAssignment(id, StaffRole.LEAD)).toList(),
				List.of(), Map.of());
		var issues = this.conflicts.find(occurrences, assignments, zone, List.of());
		for (int i = 0; i < offers.size(); i++) {
			var offer = offers.get(i);
			for (var candidate : candidates) {
				var ownIssues = issues.get(i).stream().filter((p) -> candidate.id().equals(p.staffId()))
					.filter((p) -> !offer.eventId().equals(p.eventId())).toList();
				String reason = !candidate.active() || !activeAccounts.contains(accountIds.get(candidate.id()))
						? "INACTIVE" : candidate.id().equals(offer.originalStaffId()) ? "OWN_EVENT"
						: offer.serviceTypeId() != null && !qualifications.getOrDefault(candidate.id(), java.util.Set.of())
							.contains(offer.serviceTypeId()) ? "NOT_QUALIFIED"
						: ownIssues.stream().anyMatch(ScheduleDtos.ScheduleIssue::blocking) ? "UNAVAILABLE" : null;
				result.put(new Key(offer.id(), candidate.id()), new Result(reason,
						ownIssues.stream().filter((p) -> !p.blocking()).map((p) -> p.type().name()).distinct().toList()));
			}
		}
		return result;
	}
}
