package studioforyou.pl.backend.customer.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.customer.TrainerAccounts;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

/**
 * Trainers assigned to a participant ({@code staff_assignment}). A participant can have several
 * trainers at once. Ending an assignment keeps it as history instead of deleting it. For now
 * every assignment has the default scope, {@code PERSONAL_TRAINING}, and no access to health data.
 */
@Service
@Transactional
public class TrainerAssignmentService {

	private static final String SCOPE = "PERSONAL_TRAINING";

	private final JdbcClient jdbc;

	private final CustomerProfiles customers;

	private final StaffProfiles staff;

	private final TrainerAccounts trainerAccounts;

	public TrainerAssignmentService(JdbcClient jdbc, CustomerProfiles customers, StaffProfiles staff,
			TrainerAccounts trainerAccounts) {
		this.jdbc = jdbc;
		this.customers = customers;
		this.staff = staff;
		this.trainerAccounts = trainerAccounts;
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
			throw new ApiException(ErrorCode.NOT_FOUND, "The trainer is not assigned to the participant.");
		}
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

	private ProfileDetails requireCustomer(UUID customerId) {
		return customers.findById(customerId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
	}

	private static ApiException alreadyAssigned() {
		return new ApiException(ErrorCode.TRAINER_ALREADY_ASSIGNED,
				"The trainer is already assigned to the participant.");
	}

	private record AssignmentRow(UUID staffId, UUID assignedBy, OffsetDateTime startedAt) {

		AssignedTrainer toTrainer(StaffMember member) {
			return new AssignedTrainer(member, assignedBy, startedAt.toInstant());
		}

	}

	/**
	 * @param assignedBy the account that made the assignment
	 * @param assignedAt when the assignment started
	 */
	public record AssignedTrainer(StaffMember trainer, UUID assignedBy, Instant assignedAt) {

	}

}
