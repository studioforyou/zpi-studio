package studioforyou.pl.backend.communication.history;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.communication.history.NotificationHistoryDtos.DeliveryAttempt;
import studioforyou.pl.backend.communication.history.NotificationHistoryDtos.HistoryDetails;
import studioforyou.pl.backend.communication.history.NotificationHistoryDtos.HistoryItem;
import studioforyou.pl.backend.communication.history.NotificationHistoryStore.Filter;
import studioforyou.pl.backend.communication.history.NotificationHistoryStore.Row;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * The history of every notification the studio has sent, automatic ones and announcements, for the
 * administrator. The contents of chat messages are never part of it.
 */
@Service
@Transactional(readOnly = true)
class NotificationHistoryService {

	private final Clock clock = Clock.systemUTC();

	private final NotificationHistoryStore store;

	private final CustomerProfiles customers;

	private final StaffProfiles staff;

	NotificationHistoryService(NotificationHistoryStore store, CustomerProfiles customers, StaffProfiles staff) {
		this.store = store;
		this.customers = customers;
		this.staff = staff;
	}

	PageResponse<HistoryItem> page(@Nullable String type, @Nullable UUID accountId, @Nullable Instant from,
			@Nullable Instant to, @Nullable SendStatus status, int page, int size) {
		if (type != null && NotificationType.fromCode(type).isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown notification type: " + type + ".");
		}
		if (from != null && to != null && !from.isBefore(to)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "The period must end after it starts.");
		}
		PageResponse<Row> rows = store.page(new Filter(type, accountId, from, to, status), clock.instant(), page, size);
		List<HistoryItem> items = items(rows.items());
		return new PageResponse<>(items, rows.page(), rows.size(), rows.totalItems(), rows.totalPages());
	}

	HistoryDetails details(UUID id) {
		Row row = store.find(id, clock.instant())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Notification not found."));
		List<DeliveryAttempt> deliveries = store.deliveries(id).stream()
			.map((delivery) -> new DeliveryAttempt(delivery.attemptedAt().toInstant(), delivery.channel(),
					delivery.status(), delivery.error()))
			.toList();
		return new HistoryDetails(items(List.of(row)).getFirst(), deliveries);
	}

	private List<HistoryItem> items(List<Row> rows) {
		Map<UUID, String> names = accountNames(rows.stream().map(Row::recipientId).distinct().toList());
		Map<UUID, String> subjects = customers.displayNames(
				rows.stream().map(Row::subjectCustomerId).filter(Objects::nonNull).distinct().toList());
		return rows.stream().map((row) -> {
			boolean hidden = NotificationType.NEW_MESSAGE.name().equals(row.type());
			String about = row.about() != null ? row.about()
					: row.subjectCustomerId() == null ? null : subjects.get(row.subjectCustomerId());
			return new HistoryItem(row.id(), row.type(), row.recipientId(), names.getOrDefault(row.recipientId(), "—"),
					role(row.recipientRole()), about, row.title(), hidden ? null : row.body(), hidden, row.marketing(),
					row.announcementId(), row.author(), row.sentAt().toInstant(),
					row.readAt() == null ? null : row.readAt().toInstant(), SendStatus.valueOf(row.status()));
		}).toList();
	}

	private Map<UUID, String> accountNames(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> names = new LinkedHashMap<>(staff.displayNamesByAccount(accountIds));
		names.putAll(customers.displayNamesByAccount(accountIds));
		return names;
	}

	private static @Nullable NotificationRole role(@Nullable String role) {
		if (role == null) {
			return null;
		}
		try {
			return NotificationRole.valueOf(role);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
