package studioforyou.pl.backend.customer.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.customer.OwnTrainees;
import studioforyou.pl.backend.customer.PendingAccounts;
import studioforyou.pl.backend.customer.TrainerAccounts;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

@Service
@Transactional
public class TrainerAssignmentService implements OwnTrainees {

	private static final String SCOPE = "PERSONAL_TRAINING";

	private final JdbcClient jdbc;

	private final CustomerProfiles customers;

	private final StaffProfiles staff;

	private final TrainerAccounts trainerAccounts;

	private final PendingAccounts pendingAccounts;

	public TrainerAssignmentService(JdbcClient jdbc, CustomerProfiles customers, StaffProfiles staff,
			TrainerAccounts trainerAccounts, PendingAccounts pendingAccounts) {
		this.jdbc = jdbc;
		this.customers = customers;
		this.staff = staff;
		this.trainerAccounts = trainerAccounts;
		this.pendingAccounts = pendingAccounts;
	}

	/**
	 * The participant's current trainers, longest assigned first.
	 */
	@Transactional(readOnly = true)
	public List<AssignedTrainer> list(UUID customerId) {
		requireCustomer(customerId);
		List<AssignmentRow> rows = jdbc.sql("""
				SELECT staff_id, assigned_by, started_at
				FROM staff_assignment
				WHERE customer_id = :customerId AND scope = :scope AND ended_at IS NULL
				ORDER BY started_at, staff_id
				""")
			.param("customerId", customerId)
			.param("scope", SCOPE)
			.query(AssignmentRow.class)
			.list();
		Map<UUID, StaffMember> members = staff.findAllById(rows.stream().map(AssignmentRow::staffId).toList())
			.stream()
			.collect(Collectors.toMap(StaffMember::id, Function.identity()));
		return rows.stream().map((row) -> row.toTrainer(members.get(row.staffId()))).toList();
	}

