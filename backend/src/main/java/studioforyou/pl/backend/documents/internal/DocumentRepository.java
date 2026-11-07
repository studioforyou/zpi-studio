package studioforyou.pl.backend.documents.internal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.documents.DocumentAcceptanceStatus;
import studioforyou.pl.backend.documents.DocumentKind;

/**
 * Documents in force for a participant and the participant's acceptances of them.
 * <p>
 * The version in force is the latest published one. A document is listed only when it is
 * active, has a version in force and is meant for the participant's kind. Health
 * questionnaires are filled in, not accepted, so they are left out.
 */
@Repository
public class DocumentRepository {

	private static final String STATES = """
			SELECT d.id, d.title, d.kind, d.is_required AS required, d.blocks_booking,
			  v.id AS version_id, v.version_no, v.published_at,
			  v.content IS NOT NULL AS has_content, v.file_id IS NOT NULL AS has_file,
			  acc.version_no AS accepted_version_no, acc.accepted_at,
			  EXISTS (
			    SELECT 1 FROM document_version later
			    WHERE later.document_id = d.id AND later.published_at <= now()
			      AND later.version_no > acc.version_no AND later.requires_reacceptance
			  ) AS reacceptance_required
			FROM document d
			JOIN LATERAL (
			  SELECT id, version_no, published_at, content, file_id
			  FROM document_version
			  WHERE document_id = d.id AND published_at <= now()
			  ORDER BY version_no DESC
			  LIMIT 1
			) v ON TRUE
			LEFT JOIN LATERAL (
			  SELECT av.version_no, a.accepted_at
			  FROM document_acceptance a
			  JOIN document_version av ON av.id = a.document_version_id
			  WHERE av.document_id = d.id AND a.customer_id = :customerId AND a.revoked_at IS NULL
			  ORDER BY av.version_no DESC
			  LIMIT 1
			) acc ON TRUE
			WHERE d.is_active AND d.kind <> 'HEALTH_QUESTIONNAIRE' AND d.audience IN ('ALL', :audience)
			""";

	private final JdbcClient jdbc;

	public DocumentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Documents in force for the participant: required ones first, then by title. */
	public List<DocumentState> findStates(UUID customerId, CustomerKind customerKind) {
		List<StateRow> rows = jdbc.sql(STATES + " ORDER BY d.is_required DESC, d.title, d.id")
			.param("customerId", customerId)
			.param("audience", customerKind.name())
			.query(StateRow.class)
			.list();
		return withServices(rows);
	}

	/** One document in force for the participant. */
	public Optional<DocumentState> findState(UUID customerId, CustomerKind customerKind, UUID documentId) {
		List<StateRow> rows = jdbc.sql(STATES + " AND d.id = :documentId")
			.param("customerId", customerId)
			.param("audience", customerKind.name())
			.param("documentId", documentId)
			.query(StateRow.class)
			.list();
		return withServices(rows).stream().findFirst();
	}

	/** The text of a version, or {@code null} when its content is a file. */
	public String findContent(UUID versionId) {
		return jdbc.sql("SELECT content FROM document_version WHERE id = :id")
			.param("id", versionId)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	/**
	 * Records the participant's acceptance of a version, made in the app by the given account.
	 * @throws org.springframework.dao.DuplicateKeyException when the version is already accepted
	 */
	public void insertAcceptance(UUID versionId, UUID customerId, UUID acceptedBy) {
		jdbc.sql("""
				INSERT INTO document_acceptance (document_version_id, customer_id, accepted_by, source)
				VALUES (:versionId, :customerId, :acceptedBy, 'APP')
				""")
			.param("versionId", versionId)
			.param("customerId", customerId)
			.param("acceptedBy", acceptedBy)
			.update();
	}

	/** Withdraws every active acceptance of any version of the document; returns how many. */
	public int revokeAcceptances(UUID documentId, UUID customerId) {
		return jdbc.sql("""
				UPDATE document_acceptance a
				SET revoked_at = now()
				FROM document_version v
				WHERE v.id = a.document_version_id AND v.document_id = :documentId
				  AND a.customer_id = :customerId AND a.revoked_at IS NULL
				""")
			.param("documentId", documentId)
			.param("customerId", customerId)
			.update();
	}

	private List<DocumentState> withServices(List<StateRow> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<UUID>> services = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT document_id, service_type_id
				FROM document_service_requirement
				WHERE document_id IN (:ids)
				ORDER BY service_type_id
				""")
			.param("ids", rows.stream().map(StateRow::id).toList())
			.query((rs, rowNum) -> {
				services.computeIfAbsent(rs.getObject("document_id", UUID.class), (id) -> new ArrayList<>())
					.add(rs.getObject("service_type_id", UUID.class));
				return null;
			})
			.list();
		return rows.stream().map((row) -> row.toState(services.getOrDefault(row.id(), List.of()))).toList();
	}

	record StateRow(UUID id, String title, DocumentKind kind, boolean required, boolean blocksBooking,
			UUID versionId, int versionNo, OffsetDateTime publishedAt, boolean hasContent, boolean hasFile,
			Integer acceptedVersionNo, OffsetDateTime acceptedAt, boolean reacceptanceRequired) {

		DocumentState toState(List<UUID> requiredForServiceIds) {
			return new DocumentState(id, title, kind, required, blocksBooking, versionId, versionNo, publishedAt,
					hasContent, hasFile, acceptedVersionNo, acceptedAt, reacceptanceRequired,
					List.copyOf(requiredForServiceIds));
		}

	}

	/**
	 * A document in force for a participant.
	 * @param required whether the document has to be accepted; {@code false} for optional consents
	 * @param versionId the version in force
	 * @param acceptedVersionNo the latest version the participant accepted and did not withdraw, or {@code null}
	 * @param acceptedAt when that version was accepted, or {@code null}
	 * @param reacceptanceRequired whether a version published after the accepted one has to be accepted again
	 * @param requiredForServiceIds the services the document is required for; empty when required for all
	 */
	public record DocumentState(UUID id, String title, DocumentKind kind, boolean required, boolean blocksBooking,
			UUID versionId, int versionNo, OffsetDateTime publishedAt, boolean hasContent, boolean hasFile,
			Integer acceptedVersionNo, OffsetDateTime acceptedAt, boolean reacceptanceRequired,
			List<UUID> requiredForServiceIds) {

		public DocumentAcceptanceStatus status() {
			if (acceptedVersionNo == null) {
				return DocumentAcceptanceStatus.NOT_ACCEPTED;
			}
			if (acceptedVersionNo == versionNo || !reacceptanceRequired) {
				return DocumentAcceptanceStatus.ACCEPTED;
			}
			return DocumentAcceptanceStatus.REACCEPTANCE_REQUIRED;
		}

	}

}
