package studioforyou.pl.backend.trainings.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.audit.AuditLog.Recorded;
import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.ProfileChangeLog;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.trainings.internal.TraineeCardService.Trainer;

/**
 * The change history of a participant's profile for a trainer who currently works with them (#56):
 * personal and contact details, the trainee card's goals and sport experience, and added or archived
 * health notes. Health entries are shown only with access to the participant's health data, and
 * never with the note's text. The history starts when the changes began to be recorded.
 */
@Service
@Transactional(readOnly = true)
class TraineeProfileHistory {

	private static final List<String> FIELD_ORDER = List.of("firstName", "lastName", "birthDate", "phone",
			"emergencyContactName", "emergencyPhone", "goals", "sportExperience");

	private final TraineeCardService cards;

	private final AuditLog auditLog;

	private final JdbcClient jdbc;

	private final CustomerProfiles customers;

	private final StaffProfiles staff;

	TraineeProfileHistory(TraineeCardService cards, AuditLog auditLog, JdbcClient jdbc, CustomerProfiles customers,
			StaffProfiles staff) {
		this.cards = cards;
		this.auditLog = auditLog;
		this.jdbc = jdbc;
		this.customers = customers;
		this.staff = staff;
	}

	/**
	 * One page of the history, newest first.
	 *
	 * @param group only entries of this group, or {@code null} for all
	 */
	ProfileHistoryResponse history(UUID customerId, Trainer trainer, @Nullable ProfileHistoryGroup group, int page,
			int size) {
		HealthAccess access = cards.access(customerId, trainer);
		boolean health = access == HealthAccess.GRANTED;
		if (group == ProfileHistoryGroup.HEALTH && !health) {
			return new ProfileHistoryResponse(access, List.of(), page, size, 0, 0);
		}

		List<Item> items = new ArrayList<>();
		if (group == null || group == ProfileHistoryGroup.PERSONAL) {
			auditLog.history(ProfileChangeLog.MODULE, ProfileChangeLog.ENTITY_TYPE, List.of(customerId))
				.forEach((recorded) -> items.add(new Item(recorded, ProfileHistoryGroup.PERSONAL, null)));
		}
		if (group == null || group == ProfileHistoryGroup.CARD) {
			auditLog.history(TraineeCardService.MODULE, TraineeCardService.CARD, List.of(customerId))
				.forEach((recorded) -> items.add(new Item(recorded, ProfileHistoryGroup.CARD, null)));
		}
		if (health && (group == null || group == ProfileHistoryGroup.HEALTH)) {
			Map<UUID, HealthNoteKind> notes = healthNoteKinds(customerId);
			auditLog.history(TraineeCardService.MODULE, TraineeCardService.HEALTH_NOTE, notes.keySet())
				.forEach((recorded) -> items
					.add(new Item(recorded, ProfileHistoryGroup.HEALTH, notes.get(recorded.entityId()))));
		}
		items.sort(Comparator.comparing((Item item) -> item.recorded().occurredAt())
			.thenComparing((item) -> item.recorded().id())
			.reversed());

		int from = (int) Math.min((long) page * size, items.size());
		List<Item> pageItems = items.subList(from, Math.min(from + size, items.size()));
		Map<UUID, String> names = names(pageItems.stream()
			.map((item) -> item.recorded().actorAccountId())
			.filter(Objects::nonNull)
			.toList());
		CustomerKind kind = customers.findById(customerId).orElseThrow().kind();
		Set<UUID> actingFor = customers.findAccountsActingFor(List.of(customerId));
		List<ProfileHistoryEntry> entries = pageItems.stream()
			.map((item) -> entry(item, names, kind, actingFor))
			.toList();
		int totalPages = (int) ((items.size() + size - 1) / size);
		return new ProfileHistoryResponse(access, entries, page, size, items.size(), totalPages);
	}

	private ProfileHistoryEntry entry(Item item, Map<UUID, String> names, CustomerKind kind, Set<UUID> actingFor) {
		Recorded recorded = item.recorded();
		UUID actorId = recorded.actorAccountId();
		Actor actor = null;
		if (actorId != null) {
			actor = new Actor(names.get(actorId), role(item.group(), actorId, kind, actingFor));
		}
		List<FieldChange> changes = recorded.changes()
			.entrySet()
			.stream()
			.map((change) -> new FieldChange(change.getKey(), text(change.getValue().oldValue()),
					text(change.getValue().newValue())))
			.sorted(Comparator.comparingInt((FieldChange change) -> fieldOrder(change.field())))
			.toList();
		boolean healthEntry = item.group() == ProfileHistoryGroup.HEALTH;
		return new ProfileHistoryEntry(recorded.id(), recorded.occurredAt(), item.group(), recorded.action(), actor,
				changes, healthEntry ? recorded.entityId() : null, item.healthNoteKind());
	}

