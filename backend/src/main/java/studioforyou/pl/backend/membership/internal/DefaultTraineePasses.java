package studioforyou.pl.backend.membership.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.membership.CustomerPassStatus;
import studioforyou.pl.backend.membership.TraineePasses;
import studioforyou.pl.backend.offering.PassServiceCoverage;

/** Each batch reads only membership-owned tables; no pass or history record is changed. */
@Service
@Transactional(readOnly = true)
class DefaultTraineePasses implements TraineePasses {

	private final CustomerPassRepository passes;

	private final JdbcClient jdbc;

	private final PassServiceCoverage coverage;

	DefaultTraineePasses(CustomerPassRepository passes, JdbcClient jdbc, PassServiceCoverage coverage) {
		this.passes = passes;
		this.jdbc = jdbc;
		this.coverage = coverage;
	}

	@Override
	public Map<UUID, List<Pass>> find(Collection<UUID> customerIds, Set<UUID> serviceTypeIds,
			LocalDate checkedOn, Set<LocalDate> referenceDays) {
		if (customerIds.isEmpty() || serviceTypeIds.isEmpty()) {
			return Map.of();
		}
		List<CustomerPass> held = passes.findByCustomerIdInOrderByCreatedAtDesc(customerIds);
		if (held.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Set<UUID>> covered = coverage.find(held.stream().map(CustomerPass::getVariantId).distinct().toList());
		Map<UUID, List<Suspension>> suspensions = jdbc.sql("""
				SELECT pass_id, starts_on, ends_on FROM pass_suspension
				WHERE pass_id IN (:ids) AND cancelled_at IS NULL
				""")
			.param("ids", held.stream().map(CustomerPass::getId).toList())
			.query(Suspension.class).list().stream().collect(Collectors.groupingBy(Suspension::passId));
		Set<LocalDate> days = new HashSet<>(referenceDays);
		days.add(checkedOn);
		Map<UUID, List<Pass>> result = new HashMap<>();
		for (CustomerPass pass : held) {
			Set<UUID> services = new HashSet<>(covered.getOrDefault(pass.getVariantId(), Set.of()));
			services.retainAll(serviceTypeIds);
			if (services.isEmpty()) {
				continue;
			}
			List<Suspension> periods = suspensions.getOrDefault(pass.getId(), List.of());
			Set<LocalDate> usable = days.stream().filter(day -> eligible(pass, day, periods)).collect(Collectors.toSet());
			boolean eligible = usable.contains(checkedOn);
			Short balance = pass.getEntriesRemaining();
			Pass snapshot = new Pass(pass.getId(), pass.getProductName(), pass.statusOn(checkedOn, suspended(periods, checkedOn)),
					balance, pass.getValidFrom(), pass.getValidUntil(), Set.copyOf(services), eligible,
					eligible && balance != null && balance > 0 && balance <= 3, Set.copyOf(usable));
			result.computeIfAbsent(pass.getCustomerId(), ignored -> new ArrayList<>()).add(snapshot);
		}
		return result;
	}

	private static boolean eligible(CustomerPass pass, LocalDate day, List<Suspension> periods) {
		return pass.getValidFrom() != null && pass.getValidUntil() != null
				&& pass.statusOn(day, suspended(periods, day)) == CustomerPassStatus.ACTIVE
				&& (pass.getEntriesRemaining() == null || pass.getEntriesRemaining() > 0);
	}

	private static boolean suspended(List<Suspension> periods, LocalDate day) {
		return periods.stream().anyMatch(period -> !day.isBefore(period.startsOn()) && !day.isAfter(period.endsOn()));
	}

	@Override
	public Map<UUID, Funding> funding(Collection<FundingRequest> reservations) {
		if (reservations.isEmpty()) {
			return Map.of();
		}
		List<UUID> reservationIds = reservations.stream().map(FundingRequest::reservationId).distinct().toList();
		Map<UUID, List<Ledger>> ledger = jdbc.sql("""
				SELECT reservation_id, pass_id, kind, entries_delta FROM pass_ledger
				WHERE reservation_id IN (:ids) AND kind IN ('ENTRY_RESERVED', 'ENTRY_CONSUMED', 'ENTRY_RETURNED')
				""")
			.param("ids", reservationIds).query(Ledger.class).list().stream()
			.collect(Collectors.groupingBy(Ledger::reservationId));
		Set<UUID> creditIds = reservations.stream().map(FundingRequest::makeupCreditId).filter(Objects::nonNull)
			.collect(Collectors.toSet());
		String creditWhere = "used_reservation_id IN (:reservationIds)";
		Map<String, Object> creditParams = new HashMap<>();
		creditParams.put("reservationIds", reservationIds);
		if (!creditIds.isEmpty()) {
			creditWhere += " OR id IN (:creditIds)";
			creditParams.put("creditIds", creditIds);
		}
		List<Credit> credits = jdbc.sql("SELECT id, customer_id, source_pass_id, status, used_reservation_id, used_at FROM makeup_credit WHERE " + creditWhere)
			.params(creditParams).query((rs, row) -> new Credit(rs.getObject("id", UUID.class),
					rs.getObject("customer_id", UUID.class), rs.getObject("source_pass_id", UUID.class), rs.getString("status"),
					rs.getObject("used_reservation_id", UUID.class),
					rs.getObject("used_at", OffsetDateTime.class) == null ? null : rs.getObject("used_at", OffsetDateTime.class).toInstant()))
			.list();
		Map<UUID, Credit> creditById = credits.stream().collect(Collectors.toMap(Credit::id, credit -> credit));
		Map<UUID, List<Credit>> creditsByReservation = credits.stream().filter(credit -> credit.usedReservationId() != null)
			.collect(Collectors.groupingBy(Credit::usedReservationId));
		Set<UUID> passIds = reservations.stream().map(FundingRequest::passId).filter(Objects::nonNull).collect(Collectors.toSet());
		credits.stream().map(Credit::sourcePassId).forEach(passIds::add);
		Map<UUID, PassInfo> info = passIds.isEmpty() ? Map.of() : jdbc.sql("""
				SELECT id, customer_id, product_name, entries_total FROM customer_pass WHERE id IN (:ids)
				""")
			.param("ids", passIds).query(PassInfo.class).list().stream().collect(Collectors.toMap(PassInfo::id, pass -> pass));
		Map<UUID, Funding> result = new HashMap<>();
		for (FundingRequest request : reservations) {
			List<Ledger> entries = ledger.getOrDefault(request.reservationId(), List.of());
			List<Credit> usedCredits = creditsByReservation.getOrDefault(request.reservationId(), List.of());
			Funding funding;
			if (request.makeupCreditId() != null) {
				Credit credit = creditById.get(request.makeupCreditId());
				PassInfo source = credit == null ? null : info.get(credit.sourcePassId());
				boolean valid = request.passId() == null && entries.isEmpty() && credit != null && source != null
						&& request.customerId().equals(credit.customerId()) && request.customerId().equals(source.customerId())
						&& "USED".equals(credit.status()) && request.reservationId().equals(credit.usedReservationId())
						&& credit.usedAt() != null && usedCredits.size() == 1;
				funding = valid ? new Funding("MAKEUP_USED", source.id(), source.productName()) : inconsistent();
			}
			else if (!usedCredits.isEmpty()) {
				funding = inconsistent();
			}
			else if (request.passId() == null) {
				funding = entries.isEmpty() ? new Funding("NO_PASS", null, null) : inconsistent();
			}
			else {
				funding = passFunding(request, info.get(request.passId()), entries);
			}
			result.put(request.reservationId(), funding);
		}
		return result;
	}

	private static Funding passFunding(FundingRequest request, PassInfo pass, List<Ledger> entries) {
		if (pass == null || !request.customerId().equals(pass.customerId()) || entries.isEmpty()
				|| entries.stream().anyMatch(entry -> !request.passId().equals(entry.passId()))) {
			return inconsistent();
		}
		Map<String, Ledger> byKind = new HashMap<>();
		for (Ledger entry : entries) {
			if (byKind.put(entry.kind(), entry) != null) {
				return inconsistent();
			}
		}
		boolean unlimited = pass.entriesTotal() == null;
		Ledger reserved = byKind.get("ENTRY_RESERVED");
		Ledger consumed = byKind.get("ENTRY_CONSUMED");
		Ledger returned = byKind.get("ENTRY_RETURNED");
		if (consumed != null && returned != null
				|| reserved != null && reserved.entriesDelta() != (unlimited ? 0 : -1)
				|| consumed != null && consumed.entriesDelta() != (unlimited || reserved != null ? 0 : -1)
				|| returned != null && (reserved == null || returned.entriesDelta() != (unlimited ? 0 : 1))) {
			return inconsistent();
		}
		String state = consumed != null ? "PASS_CONSUMED" : returned != null ? "PASS_RETURNED" : "PASS_RESERVED";
		return new Funding(state, pass.id(), pass.productName());
	}

	private static Funding inconsistent() {
		return new Funding("INCONSISTENT", null, null);
	}

	private record Suspension(UUID passId, LocalDate startsOn, LocalDate endsOn) {
	}

	private record PassInfo(UUID id, UUID customerId, String productName, Short entriesTotal) {
	}

	private record Ledger(UUID reservationId, UUID passId, String kind, int entriesDelta) {
	}

	private record Credit(UUID id, UUID customerId, UUID sourcePassId, String status, UUID usedReservationId,
			Instant usedAt) {
	}

}
