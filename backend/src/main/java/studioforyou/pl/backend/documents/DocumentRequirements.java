package studioforyou.pl.backend.documents;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Booking requirements only; document publication and acceptance remain separate use cases. */
@Repository
public class DocumentRequirements {

	private final JdbcClient jdbc;

	public DocumentRequirements(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Unpublished drafts and voluntary consents never block a booking. */
	public boolean hasMissing(UUID profileId, UUID serviceTypeId, String participantKind) {
		return jdbc.sql("""
				SELECT EXISTS (
				 SELECT 1 FROM document d
				 JOIN LATERAL (
				   SELECT v.version_no FROM document_version v
				   WHERE v.document_id = d.id AND v.published_at <= now()
				   ORDER BY v.version_no DESC LIMIT 1
				 ) latest ON true
				 WHERE d.is_active AND d.is_required AND d.blocks_booking
				 AND d.audience IN ('ALL', :kind)
				 AND (NOT EXISTS (SELECT 1 FROM document_service_requirement r WHERE r.document_id = d.id)
				      OR EXISTS (SELECT 1 FROM document_service_requirement r
				                 WHERE r.document_id = d.id AND r.service_type_id = :serviceId))
				 AND NOT EXISTS (
				   SELECT 1 FROM document_acceptance a
				   JOIN document_version accepted ON accepted.id = a.document_version_id
				   WHERE accepted.document_id = d.id AND a.customer_id = :profileId AND a.revoked_at IS NULL
				   AND accepted.published_at <= now()
				   AND NOT EXISTS (
				     SELECT 1 FROM document_version newer WHERE newer.document_id = d.id
				     AND newer.version_no > accepted.version_no AND newer.version_no <= latest.version_no
				     AND newer.published_at <= now() AND newer.requires_reacceptance
				   )
				 )
				)
				""").param("profileId", profileId).param("serviceId", serviceTypeId).param("kind", participantKind)
			.query(Boolean.class).single();
	}

}
