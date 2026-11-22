package studioforyou.pl.backend.membership.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Make-up policies defined by the studio. A policy is read when a credit is granted, and the
 * credit keeps its own expiry, so a change applies only to credits granted afterwards.
 * Policies are deactivated, never deleted: products and credits keep referring to them.
 */
@Service
@Transactional
class MakeupPolicyManagement {

	private static final String SELECT = """
			SELECT policy.id, policy.name, policy.validity_days, policy.max_per_pass, policy.grant_on_in_time_cancel,
			       policy.grant_on_studio_cancel, policy.expiry_checked_against, policy.is_active, policy.created_at,
			       (SELECT count(*) FROM pass_product product
			        WHERE product.makeup_policy_id = policy.id AND product.is_active) AS product_count
			FROM makeup_policy policy
			""";

	private final JdbcClient jdbc;

	MakeupPolicyManagement(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param active {@code true} for active policies, {@code false} for inactive ones, {@code null} for all
	 */
	@Transactional(readOnly = true)
	List<MakeupPolicyResponse> list(Boolean active) {
		return jdbc.sql(SELECT + " WHERE (CAST(:active AS BOOLEAN) IS NULL OR policy.is_active = :active)"
				+ " ORDER BY policy.is_active DESC, lower(policy.name), policy.id")
			.param("active", active, Types.BOOLEAN)
			.query(MakeupPolicyManagement::map)
			.list();
	}

	@Transactional(readOnly = true)
	MakeupPolicyResponse get(UUID id) {
		return jdbc.sql(SELECT + " WHERE policy.id = :id")
			.param("id", id)
			.query(MakeupPolicyManagement::map)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Make-up policy not found."));
	}

	MakeupPolicyResponse create(MakeupPolicyRequest request) {
		UUID id = jdbc.sql("""
				INSERT INTO makeup_policy (name, validity_days, max_per_pass, grant_on_in_time_cancel,
				                           grant_on_studio_cancel, expiry_checked_against)
				VALUES (:name, :days, :max, :inTime, :studio, :expiry)
				RETURNING id
				""")
			.params(terms(request))
			.query(UUID.class)
			.single();
		return get(id);
	}

	MakeupPolicyResponse update(UUID id, MakeupPolicyRequest request) {
		var params = terms(request);
		params.put("id", id);
		int updated = jdbc.sql("""
				UPDATE makeup_policy SET name = :name, validity_days = :days, max_per_pass = :max,
				       grant_on_in_time_cancel = :inTime, grant_on_studio_cancel = :studio,
				       expiry_checked_against = :expiry
				WHERE id = :id
				""")
			.params(params)
			.update();
		if (updated == 0) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Make-up policy not found.");
		}
		return get(id);
	}

	/** Products using an inactive policy stop granting new credits; credits already granted stay valid. */
	MakeupPolicyResponse setActive(UUID id, boolean active) {
		int updated = jdbc.sql("UPDATE makeup_policy SET is_active = :active WHERE id = :id")
			.param("id", id)
			.param("active", active)
			.update();
		if (updated == 0) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Make-up policy not found.");
		}
		return get(id);
	}

	private static Map<String, Object> terms(MakeupPolicyRequest request) {
		var params = new HashMap<String, Object>();
		params.put("name", request.name().trim());
		params.put("days", request.validityDays());
		params.put("max", request.maxPerPass());
		params.put("inTime", request.grantOnInTimeCancel());
		params.put("studio", request.grantOnStudioCancel());
		params.put("expiry", request.expiryCheckedAgainst().name());
		return params;
	}

	private static MakeupPolicyResponse map(ResultSet row, int number) throws SQLException {
		return new MakeupPolicyResponse(row.getObject("id", UUID.class), row.getString("name"),
				row.getInt("validity_days"), row.getObject("max_per_pass", Integer.class),
				row.getBoolean("grant_on_in_time_cancel"), row.getBoolean("grant_on_studio_cancel"),
				MakeupExpiryCheck.valueOf(row.getString("expiry_checked_against")), row.getBoolean("is_active"),
				row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getLong("product_count"));
	}

}
