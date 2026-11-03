package studioforyou.pl.backend.customer.internal;

import java.util.UUID;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/** Reads and saves the personal details of active participant profiles. */
@Service
@Transactional
class CustomerPersonalDetails {

	private final JdbcClient jdbc;

	private final Validator validator;

	CustomerPersonalDetails(JdbcClient jdbc, Validator validator) {
		this.jdbc = jdbc;
		this.validator = validator;
	}

	@Transactional(readOnly = true)
	PersonalDetails get(UUID profileId) {
		return find(profileId, false);
	}

	PersonalDetails update(UUID profileId, UpdateCustomerProfileRequest request) {
		// Lock while merging a partial update so concurrent edits preserve omitted fields.
		PersonalDetails updated = request.applyTo(find(profileId, true));
		var violations = validator.validate(updated);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
		jdbc.sql("""
				UPDATE customer_profile
				SET first_name = :firstName, last_name = :lastName, birth_date = :birthDate, phone = :phone,
				    emergency_contact_name = :emergencyContactName, emergency_phone = :emergencyPhone,
				    updated_at = clock_timestamp()
				WHERE id = :id
				""")
			.param("firstName", updated.firstName())
			.param("lastName", updated.lastName())
			.param("birthDate", updated.birthDate())
			.param("phone", updated.phone())
			.param("emergencyContactName", updated.emergencyContactName())
			.param("emergencyPhone", updated.emergencyPhone())
			.param("id", profileId)
			.update();
		return updated;
	}

	private PersonalDetails find(UUID profileId, boolean lock) {
		return jdbc.sql("""
				SELECT id, kind, first_name, last_name, birth_date, phone, emergency_contact_name, emergency_phone
				FROM customer_profile
				WHERE id = :id AND status = 'ACTIVE'
				""" + (lock ? " FOR UPDATE" : ""))
			.param("id", profileId)
			.query(PersonalDetails.class)
			.optional()
			.orElseThrow(CustomerPersonalDetails::notFound);
	}

	static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Customer profile not found.");
	}

}
