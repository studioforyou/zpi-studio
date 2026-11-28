package studioforyou.pl.backend.reporting.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.identity.AccountRoles;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.scheduling.ScheduledEvents.StaffWork;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

/** Time from finished schedule occurrences, including consultations, without changing attendance or pay. */
@Service
@Transactional(readOnly = true)
class TrainerHoursReport {

	private final StudioSettings studio;

	private final ScheduledEvents events;

	private final StaffProfiles staff;

	private final ServiceTypes services;

	private final AccountRoles roles;

	TrainerHoursReport(StudioSettings studio, ScheduledEvents events, StaffProfiles staff, ServiceTypes services,
			AccountRoles roles) {
		this.studio = studio;
		this.events = events;
		this.staff = staff;
		this.services = services;
		this.roles = roles;
	}

	Report build(ReportPeriod period, Set<Kind> kinds, @Nullable UUID staffId, boolean confirmedOnly, Instant now) {
		ZoneId zone = this.studio.zone();
		List<StaffWork> work = this.events.findFinishedStaffWork(period.start(zone), period.end(zone), now).stream()
			.filter((session) -> kinds.contains(Kind.valueOf(session.kind())))
			.filter((session) -> staffId == null || staffId.equals(session.staffId()))
			.filter((session) -> !confirmedOnly || session.confirmed())
			.toList();
		var serviceNames = this.services.findAllById(work.stream().map(StaffWork::serviceTypeId)
			.filter(Objects::nonNull).distinct().toList()).stream()
			.collect(Collectors.toMap((service) -> service.id(), (service) -> service.name()));
		// A former instructor who still has historical assignments must not disappear from the report.
		List<StaffMember> allStaff = this.staff.findAll(null);
		var accounts = this.staff.findAccountIds(allStaff.stream().map(StaffMember::id).toList());
		var accountRoles = this.roles.rolesOf(accounts.values());
		Set<UUID> assigned = work.stream().map(StaffWork::staffId).collect(Collectors.toSet());
		Map<UUID, StaffMember> members = allStaff.stream()
			.filter((member) -> assigned.contains(member.id())
					|| accountRoles.getOrDefault(accounts.get(member.id()), Set.of()).contains("TRAINER"))
			.collect(Collectors.toMap(StaffMember::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
		Map<UUID, List<Session>> perStaff = new LinkedHashMap<>();
		members.values().stream().filter((member) -> staffId == null || staffId.equals(member.id()))
			.forEach((member) -> perStaff.put(member.id(), new ArrayList<>()));
		for (StaffWork session : work) {
			String title = session.title();
			if (title == null || title.isBlank()) {
				title = serviceNames.getOrDefault(session.serviceTypeId(), "");
			}
			perStaff.get(session.staffId()).add(new Session(session.eventId(), Kind.valueOf(session.kind()), title,
					session.startsAt(), session.endsAt(), session.role(),
					Duration.between(session.startsAt(), session.endsAt()).getSeconds(), session.confirmed(), session.rooms()));
		}
		List<Instructor> instructors = perStaff.entrySet().stream().map((entry) -> {
			StaffMember member = members.get(entry.getKey());
			return new Instructor(member.id(), member.displayName(), member.active(), Counts.of(entry.getValue()),
					List.copyOf(entry.getValue()));
		}).sorted(Comparator.comparingLong((Instructor row) -> -row.counts().seconds())
			.thenComparing(Instructor::displayName).thenComparing(Instructor::id)).toList();
		List<Session> all = instructors.stream().flatMap((row) -> row.sessions().stream()).toList();
		return new Report(period.from(), period.to(), zone.getId(), Counts.of(all), instructors);
	}

	enum Kind {
		CLASS, WORKSHOP, PERSONAL_TRAINING, CONSULTATION
	}

	@Schema(name = "TrainerHoursSession")
	record Session(UUID eventId, Kind kind, String title, Instant startsAt, Instant endsAt, String role,
			long seconds, boolean confirmed, String rooms) {
	}

	@Schema(name = "TrainerHoursCounts", description = "Exact elapsed seconds, not rounded per session. "
			+ "Events are distinct occurrences; time is summed per staff assignment.")
	record Counts(long seconds, long leadSeconds, long assistantSeconds, int events,
			long unconfirmedSeconds, int unconfirmedEvents) {
		static Counts of(List<Session> sessions) {
			return new Counts(sessions.stream().mapToLong(Session::seconds).sum(),
					sessions.stream().filter((s) -> s.role().equals("LEAD")).mapToLong(Session::seconds).sum(),
					sessions.stream().filter((s) -> s.role().equals("ASSISTANT")).mapToLong(Session::seconds).sum(),
					(int) sessions.stream().map(Session::eventId).distinct().count(),
					sessions.stream().filter((s) -> !s.confirmed()).mapToLong(Session::seconds).sum(),
					(int) sessions.stream().filter((s) -> !s.confirmed()).map(Session::eventId).distinct().count());
		}
	}

	@Schema(name = "TrainerHoursInstructor")
	record Instructor(UUID id, String displayName, boolean active, Counts counts, List<Session> sessions) {
	}

	@Schema(name = "TrainerHoursReport")
	record Report(LocalDate from, LocalDate to, String timeZone, Counts totals, List<Instructor> instructors) {
	}
}
