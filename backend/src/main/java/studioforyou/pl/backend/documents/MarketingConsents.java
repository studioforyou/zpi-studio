package studioforyou.pl.backend.documents;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who has given a voluntary consent, for modules that may only reach people who agreed, e.g. a
 * marketing announcement.
 */
@Repository
public class MarketingConsents {

	private final JdbcClient jdbc;

	public MarketingConsents(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The participants among the given ones who accepted a published version of an active document
	 * of the kind and have not withdrawn it.
	 */
	public Set<UUID> consenting(Collection<UUID> profileIds, DocumentKind kind) {
		if (profileIds.isEmpty()) {
			return Set.of();
		}
		return jdbc.sql("""
				SELECT DISTINCT a.customer_id
				FROM document_acceptance a
				JOIN document_version v ON v.id = a.document_version_id
				JOIN document d ON d.id = v.document_id
				WHERE a.customer_id IN (:ids) AND a.revoked_at IS NULL
				  AND d.kind = :kind AND d.is_active AND v.published_at <= now()
				""")
			.param("ids", profileIds)
			.param("kind", kind.name())
			.query(UUID.class)
			.set();
	}

}
