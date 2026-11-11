package studioforyou.pl.backend.trainings.internal;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.customer.StaffAssignments;
import studioforyou.pl.backend.customer.StaffAssignments.ActiveAssignment;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.shared.api.UserText;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.trainings.internal.TraineeCardDtos.AddHealthNoteRequest;
import studioforyou.pl.backend.trainings.internal.TraineeCardDtos.HealthNoteResponse;
import studioforyou.pl.backend.trainings.internal.TraineeCardDtos.TraineeCardResponse;
import studioforyou.pl.backend.trainings.internal.TraineeCardDtos.UpdateTraineeCardRequest;

/**
 * The trainee card a trainer keeps for a participant they currently work with (#99): goals, sport
 * experience and the health notes the participant shared with them. A participant has one card,
 * shared by all of their current trainers, so an update names the version it is based on.
 * <p>
 * The card is available only during an active assignment to an active participant; otherwise it
 * is reported as not found, like the trainee's profile. Health notes additionally need the
 * {@value #HEALTH_PERMISSION} permission and the participant's consent on the trainer's own
 * assignment. Notes are never edited: a trainer adds them and archives those that no longer apply.
 */
@Service
@Transactional
class TraineeCardService {

	/** The permission that, together with the participant's consent, opens their health notes. */
	static final String HEALTH_PERMISSION = "CUSTOMER_HEALTH_READ";

	static final int MAX_CARD_TEXT_LENGTH = 2000;

	static final int MAX_HEALTH_NOTE_LENGTH = 1000;

	private final JdbcClient jdbc;

	private final StaffProfiles staff;

	private final CustomerProfiles customers;

	private final StaffAssignments assignments;

	TraineeCardService(JdbcClient jdbc, StaffProfiles staff, CustomerProfiles customers,
			StaffAssignments assignments) {
		this.jdbc = jdbc;
		this.staff = staff;
		this.customers = customers;
		this.assignments = assignments;
	}

	@Transactional(readOnly = true)
	TraineeCardResponse get(UUID customerId, Trainer trainer) {
		return card(customerId, access(customerId, trainer));
	}

	/**
	 * Changes the goals and/or sport experience present in the request. The first change of a
	 * participant's card creates it.
	 */
	TraineeCardResponse update(UUID customerId, Trainer trainer, UpdateTraineeCardRequest request) {
		String goals = request.goalsPresent() ? optionalText("goals", request.getGoals()) : null;
		String experience = request.sportExperiencePresent()
				? optionalText("sportExperience", request.getSportExperience()) : null;
		HealthAccess access = access(customerId, trainer);
		Optional<CardRow> current = jdbc.sql("""
				SELECT goals, sport_experience, version, updated_by, updated_at
				FROM trainee_card
				WHERE customer_id = :customerId
				FOR UPDATE
				""")
			.param("customerId", customerId)
			.query(CardRow.class)
			.optional();
		if (current.map(CardRow::version).orElse(0) != request.getVersion()) {
			throw versionConflict();
		}
		if (!request.goalsPresent() && !request.sportExperiencePresent()) {
			return card(customerId, access);
		}
		if (current.isPresent()) {
			jdbc.sql("""
					UPDATE trainee_card
					SET goals = :goals, sport_experience = :experience, updated_by = :accountId,
					    updated_at = now(), version = version + 1
					WHERE customer_id = :customerId
					""")
				.param("customerId", customerId)
				.param("goals", request.goalsPresent() ? goals : current.get().goals())
				.param("experience", request.sportExperiencePresent() ? experience : current.get().sportExperience())
				.param("accountId", trainer.accountId())
				.update();
		}
		else {
			int created = jdbc.sql("""
					INSERT INTO trainee_card (customer_id, goals, sport_experience, updated_by, updated_at, version)
					VALUES (:customerId, :goals, :experience, :accountId, now(), 1)
					ON CONFLICT (customer_id) DO NOTHING
					""")
				.param("customerId", customerId)
				.param("goals", goals)
				.param("experience", experience)
				.param("accountId", trainer.accountId())
				.update();
			if (created == 0) {
				// Another trainer filled in the card at the same moment.
				throw versionConflict();
			}
		}
		return card(customerId, access);
	}

