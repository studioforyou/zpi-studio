package studioforyou.pl.backend.reporting.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.identity.AuthenticatedAccount;
import studioforyou.pl.backend.reporting.internal.TrainerSettlementStore.Header;
import studioforyou.pl.backend.reporting.internal.TrainerSettlementStore.Payment;
import studioforyou.pl.backend.reporting.internal.TrainerSettlementStore.Revision;
import studioforyou.pl.backend.reporting.internal.TrainerSettlementsController.Approval;
import studioforyou.pl.backend.reporting.internal.TrainerSettlementsController.Correction;
import studioforyou.pl.backend.reporting.internal.TrainerSettlementsController.Payout;
import studioforyou.pl.backend.reporting.internal.TrainerSettlementsController.Reversal;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.shared.api.Money;

/** Monthly agreements and a manual, append-only payout ledger. This service never sends money. */
@Service
class TrainerSettlements {

	private final TrainerSettlementStore store;
	private final TrainerHoursReport hours;
	private final StudioSettings studio;
	private final ObjectMapper json;

	TrainerSettlements(
			TrainerSettlementStore store,
			TrainerHoursReport hours,
			StudioSettings studio,
			ObjectMapper json) {
		this.store = store;
		this.hours = hours;
		this.studio = studio;
		this.json = json;
	}

	record Snapshot(TrainerHoursReport.Report report, String studioName, Instant generatedAt) {}

	record Preview(Snapshot snapshot, String currency, String fingerprint) {}

	enum Status {
		UNPAID,
		PARTIAL,
		PAID
	}

	record Summary(
			UUID id,
			UUID staffId,
			String month,
			String displayName,
			long version,
			int revision,
			Money amount,
			Money paid,
			Money remaining,
			long countedSeconds,
			long unconfirmedSeconds,
			boolean includeUnconfirmed,
			Status status,
			Instant updatedAt) {}

	record RevisionView(
			UUID id,
			int revision,
			Money amount,
			boolean includeUnconfirmed,
			Snapshot snapshot,
			String reason,
			String actor,
			Instant createdAt) {}

	record PaymentView(
			UUID id,
			Money amount,
			LocalDate paidOn,
			String method,
			String note,
			@Nullable UUID reversesId,
			boolean reversed,
			String actor,
			Instant createdAt) {}

