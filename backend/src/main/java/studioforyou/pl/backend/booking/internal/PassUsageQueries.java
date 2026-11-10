package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.membership.PassUsageHistory;
import studioforyou.pl.backend.membership.PassUsageHistory.Pass;
import studioforyou.pl.backend.offering.PassVariantLookup;
import studioforyou.pl.backend.offering.PassVariantLookup.ProductReference;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;

/** Composes a consistent read through membership and scheduling without cross-owner joins. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class PassUsageQueries {

	private final CustomerProfiles profiles;

	private final PassUsageHistory history;

	private final GroupClassSchedule schedule;

	private final JdbcClient jdbc;

	private final PassVariantLookup variants;

	PassUsageQueries(CustomerProfiles profiles, PassUsageHistory history, GroupClassSchedule schedule, JdbcClient jdbc,
			PassVariantLookup variants) {
		this.profiles = profiles;
		this.history = history;
		this.schedule = schedule;
		this.jdbc = jdbc;
		this.variants = variants;
	}

	PageResponse<UsageItem> list(UUID accountId, UUID profileId, UUID passId, boolean includeUpcoming, int page, int size) {
		if (!profiles.isActingFor(accountId, profileId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		return accountHistory(accountId, profileId, passId, null, null, null, includeUpcoming, page, size).entries();
	}

	AccountHistory accountHistory(UUID accountId, UUID profileId, UUID passId, UUID productId,
			Instant from, Instant to, boolean includeUpcoming, int page, int size) {
		if (from != null && to != null && !from.isBefore(to)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "The date range must have a positive length.");
		}
		var participants = new ArrayList<Participant>();
		profiles.findOwnProfile(accountId).ifPresent(own -> {
			participants.add(new Participant(own.id(), own.displayName()));
			profiles.findChildrenOf(own.id()).forEach(child -> participants.add(new Participant(child.id(), child.displayName())));
		});
		Map<UUID, String> names = participants.stream().collect(Collectors.toMap(Participant::id, Participant::name));
		if (profileId != null && !names.containsKey(profileId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		Map<UUID, Pass> passes = history.passes(names.keySet()).stream().collect(Collectors.toMap(Pass::id, Function.identity()));
		Pass selected = passId == null ? null : passes.get(passId);
		if (passId != null && (selected == null || (profileId != null && !selected.profileId().equals(profileId)))) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Pass not found.");
		}
		Map<UUID, ProductReference> products = variants.productsOfVariants(passes.values().stream().map(Pass::variantId)
			.filter(id -> id != null).distinct().toList());
		List<ProductReference> productOptions = products.values().stream().distinct()
			.sorted(Comparator.comparing(ProductReference::name).thenComparing(ProductReference::id)).toList();
		if (productId != null && productOptions.stream().noneMatch(product -> product.id().equals(productId))) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Pass type not found.");
		}
		List<UUID> matchingPasses = passes.values().stream()
			.filter(pass -> profileId == null || pass.profileId().equals(profileId))
			.filter(pass -> passId == null || pass.id().equals(passId))
			.filter(pass -> productId == null || (pass.variantId() != null && products.containsKey(pass.variantId())
					&& products.get(pass.variantId()).id().equals(productId)))
			.map(Pass::id).toList();
		List<UUID> excluded = includeUpcoming ? List.of() : upcomingReservations(matchingPasses, names.keySet());
		var entries = history.list(matchingPasses, excluded, from, to, page, size);
		return new AccountHistory(enrich(entries, passes, names, products), List.copyOf(participants), productOptions,
				selected == null ? null : selected.name());
	}

	private List<UUID> upcomingReservations(List<UUID> passIds, java.util.Set<UUID> profileIds) {
		if (passIds.isEmpty()) return List.of();
		Map<UUID, UUID> eventsByReservation = jdbc.sql("""
				SELECT id, event_id FROM reservation
				WHERE customer_pass_id IN (:passes) AND customer_id IN (:profiles)
				""").param("passes", passIds).param("profiles", profileIds)
			.query((row, number) -> Map.entry(row.getObject("id", UUID.class), row.getObject("event_id", UUID.class)))
			.list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		Instant now = Instant.now();
		var upcomingEvents = schedule.findAllById(eventsByReservation.values().stream().distinct().toList()).stream()
			.filter(event -> event.endsAt().isAfter(now)).map(Occurrence::id).collect(Collectors.toSet());
		return eventsByReservation.entrySet().stream().filter(entry -> upcomingEvents.contains(entry.getValue()))
			.map(Map.Entry::getKey).toList();
	}

	private PageResponse<UsageItem> enrich(PageResponse<PassUsageHistory.Entry> entries, Map<UUID, Pass> passes,
			Map<UUID, String> names, Map<UUID, ProductReference> products) {
		List<UUID> ids = entries.items().stream().map(PassUsageHistory.Entry::reservationId)
			.filter(id -> id != null).toList();
		Map<UUID, Reservation> reservations = ids.isEmpty() ? Map.of() : jdbc.sql("""
				SELECT id, event_id, status, customer_id, customer_pass_id FROM reservation
				WHERE id IN (:ids) AND customer_id IN (:profiles) AND customer_pass_id IN (:passes)
				""").param("ids", ids).param("profiles", names.keySet()).param("passes", passes.keySet())
			.query((row, number) -> new Reservation(row.getObject("id", UUID.class),
					row.getObject("event_id", UUID.class), row.getString("status"),
					row.getObject("customer_id", UUID.class), row.getObject("customer_pass_id", UUID.class)))
			.list().stream().collect(Collectors.toMap(Reservation::id, Function.identity()));
		Map<UUID, Occurrence> events = schedule.findAllById(reservations.values().stream().map(Reservation::eventId).distinct().toList())
			.stream().collect(Collectors.toMap(Occurrence::id, Function.identity()));
		return entries.map(entry -> {
			Pass pass = passes.get(entry.passId());
			Reservation reservation = entry.reservationId() == null ? null : reservations.get(entry.reservationId());
			if (reservation != null && (!reservation.passId().equals(pass.id()) || !reservation.profileId().equals(pass.profileId()))) {
				reservation = null;
			}
			Occurrence event = reservation == null ? null : events.get(reservation.eventId());
			boolean correction = "CORRECTION".equals(entry.kind());
			String kind = !correction && event == null ? "REVIEW" : entry.kind();
			ProductReference product = pass.variantId() == null ? null : products.get(pass.variantId());
			return new UsageItem(entry.id(), reservation == null ? null : entry.reservationId(), kind,
					"REVIEW".equals(kind) ? null : entry.entriesChange(), entry.occurredAt(),
					event == null ? null : event.id(), event == null ? null : event.title(),
					event == null ? null : event.serviceTypeName(), event == null ? null : event.startsAt(),
					event == null ? null : event.endsAt(), event != null
							&& ("CANCELLED".equals(event.status()) || "CANCELLED".equals(reservation.status())),
					pass.profileId(), names.get(pass.profileId()), pass.id(), pass.name(),
					product == null ? null : product.id());
		});
	}

	private record Reservation(UUID id, UUID eventId, String status, UUID profileId, UUID passId) {
	}

	record Participant(UUID id, String name) {
	}

	record AccountHistory(PageResponse<UsageItem> entries, List<Participant> participants,
			List<ProductReference> products, String selectedPassName) {
	}

	record UsageItem(UUID id, UUID reservationId, String kind, Integer entriesChange, Instant occurredAt,
			UUID eventId, String title, String serviceTypeName, Instant startsAt, Instant endsAt, boolean cancelled,
			UUID profileId, String profileName, UUID passId, String passName, UUID productId) {
	}

}