	/** Adds a current health note reported by the signed-in trainer. */
	HealthNoteResponse addHealthNote(UUID customerId, Trainer trainer, AddHealthNoteRequest request) {
		String description = requiredText("description", request.description(), MAX_HEALTH_NOTE_LENGTH);
		requireHealthAccess(access(customerId, trainer));
		UUID noteId = jdbc.sql("""
				INSERT INTO health_note (customer_id, kind, description, reported_by)
				VALUES (:customerId, :kind, :description, :accountId)
				RETURNING id
				""")
			.param("customerId", customerId)
			.param("kind", request.kind().name())
			.param("description", description)
			.param("accountId", trainer.accountId())
			.query(UUID.class)
			.single();
		return note(customerId, noteId);
	}

	@Transactional(readOnly = true)
	HealthNoteResponse getHealthNote(UUID customerId, UUID noteId, Trainer trainer) {
		requireHealthAccess(access(customerId, trainer));
		return note(customerId, noteId);
	}

	/**
	 * Archives a health note that no longer applies, recording the signed-in trainer. The note stays
	 * on the card as history. An already archived note is returned unchanged, so a retry succeeds.
	 */
	HealthNoteResponse archiveHealthNote(UUID customerId, UUID noteId, Trainer trainer) {
		requireHealthAccess(access(customerId, trainer));
		jdbc.sql("""
				UPDATE health_note
				SET archived_at = now(), archived_by = :accountId
				WHERE id = :noteId AND customer_id = :customerId AND archived_at IS NULL
				""")
			.param("noteId", noteId)
			.param("customerId", customerId)
			.param("accountId", trainer.accountId())
			.update();
		return note(customerId, noteId);
	}

	/**
	 * What the trainer may see of the participant's health, after checking that they currently work
	 * with the active participant.
	 */
	private HealthAccess access(UUID customerId, Trainer trainer) {
		UUID staffId = staff.getByAccount(trainer.accountId()).id();
		ActiveAssignment assignment = assignments.findActive(customerId, staffId)
			.orElseThrow(TraineeCardService::notAssigned);
		if (!customers.findById(customerId).map(ProfileDetails::active).orElse(false)) {
			throw notAssigned();
		}
		if (!trainer.healthPermission()) {
			return HealthAccess.NO_PERMISSION;
		}
		return assignment.healthShared() ? HealthAccess.GRANTED : HealthAccess.NOT_SHARED;
	}

	private TraineeCardResponse card(UUID customerId, HealthAccess access) {
		Optional<CardRow> card = jdbc.sql("""
				SELECT goals, sport_experience, version, updated_by, updated_at
				FROM trainee_card
				WHERE customer_id = :customerId
				""")
			.param("customerId", customerId)
			.query(CardRow.class)
			.optional();
		List<NoteRow> notes = (access == HealthAccess.GRANTED) ? jdbc.sql("""
				SELECT id, kind, description, reported_by, created_at, archived_at, archived_by
				FROM health_note
				WHERE customer_id = :customerId
				ORDER BY archived_at IS NOT NULL, COALESCE(archived_at, created_at) DESC, id
				""")
			.param("customerId", customerId)
			.query(NoteRow.class)
			.list() : List.of();
		Set<UUID> authors = new LinkedHashSet<>();
		card.map(CardRow::updatedBy).ifPresent(authors::add);
		notes.forEach((note) -> authors.addAll(note.authors()));
		Map<UUID, String> names = names(authors);
		return new TraineeCardResponse(customerId, card.map(CardRow::goals).orElse(null),
				card.map(CardRow::sportExperience).orElse(null), card.map(CardRow::version).orElse(0),
				card.map(CardRow::updatedAt).map(OffsetDateTime::toInstant).orElse(null),
				card.map(CardRow::updatedBy).map(names::get).orElse(null), access,
				(access == HealthAccess.GRANTED) ? notes.stream().map((note) -> note.toResponse(names)).toList()
						: null);
	}

