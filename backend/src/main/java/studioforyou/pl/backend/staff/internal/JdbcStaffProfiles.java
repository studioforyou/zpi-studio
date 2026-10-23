package studioforyou.pl.backend.staff.internal;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

/** Keeps staff persistence inside the module, including the account-to-profile lookup. */
@Service
@Transactional
public class JdbcStaffProfiles implements StaffProfiles {

	// Unicode White_Space plus BOM, matching the mobile client's String.trim().
	private static final Pattern EDGE_WHITESPACE = Pattern.compile(
			"^[\\s\\p{Z}\\u0085\\uFEFF]+|[\\s\\p{Z}\\u0085\\uFEFF]+$");

	private final JdbcClient jdbc;

	private final Validator validator;

	public JdbcStaffProfiles(JdbcClient jdbc, Validator validator) {
		this.jdbc = jdbc;
		this.validator = validator;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<StaffSummary> findByAccount(UUID accountId) {
		return jdbc.sql("""
				SELECT id, first_name || ' ' || last_name AS display_name
				FROM staff_profile
				WHERE account_id = :accountId
				""")
			.param("accountId", accountId)
			.query(StaffSummary.class)
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public StaffProfile getByAccount(UUID accountId) {
		return findProfile(accountId, false);
	}

	@Override
	public StaffProfile updateByAccount(UUID accountId, ProfileChanges changes) {
		// Lock while merging a partial update so concurrent edits preserve omitted fields.
		StaffProfile current = findProfile(accountId, true);
		StaffProfile updated = new StaffProfile(current.id(),
				changes.firstNamePresent() ? trim(changes.firstName()) : current.firstName(),
				changes.lastNamePresent() ? trim(changes.lastName()) : current.lastName(),
				changes.phonePresent() ? blankToNull(trim(changes.phone())) : current.phone());
		var violations = validator.validate(updated);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
		jdbc.sql("""
				UPDATE staff_profile
				SET first_name = :firstName, last_name = :lastName, phone = :phone,
				    updated_at = clock_timestamp()
				WHERE account_id = :accountId
				""")
			.param("firstName", updated.firstName())
			.param("lastName", updated.lastName())
			.param("phone", updated.phone())
			.param("accountId", accountId)
			.update();
		return updated;
	}

	private StaffProfile findProfile(UUID accountId, boolean lock) {
		return jdbc.sql("""
				SELECT id, first_name, last_name, phone
				FROM staff_profile
				WHERE account_id = :accountId
				""" + (lock ? " FOR UPDATE" : ""))
			.param("accountId", accountId)
			.query(StaffProfile.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Staff profile not found."));
	}

	private static String trim(String value) {
		return value == null ? null : EDGE_WHITESPACE.matcher(value).replaceAll("");
	}

	private static String blankToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

}
