package studioforyou.pl.backend.customer;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.audit.AuditLog.Change;

/**
 * Records changes of a participant's personal and contact details in the audit log, so the
 * participant's trainers can see the history of their profile. Call it inside the transaction of
 * the change. An edit that changes nothing records nothing.
 */
@Component
public class ProfileChangeLog {

	/** The module of the profile change records. */
	public static final String MODULE = "customer";

	/** The record type of the profile change records; the record id is the profile id. */
	public static final String ENTITY_TYPE = "customer_profile";

	/** The action of a change of personal or contact details. */
	public static final String PROFILE_UPDATED = "PROFILE_UPDATED";

	private final AuditLog auditLog;

	ProfileChangeLog(AuditLog auditLog) {
		this.auditLog = auditLog;
	}

	/**
	 * Appends one record with every field whose value differs, or nothing when none does.
	 *
	 * @param actorAccountId the account that made the change, or {@code null} for the system
	 */
	public void record(UUID profileId, @Nullable UUID actorAccountId, Details before, Details after) {
		Map<String, Change> changes = new LinkedHashMap<>();
		compare(changes, "firstName", before.firstName(), after.firstName());
		compare(changes, "lastName", before.lastName(), after.lastName());
		compare(changes, "birthDate", before.birthDate(), after.birthDate());
		compare(changes, "phone", before.phone(), after.phone());
		compare(changes, "emergencyContactName", before.emergencyContactName(), after.emergencyContactName());
		compare(changes, "emergencyPhone", before.emergencyPhone(), after.emergencyPhone());
		if (!changes.isEmpty()) {
			auditLog.append(new AuditLog.Entry(actorAccountId, MODULE, ENTITY_TYPE, profileId, PROFILE_UPDATED,
					changes, null));
		}
	}

	private static void compare(Map<String, Change> changes, String field, @Nullable Object before,
			@Nullable Object after) {
		if (!Objects.equals(before, after)) {
			changes.put(field, new Change(text(before), text(after)));
		}
	}

	private static @Nullable String text(@Nullable Object value) {
		return (value == null) ? null : value.toString();
	}

	/** The personal and contact details of a profile that its history shows. */
	public record Details(@Nullable String firstName, @Nullable String lastName, @Nullable LocalDate birthDate,
			@Nullable String phone, @Nullable String emergencyContactName, @Nullable String emergencyPhone) {

	}

}