	private HealthNoteResponse note(UUID customerId, UUID noteId) {
		NoteRow note = jdbc.sql("""
				SELECT id, kind, description, reported_by, created_at, archived_at, archived_by
				FROM health_note
				WHERE id = :noteId AND customer_id = :customerId
				""")
			.param("noteId", noteId)
			.param("customerId", customerId)
			.query(NoteRow.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Health note not found."));
		return note.toResponse(names(note.authors()));
	}

	/** Names of the people who wrote on a card; a staff member's name wins, as in conversations. */
	private Map<UUID, String> names(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> names = new HashMap<>(customers.displayNamesByAccount(accountIds));
		names.putAll(staff.displayNamesByAccount(accountIds));
		return names;
	}

	private static void requireHealthAccess(HealthAccess access) {
		switch (access) {
			case GRANTED -> {
			}
			case NO_PERMISSION -> throw new ApiException(ErrorCode.ACCESS_DENIED,
					"The trainer does not have the " + HEALTH_PERMISSION + " permission.");
			case NOT_SHARED -> throw new ApiException(ErrorCode.HEALTH_DATA_NOT_SHARED,
					"The participant has not shared their health data with the trainer.");
		}
	}

	/** Trimmed text of an optional card field; blank text clears it. */
	private static @Nullable String optionalText(String field, @Nullable String value) {
		String text = UserText.trimToNull(value);
		if (text != null && text.length() > MAX_CARD_TEXT_LENGTH) {
			throw tooLong(field, MAX_CARD_TEXT_LENGTH);
		}
		return text;
	}

	private static String requiredText(String field, @Nullable String value, int maxLength) {
		String text = UserText.trimToNull(value);
		if (text == null) {
			throw ApiException.invalidField(field, FieldErrorCode.REQUIRED, "must not be blank");
		}
		if (text.length() > maxLength) {
			throw tooLong(field, maxLength);
		}
		return text;
	}

	private static ApiException tooLong(String field, int maxLength) {
		return ApiException.invalidField(field, FieldErrorCode.TOO_LONG,
				"must be at most " + maxLength + " characters");
	}

	private static ApiException notAssigned() {
		return new ApiException(ErrorCode.NOT_FOUND, "The trainer is not assigned to the participant.");
	}

	private static ApiException versionConflict() {
		return new ApiException(ErrorCode.VERSION_CONFLICT,
				"The trainee card was changed by another request. Reload it and try again.");
	}

	/**
	 * The signed-in trainer.
	 *
	 * @param healthPermission whether the account has the {@value #HEALTH_PERMISSION} permission
	 */
	record Trainer(UUID accountId, boolean healthPermission) {

	}

	private record CardRow(@Nullable String goals, @Nullable String sportExperience, int version,
			@Nullable UUID updatedBy, OffsetDateTime updatedAt) {

	}

	private record NoteRow(UUID id, HealthNoteKind kind, String description, UUID reportedBy,
			OffsetDateTime createdAt, @Nullable OffsetDateTime archivedAt, @Nullable UUID archivedBy) {

		Set<UUID> authors() {
			Set<UUID> authors = new LinkedHashSet<>();
			authors.add(reportedBy);
			if (archivedBy != null) {
				authors.add(archivedBy);
			}
			return authors;
		}

		HealthNoteResponse toResponse(Map<UUID, String> names) {
			return new HealthNoteResponse(id, kind, description, createdAt.toInstant(), names.get(reportedBy),
					(archivedAt == null) ? null : archivedAt.toInstant(),
					(archivedBy == null) ? null : names.get(archivedBy));
		}

	}

}
