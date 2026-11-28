package studioforyou.pl.backend.reporting.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.identity.AuthenticatedAccount;

/**
 * Only the three permanent settlement tables are written here. Revisions and ledger entries are
 * append-only.
 */
@Repository
class TrainerSettlementStore {

	private final JdbcClient jdbc;
	private final ObjectMapper json;

	TrainerSettlementStore(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	record Header(UUID id, UUID staffId, LocalDate month, String currency, long version) {}

	record Revision(
			UUID id,
			UUID settlementId,
			int revision,
			BigDecimal amount,
			boolean includeUnconfirmed,
			TrainerSettlements.Snapshot snapshot,
			@Nullable String sourceFingerprint,
			String reason,
			UUID actorId,
			String actor,
			Instant createdAt) {}

	record Payment(
			UUID id,
			UUID settlementId,
			BigDecimal amount,
			LocalDate paidOn,
			String method,
			String note,
			@Nullable UUID reversesId,
			UUID actorId,
			String actor,
			Instant createdAt) {}

	Optional<Header> header(UUID id, boolean lock) {
		return jdbc.sql(
						"SELECT * FROM trainer_settlement WHERE id=:id"
								+ (lock ? " FOR UPDATE" : ""))
				.param("id", id)
				.query(Header.class)
				.optional();
	}

	List<Header> headers(LocalDate month) {
		return jdbc.sql("SELECT * FROM trainer_settlement WHERE month=:month ORDER BY staff_id")
				.param("month", month)
				.query(Header.class)
				.list();
	}

	boolean create(UUID id, UUID staffId, LocalDate month, String currency) {
		return jdbc.sql(
								"""
INSERT INTO trainer_settlement(id,staff_id,month,currency) VALUES (:id,:staff,:month,:currency)
ON CONFLICT DO NOTHING
""")
						.param("id", id)
						.param("staff", staffId)
						.param("month", month)
						.param("currency", currency)
						.update()
				== 1;
	}

	void advance(UUID id) {
		jdbc.sql("UPDATE trainer_settlement SET version=version+1 WHERE id=:id")
				.param("id", id)
				.update();
	}

	List<Revision> revisions(UUID id) {
		return jdbc.sql(
						"SELECT * FROM trainer_settlement_revision WHERE settlement_id=:id ORDER BY"
							+ " revision DESC")
				.param("id", id)
				.query((rs, n) -> revision(rs))
				.list();
	}

	Optional<Revision> revision(UUID operation) {
		return jdbc.sql("SELECT * FROM trainer_settlement_revision WHERE id=:id")
				.param("id", operation)
				.query((rs, n) -> revision(rs))
				.optional();
	}

	private Revision revision(ResultSet rs) throws SQLException {
		return new Revision(
				rs.getObject("id", UUID.class),
				rs.getObject("settlement_id", UUID.class),
				rs.getInt("revision"),
				rs.getBigDecimal("amount"),
				rs.getBoolean("include_unconfirmed"),
				json.readValue(rs.getString("snapshot"), TrainerSettlements.Snapshot.class),
				rs.getString("source_fingerprint"),
				rs.getString("reason"),
				rs.getObject("actor_id", UUID.class),
				rs.getString("actor_label"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	void appendRevision(
			UUID operation,
			UUID id,
			int number,
			BigDecimal amount,
			boolean include,
			TrainerSettlements.Snapshot snapshot,
			@Nullable String sourceFingerprint,
			String reason,
			AuthenticatedAccount actor) {
		jdbc.sql(
						"""
INSERT INTO trainer_settlement_revision(id,settlement_id,revision,amount,include_unconfirmed,snapshot,source_fingerprint,reason,actor_id,actor_label)
VALUES (:op,:id,:number,:amount,:include,CAST(:snapshot AS JSONB),:fingerprint,:reason,:actor,:label)
""")
				.param("op", operation)
				.param("id", id)
				.param("number", number)
				.param("amount", amount)
				.param("include", include)
				.param("snapshot", json.writeValueAsString(snapshot))
				.param("fingerprint", sourceFingerprint, java.sql.Types.VARCHAR)
				.param("reason", reason)
				.param("actor", actor.id())
				.param("label", actor.email())
				.update();
	}

	List<Payment> payments(UUID id) {
		return jdbc.sql(
						"SELECT * FROM trainer_payout WHERE settlement_id=:id ORDER BY created_at"
							+ " DESC,id")
				.param("id", id)
				.query((rs, n) -> payment(rs))
				.list();
	}

	Optional<Payment> payment(UUID operation) {
		return jdbc.sql("SELECT * FROM trainer_payout WHERE id=:id")
				.param("id", operation)
				.query((rs, n) -> payment(rs))
				.optional();
	}

	private Payment payment(ResultSet rs) throws SQLException {
		return new Payment(
				rs.getObject("id", UUID.class),
				rs.getObject("settlement_id", UUID.class),
				rs.getBigDecimal("amount"),
				rs.getObject("paid_on", LocalDate.class),
				rs.getString("method"),
				rs.getString("note"),
				rs.getObject("reverses_id", UUID.class),
				rs.getObject("actor_id", UUID.class),
				rs.getString("actor_label"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	void appendPayment(
			UUID operation,
			UUID id,
			BigDecimal amount,
			LocalDate date,
			String method,
			String note,
			@Nullable UUID reversesId,
			AuthenticatedAccount actor) {
		jdbc.sql(
						"""
INSERT INTO trainer_payout(id,settlement_id,amount,paid_on,method,note,reverses_id,actor_id,actor_label)
VALUES (:op,:id,:amount,:date,:method,:note,:reverses,:actor,:label)
""")
				.param("op", operation)
				.param("id", id)
				.param("amount", amount)
				.param("date", date)
				.param("method", method)
				.param("note", note)
				.param("reverses", reversesId, java.sql.Types.OTHER)
				.param("actor", actor.id())
				.param("label", actor.email())
				.update();
	}
}