	record Detail(Summary summary, List<RevisionView> revisions, List<PaymentView> payments) {}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	List<Summary> list(String month) {
		return store.headers(month(month).atDay(1)).stream()
				.map(this::detail)
				.map(Detail::summary)
				.sorted(Comparator.comparing(Summary::displayName).thenComparing(Summary::id))
				.toList();
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	List<Detail> export(String month) {
		return store.headers(month(month).atDay(1)).stream()
				.map(this::detail)
				.sorted(Comparator.comparing((Detail row) -> row.summary().displayName()))
				.toList();
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	Detail detail(UUID id) {
		return detail(header(id, false));
	}

	private Detail detail(Header header) {
		List<Revision> revisions = store.revisions(header.id());
		List<Payment> payments = store.payments(header.id());
		Revision latest = revisions.getFirst();
		BigDecimal paid = paid(payments);
		var counts = latest.snapshot().report().totals();
		long seconds =
				counts.seconds() - (latest.includeUnconfirmed() ? 0 : counts.unconfirmedSeconds());
		Instant changed =
				payments.stream()
						.map(Payment::createdAt)
						.max(Comparator.naturalOrder())
						.filter(time -> time.isAfter(latest.createdAt()))
						.orElse(latest.createdAt());
		Summary summary =
				new Summary(
						header.id(),
						header.staffId(),
						YearMonth.from(header.month()).toString(),
						latest.snapshot().report().instructors().getFirst().displayName(),
						header.version(),
						latest.revision(),
						Money.of(latest.amount(), header.currency()),
						Money.of(paid, header.currency()),
						Money.of(latest.amount().subtract(paid), header.currency()),
						seconds,
						counts.unconfirmedSeconds(),
						latest.includeUnconfirmed(),
						paid.compareTo(latest.amount()) == 0
								? Status.PAID
								: paid.signum() > 0 ? Status.PARTIAL : Status.UNPAID,
						changed);
		return new Detail(
				summary,
				revisions.stream()
						.map(
								row ->
										new RevisionView(
												row.id(),
												row.revision(),
												Money.of(row.amount(), header.currency()),
												row.includeUnconfirmed(),
												row.snapshot(),
												row.reason(),
												row.actor(),
												row.createdAt()))
						.toList(),
				payments.stream()
						.map(
								row ->
										new PaymentView(
												row.id(),
												Money.of(row.amount(), header.currency()),
												row.paidOn(),
												row.method(),
												row.note(),
												row.reversesId(),
												payments.stream()
														.anyMatch(
																p ->
																		row.id()
																				.equals(
																						p
																								.reversesId())),
												row.actor(),
												row.createdAt()))
						.toList());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	Preview preview(String month, UUID staffId) {
		return preview(month, staffId, studio.currency());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	Preview revisionPreview(UUID id) {
		Header saved = header(id, false);
		return preview(YearMonth.from(saved.month()).toString(), saved.staffId(), saved.currency());
	}

	private Preview preview(String month, UUID staffId, String currency) {
		YearMonth period = month(month);
		var report =
				hours.build(
						ReportPeriod.of(period.atDay(1), period.atEndOfMonth()),
						EnumSet.allOf(TrainerHoursReport.Kind.class),
						staffId,
						false,
						Instant.now());
		if (report.instructors().size() != 1)
			throw new ApiException(ErrorCode.NOT_FOUND, "Instructor not found in this period.");
		Snapshot snapshot = new Snapshot(report, studio.name(), Instant.now());
		return new Preview(snapshot, currency, fingerprint(snapshot, currency));
	}

	@Transactional
	Detail approve(Approval request, AuthenticatedAccount actor) {
		YearMonth period = month(request.month());
		if (!store.create(
				request.operationId(),
				request.staffId(),
				period.atDay(1),
				request.amount().currency())) {
			Header existing =
					store.header(request.operationId(), true)
							.orElseThrow(
									() ->
											new ApiException(
													ErrorCode.SETTLEMENT_EXISTS,
													"This instructor already has a settlement for"
														+ " this month."));
			Revision first =
					store.revision(request.operationId())
							.orElseThrow(TrainerSettlements::operationConflict);
			if (!existing.staffId().equals(request.staffId())
					|| !existing.month().equals(period.atDay(1))
					|| !existing.currency().equals(request.amount().currency())
					|| !first.actorId().equals(actor.id())
					|| first.amount().compareTo(request.amount().toAmount()) != 0
					|| first.includeUnconfirmed() != request.includeUnconfirmed()
					|| !first.reason().equals(note(request.note()))
					|| !Objects.equals(first.sourceFingerprint(), request.fingerprint())) throw operationConflict();
			return detail(existing);
		}
		Preview preview = preview(request.month(), request.staffId());
		currency(request.amount(), preview.currency());
		unchanged(preview, request.fingerprint());
		store.appendRevision(
				request.operationId(),
				request.operationId(),
				1,
				request.amount().toAmount(),
				request.includeUnconfirmed(),
				preview.snapshot(),
				request.fingerprint(),
				note(request.note()),
				actor);
		return detail(request.operationId());
	}

	@Transactional
	Detail revise(UUID id, Correction request, AuthenticatedAccount actor) {
		Header header = header(id, true);
		var replay = store.revision(request.operationId());
		if (replay.isPresent()) {
			Revision saved = replay.get();
			if (!saved.settlementId().equals(id)
					|| !saved.actorId().equals(actor.id())
					|| saved.amount().compareTo(request.amount().toAmount()) != 0
					|| saved.includeUnconfirmed() != request.includeUnconfirmed()
					|| !saved.reason().equals(request.reason().trim())
					|| !header.currency().equals(request.amount().currency())
					|| !Objects.equals(saved.sourceFingerprint(), request.fingerprint())) throw operationConflict();
			return detail(header);
		}
		version(header, request.version());
		currency(request.amount(), header.currency());
		if (request.amount().toAmount().compareTo(paid(store.payments(id))) < 0)
			throw new ApiException(
					ErrorCode.SETTLEMENT_AMOUNT_CONFLICT,
					"The agreed amount cannot be less than recorded payouts.");
		Revision previous = store.revisions(id).getFirst();
		Snapshot snapshot = previous.snapshot();
		if (request.fingerprint() != null) {
			Preview fresh =
					preview(
							YearMonth.from(header.month()).toString(),
							header.staffId(),
							header.currency());
			unchanged(fresh, request.fingerprint());
			// The settlement's currency is fixed even if the studio later changes its currency.
			snapshot = fresh.snapshot();
		}
		store.appendRevision(
				request.operationId(),
				id,
				previous.revision() + 1,
				request.amount().toAmount(),
				request.includeUnconfirmed(),
				snapshot,
				request.fingerprint(),
				request.reason().trim(),
				actor);
		store.advance(id);
		return detail(id);
	}

	@Transactional
	Detail payout(UUID id, Payout request, AuthenticatedAccount actor) {
		Header header = header(id, true);
		var replay = store.payment(request.operationId());
		if (replay.isPresent()) {
			Payment row = replay.get();
			if (!row.settlementId().equals(id)
					|| !row.actorId().equals(actor.id())
					|| row.reversesId() != null
					|| row.amount().compareTo(request.amount().toAmount()) != 0
					|| !row.paidOn().equals(request.paidOn())
					|| !row.method().equals(request.method().name())
					|| !row.note().equals(note(request.note()))
					|| !header.currency().equals(request.amount().currency()))
				throw operationConflict();
			return detail(header);
		}
		version(header, request.version());
		currency(request.amount(), header.currency());
		if (request.amount().amountMinor() <= 0)
			throw invalid("amount", "A payout must be greater than zero.");
		if (request.paidOn().isAfter(studio.today()) || request.paidOn().getYear() < 1900)
			throw invalid("paidOn", "Choose a past or present payout date.");
		BigDecimal remaining =
				store.revisions(id).getFirst().amount().subtract(paid(store.payments(id)));
		if (request.amount().toAmount().compareTo(remaining) > 0)
			throw new ApiException(
					ErrorCode.SETTLEMENT_AMOUNT_CONFLICT,
					"A payout cannot exceed the remaining amount.");
		store.appendPayment(
				request.operationId(),
				id,
				request.amount().toAmount(),
				request.paidOn(),
				request.method().name(),
				note(request.note()),
				null,
				actor);
		store.advance(id);
		return detail(id);
	}

	@Transactional
	Detail reverse(UUID id, UUID paymentId, Reversal request, AuthenticatedAccount actor) {
		Header header = header(id, true);
		var replay = store.payment(request.operationId());
		if (replay.isPresent()) {
			Payment row = replay.get();
			if (!row.settlementId().equals(id)
					|| !row.actorId().equals(actor.id())
					|| !paymentId.equals(row.reversesId())
					|| !row.note().equals(request.reason().trim())) throw operationConflict();
			return detail(header);
		}
		version(header, request.version());
		List<Payment> payments = store.payments(id);
		Payment original =
				payments.stream()
						.filter(row -> row.id().equals(paymentId) && row.reversesId() == null)
						.findFirst()
						.orElseThrow(
								() -> new ApiException(ErrorCode.NOT_FOUND, "Payout not found."));
		if (payments.stream().anyMatch(row -> paymentId.equals(row.reversesId())))
			throw new ApiException(
					ErrorCode.PAYOUT_ALREADY_REVERSED,
					"This payout entry has already been withdrawn.");
		store.appendPayment(
				request.operationId(),
				id,
				original.amount(),
				studio.today(),
				original.method(),
				request.reason().trim(),
				original.id(),
				actor);
		store.advance(id);
		return detail(id);
	}

	private YearMonth month(String value) {
		YearMonth current = YearMonth.from(studio.today());
		try {
			if (!value.matches("[1-9]\\d{3}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException();
			YearMonth month = YearMonth.parse(value);
			if (month.getYear() < 1900 || month.isAfter(current))
				throw new IllegalArgumentException();
			return month;
		} catch (java.time.DateTimeException | IllegalArgumentException ex) {
			throw invalid("month", "Choose a valid past or present month.");
		}
	}

	private Header header(UUID id, boolean lock) {
		return store.header(id, lock)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Settlement not found."));
	}

	private String fingerprint(Snapshot snapshot, String currency) {
		try {
			String source =
					json.writeValueAsString(snapshot.report())
							+ "\n"
							+ snapshot.studioName()
							+ "\n"
							+ currency;
			return HexFormat.of()
					.formatHex(
							MessageDigest.getInstance("SHA-256")
									.digest(source.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void unchanged(Preview preview, String fingerprint) {
		if (!preview.fingerprint().equals(fingerprint))
			throw new ApiException(
					ErrorCode.SETTLEMENT_SOURCE_CHANGED,
					"The schedule has changed. Review the updated hours before approval.");
	}

	private static BigDecimal paid(List<Payment> payments) {
		return payments.stream()
				.map(row -> row.reversesId() == null ? row.amount() : row.amount().negate())
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static void version(Header header, long version) {
		if (header.version() != version)
			throw new ApiException(
					ErrorCode.VERSION_CONFLICT, "The settlement changed. Reload it before saving.");
	}

	private static void currency(Money money, String currency) {
		if (!Objects.equals(money.currency(), currency))
			throw invalid("amount.currency", "Currency does not match the settlement.");
	}

	private static String note(@Nullable String value) {
		return value == null ? "" : value.trim();
	}

	private static ApiException invalid(String field, String detail) {
		return ApiException.invalidField(field, FieldErrorCode.INVALID_VALUE, detail);
	}

	private static ApiException operationConflict() {
		return new ApiException(
				ErrorCode.SETTLEMENT_OPERATION_CONFLICT,
				"This operation ID was already used with different data.");
	}
}
