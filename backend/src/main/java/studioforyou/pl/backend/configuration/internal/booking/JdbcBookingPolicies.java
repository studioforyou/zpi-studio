package studioforyou.pl.backend.configuration.internal.booking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

@Service
@Transactional
class JdbcBookingPolicies implements BookingPolicies {

	private static final Values FALLBACK = new Values(true, 12, true, true, 14, 30, true, 0, false, 3, 3);

	private final JdbcClient jdbc;

	private record PolicyRow(UUID id, Values values) {
	}

	JdbcBookingPolicies(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public Values studioDefaults() {
		return merge(studioPolicy().values(), FALLBACK);
	}

	private PolicyRow studioPolicy() {
		return jdbc.sql("""
				SELECT * FROM booking_policy
				WHERE scope = 'STUDIO' AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now())
				ORDER BY valid_from DESC, created_at DESC, id LIMIT 1
				""").query((rs, row) -> new PolicyRow(rs.getObject("id", UUID.class), values(rs)))
			.optional().orElse(new PolicyRow(null, null));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Definition> findForServices(Collection<ServiceContext> services) {
		if (services.isEmpty()) {
			return Map.of();
		}
		PolicyRow studioRow = studioPolicy();
		Values studio = merge(studioRow.values(), FALLBACK);
		Map<UUID, PolicyRow> overrides = new HashMap<>();
		jdbc.sql("""
				SELECT DISTINCT ON (service_type_id) * FROM booking_policy
				WHERE scope = 'SERVICE_TYPE' AND service_type_id IN (:ids)
				AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now())
				ORDER BY service_type_id, valid_from DESC, created_at DESC, id
				""").param("ids", services.stream().map(ServiceContext::id).toList())
			.query((rs, row) -> {
				overrides.put(rs.getObject("service_type_id", UUID.class),
						new PolicyRow(rs.getObject("id", UUID.class), values(rs)));
				return rs.getObject("id", UUID.class);
			}).list();
		Map<UUID, Definition> result = new HashMap<>();
		for (ServiceContext service : services) {
			PolicyRow custom = overrides.get(service.id());
			Values values = custom == null ? null : custom.values();
			result.put(service.id(), new Definition(custom == null ? studioRow.id() : custom.id(), studioRow.id(),
					values, effective(service, values, studio)));
		}
		return result;
	}

	@Override
	public void replaceForService(ServiceContext service, Values overrides, UUID changedBy) {
		Values resolved = effective(service, overrides, studioDefaults());
		if (!service.groupRulesAllowed() && overrides != null) {
			if (Boolean.TRUE.equals(overrides.autoCancelEnabled())) {
				throw ApiException.invalidField("bookingPolicy.autoCancelEnabled", FieldErrorCode.OUT_OF_RANGE,
						"Personal training does not use attendance cancellation.");
			}
			if (Boolean.TRUE.equals(overrides.waitlistEnabled())) {
				throw ApiException.invalidField("bookingPolicy.waitlistEnabled", FieldErrorCode.OUT_OF_RANGE,
						"Personal training does not use waitlists.");
			}
		}
		if (Boolean.TRUE.equals(resolved.autoCancelEnabled()) && service.maxParticipants() != null
				&& resolved.autoCancelMinParticipants() > service.maxParticipants()) {
			throw ApiException.invalidField("bookingPolicy.autoCancelMinParticipants", FieldErrorCode.OUT_OF_RANGE,
					"The automatic cancellation threshold cannot exceed the service capacity.");
		}
		Values previous = jdbc.sql("""
				SELECT * FROM booking_policy WHERE scope = 'SERVICE_TYPE'
				AND service_type_id = :service AND valid_to IS NULL FOR UPDATE
				""").param("service", service.id()).query((rs, row) -> values(rs)).optional().orElse(null);
		if (Objects.equals(previous, overrides)) {
			return;
		}
		jdbc.sql("""
				UPDATE booking_policy SET valid_to = now()
				WHERE scope = 'SERVICE_TYPE' AND service_type_id = :service AND valid_to IS NULL
				""").param("service", service.id()).update();
		if (overrides == null) {
			return;
		}
		jdbc.sql("""
				INSERT INTO booking_policy (scope, service_type_id, cancellation_allowed, free_cancel_hours,
				late_cancel_allowed, late_cancel_forfeits_entry, booking_opens_days_before,
				booking_closes_minutes_before, waitlist_enabled, waitlist_confirm_minutes,
				auto_cancel_enabled, auto_cancel_min_participants, auto_cancel_hours_before, created_by)
				VALUES ('SERVICE_TYPE', :service, :cancel, :freeHours, :late, :forfeit, :opens, :closes,
				:waitlist, :confirm, :autoCancel, :minimum, :checkHours, :actor)
				""").param("service", service.id()).param("cancel", overrides.cancellationAllowed())
			.param("freeHours", overrides.freeCancelHours()).param("late", overrides.lateCancelAllowed())
			.param("forfeit", overrides.lateCancelForfeitsEntry()).param("opens", overrides.bookingOpensDaysBefore())
			.param("closes", overrides.bookingClosesMinutesBefore()).param("waitlist", overrides.waitlistEnabled())
			.param("confirm", overrides.waitlistConfirmMinutes()).param("autoCancel", overrides.autoCancelEnabled())
			.param("minimum", overrides.autoCancelMinParticipants()).param("checkHours", overrides.autoCancelHoursBefore())
			.param("actor", changedBy).update();
	}

	private static Values effective(ServiceContext service, Values overrides, Values studio) {
		Values rules = merge(overrides, studio);
		Integer minimum = overrides != null && overrides.autoCancelMinParticipants() != null
				? overrides.autoCancelMinParticipants()
				: Objects.requireNonNullElse(service.minParticipants(), rules.autoCancelMinParticipants());
		return new Values(rules.cancellationAllowed(), rules.freeCancelHours(), rules.lateCancelAllowed(),
				rules.lateCancelForfeitsEntry(), rules.bookingOpensDaysBefore(), rules.bookingClosesMinutesBefore(),
				service.groupRulesAllowed() && Boolean.TRUE.equals(rules.waitlistEnabled()), rules.waitlistConfirmMinutes(),
				service.groupRulesAllowed() && Boolean.TRUE.equals(rules.autoCancelEnabled()),
				service.groupRulesAllowed() ? minimum : null,
				service.groupRulesAllowed() ? rules.autoCancelHoursBefore() : null);
	}

	private static Values merge(Values child, Values parent) {
		if (child == null) {
			return parent;
		}
		return new Values(inherit(child.cancellationAllowed(), parent.cancellationAllowed()),
				inherit(child.freeCancelHours(), parent.freeCancelHours()),
				inherit(child.lateCancelAllowed(), parent.lateCancelAllowed()),
				inherit(child.lateCancelForfeitsEntry(), parent.lateCancelForfeitsEntry()),
				inherit(child.bookingOpensDaysBefore(), parent.bookingOpensDaysBefore()),
				inherit(child.bookingClosesMinutesBefore(), parent.bookingClosesMinutesBefore()),
				inherit(child.waitlistEnabled(), parent.waitlistEnabled()),
				inherit(child.waitlistConfirmMinutes(), parent.waitlistConfirmMinutes()),
				inherit(child.autoCancelEnabled(), parent.autoCancelEnabled()),
				inherit(child.autoCancelMinParticipants(), parent.autoCancelMinParticipants()),
				inherit(child.autoCancelHoursBefore(), parent.autoCancelHoursBefore()));
	}

	private static <T> T inherit(T value, T fallback) {
		return value == null ? fallback : value;
	}

	private static Values values(ResultSet rs) throws SQLException {
		return new Values(rs.getObject("cancellation_allowed", Boolean.class), number(rs, "free_cancel_hours"),
				rs.getObject("late_cancel_allowed", Boolean.class), rs.getObject("late_cancel_forfeits_entry", Boolean.class),
				number(rs, "booking_opens_days_before"), number(rs, "booking_closes_minutes_before"),
				rs.getObject("waitlist_enabled", Boolean.class), number(rs, "waitlist_confirm_minutes"),
				rs.getObject("auto_cancel_enabled", Boolean.class), number(rs, "auto_cancel_min_participants"),
				number(rs, "auto_cancel_hours_before"));
	}

	private static Integer number(ResultSet rs, String column) throws SQLException {
		Number value = (Number) rs.getObject(column);
		return value == null ? null : value.intValue();
	}

}