	@Transactional(readOnly = true)
	public AssignedTrainer get(UUID customerId, UUID staffId) {
		requireCustomer(customerId);
		AssignmentRow row = findActive(customerId, staffId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "The trainer is not assigned to the participant."));
		return row.toTrainer(staff.findAllById(List.of(staffId)).getFirst());
	}

	/**
	 * Active staff members with the {@code TRAINER} role, sorted by last and first name: the
	 * ones that can be assigned to participants.
	 */
	@Transactional(readOnly = true)
	public List<StaffMember> listAssignable() {
		return onlyTrainers(staff.findAll(true));
	}

	/**
	 * Assigns an active staff member with the {@code TRAINER} role to an active participant.
	 */
	public AssignedTrainer assign(UUID customerId, UUID staffId, UUID actorAccountId) {
		ProfileDetails customer = requireCustomer(customerId);
		if (!customer.active()) {
			throw new ApiException(ErrorCode.CUSTOMER_NOT_ACTIVE, "The participant's profile is not active.");
		}
		StaffMember member = staff.findAllById(List.of(staffId))
			.stream()
			.filter(StaffMember::active)
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.STAFF_UNAVAILABLE,
					"The staff member does not exist or is not active."));
		if (onlyTrainers(List.of(member)).isEmpty()) {
			throw new ApiException(ErrorCode.STAFF_NOT_TRAINER, "Only a trainer can be assigned to a participant.");
		}
		if (findActive(customerId, staffId).isPresent()) {
			throw alreadyAssigned();
		}
		try {
			AssignmentRow row = jdbc.sql("""
					INSERT INTO staff_assignment (customer_id, staff_id, scope, assigned_by)
					VALUES (:customerId, :staffId, :scope, :assignedBy)
					RETURNING staff_id, assigned_by, started_at
					""")
				.param("customerId", customerId)
				.param("staffId", staffId)
				.param("scope", SCOPE)
				.param("assignedBy", actorAccountId)
				.query(AssignmentRow.class)
				.single();
			return row.toTrainer(member);
		}
		catch (DuplicateKeyException ex) {
			// The same trainer was assigned by another request at the same moment.
			throw alreadyAssigned();
		}
	}

	/**
	 * Active participants the signed-in trainer works with, sorted by last and first name.
	 */
	@Transactional(readOnly = true)
	public List<AssignedTrainee> listOwnTrainees(UUID trainerAccountId) {
		List<TraineeRow> rows = jdbc.sql("""
				SELECT c.id, c.kind, c.first_name, c.last_name, c.birth_date, c.status = 'ACTIVE' AS active,
				       c.account_id, a.assigned_by, a.started_at
				FROM staff_assignment a
				JOIN customer_profile c ON c.id = a.customer_id
				WHERE a.staff_id = :staffId AND a.scope = :scope AND a.ended_at IS NULL
				ORDER BY c.last_name, c.first_name, c.id
				""")
			.param("staffId", ownStaffId(trainerAccountId))
			.param("scope", SCOPE)
			.query(TraineeRow.class)
			.list();
		Set<UUID> invited = pendingAccounts
			.findInvited(rows.stream().map(TraineeRow::accountId).filter(Objects::nonNull).toList());
		return rows.stream()
			.map((row) -> row.toTrainee(row.accountId() != null && invited.contains(row.accountId())))
			.toList();
	}

	@Transactional(readOnly = true)
	public AssignedTrainee getOwnTrainee(UUID customerId, UUID trainerAccountId) {
		ProfileDetails customer = requireCustomer(customerId);
		AssignmentRow row = findActive(customerId, ownStaffId(trainerAccountId))
			.orElseThrow(TrainerAssignmentService::notAssigned);
		return row.toTrainee(customer, awaitingActivation(customerId));
	}

	/**
	 * Personal and contact details of an active participant the signed-in trainer currently works
	 * with. An ended assignment or an inactive profile is reported as not found, so the details
	 * stop being available as soon as the cooperation ends.
	 */
	@Transactional(readOnly = true)
	public TraineeProfile getOwnTraineeProfile(UUID customerId, UUID trainerAccountId) {
		AssignmentRow assignment = findActive(customerId, ownStaffId(trainerAccountId))
			.orElseThrow(TrainerAssignmentService::notAssigned);
		ProfileRow profile = jdbc.sql("""
				SELECT id, kind, first_name, last_name, birth_date, phone, emergency_contact_name, emergency_phone
				FROM customer_profile
				WHERE id = :id AND status = 'ACTIVE'
				""")
			.param("id", customerId)
			.query(ProfileRow.class)
			.optional()
			.orElseThrow(TrainerAssignmentService::notAssigned);
		List<Guardian> guardians = jdbc.sql("""
				SELECT guardian.id, guardian.first_name, guardian.last_name, guardian.phone, g.relation
				FROM guardianship g
				JOIN customer_profile guardian ON guardian.id = g.guardian_id AND guardian.status = 'ACTIVE'
				WHERE g.child_id = :id AND g.ended_at IS NULL
				ORDER BY g.created_at, guardian.id
				""")
			.param("id", customerId)
			.query(Guardian.class)
			.list();
		return new TraineeProfile(profile.id(), profile.kind(), profile.firstName(), profile.lastName(),
				profile.birthDate(), profile.phone(), profile.emergencyContactName(), profile.emergencyPhone(),
				guardians, assignment.startedAt().toInstant());
	}

	/**
	 * Assigns an active participant to the signed-in trainer, who is also recorded as the one
	 * who made the assignment. The rules are the same as when an administrator assigns them.
	 */
	public AssignedTrainee assignToSelf(UUID customerId, UUID trainerAccountId) {
		ProfileDetails customer = requireCustomer(customerId);
		StaffMember member = staff.findAllById(List.of(ownStaffId(trainerAccountId))).getFirst();
		return insert(customer, member, trainerAccountId).toTrainee(customer, awaitingActivation(customerId));
	}

	@Override
	public void assignToTrainer(UUID customerId, UUID trainerAccountId) {
		assignToSelf(customerId, trainerAccountId);
	}

	/**
	 * Ends the signed-in trainer's active assignment to the participant. The assignment stays as
	 * history.
	 */
	public void unassignFromSelf(UUID customerId, UUID trainerAccountId) {
		unassign(customerId, ownStaffId(trainerAccountId));
	}

	private AssignmentRow insert(ProfileDetails customer, StaffMember member, UUID actorAccountId) {
		if (!customer.active()) {
			throw new ApiException(ErrorCode.CUSTOMER_NOT_ACTIVE, "The participant's profile is not active.");
		}
		if (!member.active()) {
			throw staffUnavailable();
		}
		if (onlyTrainers(List.of(member)).isEmpty()) {
			throw new ApiException(ErrorCode.STAFF_NOT_TRAINER, "Only a trainer can be assigned to a participant.");
		}
		if (findActive(customer.id(), member.id()).isPresent()) {
			throw alreadyAssigned();
		}
		try {
			return jdbc.sql("""
					INSERT INTO staff_assignment (customer_id, staff_id, scope, assigned_by)
					VALUES (:customerId, :staffId, :scope, :assignedBy)
					RETURNING staff_id, assigned_by, started_at
					""")
				.param("customerId", customer.id())
				.param("staffId", member.id())
				.param("scope", SCOPE)
				.param("assignedBy", actorAccountId)
				.query(AssignmentRow.class)
				.single();
		}
		catch (DuplicateKeyException ex) {
			// The same trainer was assigned by another request at the same moment.
			throw alreadyAssigned();
		}
	}

	/**
	 * Ends the trainer's active assignment to the participant. The assignment stays as history.
	 */
	public void unassign(UUID customerId, UUID staffId) {
		requireCustomer(customerId);
		int ended = jdbc.sql("""
				UPDATE staff_assignment
				SET ended_at = now()
				WHERE customer_id = :customerId AND staff_id = :staffId AND scope = :scope AND ended_at IS NULL
				""")
			.param("customerId", customerId)
			.param("staffId", staffId)
			.param("scope", SCOPE)
			.update();
		if (ended == 0) {
			throw notAssigned();
		}
	}

	/** Whether the participant's own account was invited and not activated yet. */
	private boolean awaitingActivation(UUID customerId) {
		return jdbc.sql("SELECT account_id FROM customer_profile WHERE id = :id AND account_id IS NOT NULL")
			.param("id", customerId)
			.query(UUID.class)
			.optional()
			.map((accountId) -> !pendingAccounts.findInvited(List.of(accountId)).isEmpty())
			.orElse(false);
	}

	private Optional<AssignmentRow> findActive(UUID customerId, UUID staffId) {
		return jdbc.sql("""
				SELECT staff_id, assigned_by, started_at
				FROM staff_assignment
				WHERE customer_id = :customerId AND staff_id = :staffId AND scope = :scope AND ended_at IS NULL
				""")
			.param("customerId", customerId)
			.param("staffId", staffId)
			.param("scope", SCOPE)
			.query(AssignmentRow.class)
			.optional();
	}

	private List<StaffMember> onlyTrainers(List<StaffMember> members) {
		Map<UUID, UUID> accountIds = staff.findAccountIds(members.stream().map(StaffMember::id).toList());
		Set<UUID> trainers = trainerAccounts.findTrainers(accountIds.values());
		return members.stream().filter((member) -> trainers.contains(accountIds.get(member.id()))).toList();
	}

	/** The staff profile of the signed-in trainer, or {@code NOT_FOUND} if the account has none. */
	private UUID ownStaffId(UUID trainerAccountId) {
		return staff.getByAccount(trainerAccountId).id();
	}

	private ProfileDetails requireCustomer(UUID customerId) {
		return customers.findById(customerId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
	}

	private static ApiException staffUnavailable() {
		return new ApiException(ErrorCode.STAFF_UNAVAILABLE, "The staff member does not exist or is not active.");
	}

	private static ApiException notAssigned() {
		return new ApiException(ErrorCode.NOT_FOUND, "The trainer is not assigned to the participant.");
	}

	private static ApiException alreadyAssigned() {
		return new ApiException(ErrorCode.TRAINER_ALREADY_ASSIGNED,
				"The trainer is already assigned to the participant.");
	}

	private record AssignmentRow(UUID staffId, UUID assignedBy, OffsetDateTime startedAt) {

		AssignedTrainer toTrainer(StaffMember member) {
			return new AssignedTrainer(member, assignedBy, startedAt.toInstant());
		}

		AssignedTrainee toTrainee(ProfileDetails customer, boolean awaitingActivation) {
			return new AssignedTrainee(customer, assignedBy, startedAt.toInstant(), awaitingActivation);
		}

	}

	private record ProfileRow(UUID id, CustomerKind kind, String firstName, String lastName, LocalDate birthDate,
			String phone, String emergencyContactName, String emergencyPhone) {

	}

	private record TraineeRow(UUID id, CustomerKind kind, String firstName, String lastName, LocalDate birthDate,
			boolean active, UUID accountId, UUID assignedBy, OffsetDateTime startedAt) {

		AssignedTrainee toTrainee(boolean awaitingActivation) {
			return new AssignedTrainee(new ProfileDetails(id, kind, firstName, lastName, birthDate, active),
					assignedBy, startedAt.toInstant(), awaitingActivation);
		}

	}

	/**
	 * @param assignedBy the account that made the assignment
	 * @param assignedAt when the assignment started
	 */
	public record AssignedTrainer(StaffMember trainer, UUID assignedBy, Instant assignedAt) {

	}

	/**
	 * A participant assigned to a trainer.
	 *
	 * @param assignedBy the account that made the assignment
	 * @param assignedAt when the assignment started
	 * @param awaitingActivation whether the participant's own account was invited and the person
	 * has not accepted the invitation yet
	 */
	public record AssignedTrainee(ProfileDetails customer, UUID assignedBy, Instant assignedAt,
			boolean awaitingActivation) {

	}

	/**
	 * What a trainer may know about a participant they work with: no e-mail address and nothing
	 * about health.
	 *
	 * @param birthDate the date of birth, or {@code null} if unknown
	 * @param phone the participant's phone number, or {@code null}
	 * @param emergencyContactName the emergency contact, or {@code null} if none
	 * @param guardians current guardians of a child; empty for an adult
	 * @param assignedAt when the cooperation started
	 */
	public record TraineeProfile(UUID id, CustomerKind kind, String firstName, String lastName, LocalDate birthDate,
			String phone, String emergencyContactName, String emergencyPhone, List<Guardian> guardians,
			Instant assignedAt) {

	}

	/** A current guardian of a child participant. */
	public record Guardian(UUID id, String firstName, String lastName, String phone, String relation) {

	}

}