	/**
	 * Who the author is to the participant: the participant themselves or a guardian of a child;
	 * otherwise the studio's staff in the panel for personal details, and a trainer for the card and
	 * health notes.
	 */
	private static ActorRole role(ProfileHistoryGroup group, UUID actorId, CustomerKind kind, Set<UUID> actingFor) {
		if (actingFor.contains(actorId)) {
			return (kind == CustomerKind.CHILD) ? ActorRole.GUARDIAN : ActorRole.CLIENT;
		}
		return (group == ProfileHistoryGroup.PERSONAL) ? ActorRole.ADMIN : ActorRole.TRAINER;
	}

	/** Fields in the order of the profile screen; the stored changes have no order. */
	private static int fieldOrder(String field) {
		int index = FIELD_ORDER.indexOf(field);
		return (index < 0) ? FIELD_ORDER.size() : index;
	}

	private Map<UUID, HealthNoteKind> healthNoteKinds(UUID customerId) {
		Map<UUID, HealthNoteKind> kinds = new HashMap<>();
		jdbc.sql("SELECT id, kind FROM health_note WHERE customer_id = :customerId")
			.param("customerId", customerId)
			.query((rs, row) -> kinds.put(rs.getObject("id", UUID.class), HealthNoteKind.valueOf(rs.getString("kind"))))
			.list();
		return kinds;
	}

	/** Names of the authors; a staff member's name wins, as on the card. */
	private Map<UUID, String> names(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> names = new HashMap<>(customers.displayNamesByAccount(accountIds));
		names.putAll(staff.displayNamesByAccount(accountIds));
		return names;
	}

	private static @Nullable String text(@Nullable Object value) {
		return (value == null) ? null : value.toString();
	}

	private record Item(Recorded recorded, ProfileHistoryGroup group, @Nullable HealthNoteKind healthNoteKind) {

	}

	/** What part of the profile an entry is about. */
	enum ProfileHistoryGroup {

		/** Name, birth date, phone and emergency contact. */
		PERSONAL,

		/** Goals and sport experience on the trainee card. */
		CARD,

		/** Health notes added or archived; shown only with access to the participant's health data. */
		HEALTH

	}

	/** Who the author of a change is to the participant. */
	enum ActorRole {

		CLIENT, GUARDIAN, TRAINER, ADMIN

	}

	@Schema(name = "TraineeProfileHistory", description = "One page of the change history, newest first.")
	record ProfileHistoryResponse(
			@Schema(description = "Whether health entries are included: only with GRANTED.") HealthAccess healthAccess,
			List<ProfileHistoryEntry> items, int page, int size, long totalItems, int totalPages) {

	}

	/**
	 * One change of the profile.
	 *
	 * @param actor who made the change, or {@code null} for the system
	 * @param changes changed fields with their old and new values; empty for health entries
	 * @param healthNoteId the added or archived note, for HEALTH entries
	 * @param healthNoteKind the kind of that note, for HEALTH entries
	 */
	@Schema(name = "TraineeProfileHistoryEntry")
	record ProfileHistoryEntry(long id, Instant occurredAt, ProfileHistoryGroup group,
			@Schema(description = "PROFILE_UPDATED, CARD_UPDATED, HEALTH_NOTE_ADDED or HEALTH_NOTE_ARCHIVED.",
					example = "PROFILE_UPDATED") String action,
			@Schema(nullable = true) @Nullable Actor actor, List<FieldChange> changes,
			@Schema(nullable = true) @Nullable UUID healthNoteId,
			@Schema(nullable = true) @Nullable HealthNoteKind healthNoteKind) {

	}

	/**
	 * The author of a change.
	 *
	 * @param name the author's name, or {@code null} if unknown
	 */
	@Schema(name = "TraineeProfileHistoryActor")
	record Actor(@Schema(nullable = true, example = "Katarzyna Zielińska") @Nullable String name, ActorRole role) {

	}

	/** A changed field; dates are ISO dates. */
	@Schema(name = "TraineeProfileFieldChange")
	record FieldChange(@Schema(description = "firstName, lastName, birthDate, phone, emergencyContactName, "
			+ "emergencyPhone, goals or sportExperience.", example = "phone") String field,
			@Schema(nullable = true, example = "500 100 200") @Nullable String oldValue,
			@Schema(nullable = true, example = "600 300 400") @Nullable String newValue) {

	}

}
