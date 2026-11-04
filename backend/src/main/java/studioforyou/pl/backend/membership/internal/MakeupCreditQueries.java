package studioforyou.pl.backend.membership.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;

/** Reads only membership-owned tables, after the profile owner authorizes access. */
@Service
class MakeupCreditQueries {

	private static final String AVAILABLE = """
			FROM makeup_credit credit
			JOIN customer_pass pass ON pass.id = credit.source_pass_id
			WHERE credit.customer_id = :profileId AND pass.customer_id = :profileId
			  AND credit.status = 'AVAILABLE' AND credit.expires_at > CURRENT_TIMESTAMP
			  AND credit.used_at IS NULL AND credit.used_reservation_id IS NULL
			""";

	private final JdbcClient jdbc;

	private final CustomerProfiles profiles;

	MakeupCreditQueries(JdbcClient jdbc, CustomerProfiles profiles) {
		this.jdbc = jdbc;
		this.profiles = profiles;
	}

	/** Count and page use the same database snapshot and transaction timestamp. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<OwnMakeupCreditResponse> list(UUID accountId, UUID profileId, int page, int size) {
		if (!profiles.isActingFor(accountId, profileId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		long total = jdbc.sql("SELECT count(*) " + AVAILABLE).param("profileId", profileId)
			.query(Long.class).single();
		var items = jdbc.sql("""
				SELECT credit.id, credit.source_pass_id, pass.product_name, credit.granted_at, credit.expires_at
				""" + AVAILABLE + " ORDER BY credit.expires_at, credit.id LIMIT :limit OFFSET :offset")
			.param("profileId", profileId).param("limit", size).param("offset", (long) page * size)
			.query((row, number) -> new OwnMakeupCreditResponse(row.getObject("id", UUID.class),
					row.getObject("source_pass_id", UUID.class), row.getString("product_name"),
					row.getTimestamp("granted_at").toInstant(), row.getTimestamp("expires_at").toInstant()))
			.list();
		return PageResponse.of(items, page, size, total);
	}

}
