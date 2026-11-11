package studioforyou.pl.backend.reporting.internal;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;

import studioforyou.pl.backend.scheduling.ScheduledEvents.EventFilter;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * The query string of a report about events: the period, and which events it covers. A filter
 * parameter can be repeated (or take values separated by commas); without it nothing is left out,
 * except that {@code kind} has a default of its own for each report.
 */
@ParameterObject
record ReportQuery(
		@Parameter(description = "First day of the period, in the studio's time zone.", required = true,
				example = "2026-10-01") @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate from,
		@Parameter(description = "Last day of the period (inclusive).", required = true,
				example = "2026-10-31") @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate to,
		@Parameter(description = "Kinds of events; each report has its default.") @Nullable List<ReportEventKind> kind,
		@Parameter(description = "Only events of these service types.") @Nullable List<UUID> serviceTypeId,
		@Parameter(description = "Only events these staff members run or assist.") @Nullable List<UUID> staffId,
		@Parameter(description = "Only classes of these fixed groups.") @Nullable List<UUID> trainingGroupId,
		@Parameter(description = "Only events in these rooms.") @Nullable List<UUID> resourceId) {

	ReportPeriod period() {
		return ReportPeriod.of(this.from, this.to);
	}

	/**
	 * The events to report.
	 *
	 * @param allowed kinds this report can cover; asking for another one is rejected
	 * @param defaults kinds covered when the query names none
	 */
	ReportFilter filter(Set<ReportEventKind> allowed, Set<ReportEventKind> defaults) {
		Set<ReportEventKind> kinds = this.kind == null || this.kind.isEmpty() ? EnumSet.copyOf(defaults)
				: EnumSet.copyOf(this.kind);
		if (!allowed.containsAll(kinds)) {
			throw ApiException.invalidField("kind", FieldErrorCode.INVALID_VALUE,
					"This report covers only " + allowed.stream().map(Enum::name).sorted().toList() + ".");
		}
		return new ReportFilter(kinds, set(this.serviceTypeId), set(this.staffId), set(this.trainingGroupId),
				set(this.resourceId));
	}

	private static Set<UUID> set(@Nullable List<UUID> ids) {
		return ids == null ? Set.of() : Set.copyOf(ids);
	}

	/** Which events a report covers; an empty set other than {@code kinds} leaves nothing out. */
	record ReportFilter(Set<ReportEventKind> kinds, Set<UUID> serviceTypeIds, Set<UUID> staffIds,
			Set<UUID> trainingGroupIds, Set<UUID> resourceIds) {

		/** The kinds in declaration order, so responses are stable. */
		List<ReportEventKind> sortedKinds() {
			return this.kinds.stream().sorted().toList();
		}

		EventFilter toEventFilter() {
			return new EventFilter(this.kinds.stream().map(Enum::name).collect(Collectors.toSet()),
					this.serviceTypeIds, this.staffIds, this.trainingGroupIds, this.resourceIds);
		}

	}

}
