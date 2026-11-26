package studioforyou.pl.backend.communication.announcement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.communication.announcement.AnnouncementTemplateDtos.AnnouncementTemplate;

/** Announcement templates ({@code announcement_template}). */
@Repository
class AnnouncementTemplateStore {

	private static final String SELECT = "SELECT id, name, title, body, is_marketing, updated_at FROM announcement_template ";

	private final JdbcClient jdbc;

	AnnouncementTemplateStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Every template, by name. */
	List<AnnouncementTemplate> list() {
		return jdbc.sql(SELECT + "ORDER BY lower(name), id").query(AnnouncementTemplateStore::template).list();
	}

	Optional<AnnouncementTemplate> find(UUID id) {
		return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(AnnouncementTemplateStore::template).optional();
	}

	/** Whether another template than {@code exceptId} has the name, regardless of letter case. */
	boolean nameUsed(String name, @Nullable UUID exceptId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM announcement_template
				               WHERE lower(name) = lower(:name) AND (CAST(:exceptId AS UUID) IS NULL OR id <> :exceptId))
				""")
			.param("name", name)
			.param("exceptId", exceptId, java.sql.Types.OTHER)
			.query(Boolean.class)
			.single();
	}

	UUID insert(String name, String title, String body, boolean marketing, UUID createdBy) {
		return jdbc.sql("""
				INSERT INTO announcement_template (name, title, body, is_marketing, created_by, updated_by)
				VALUES (:name, :title, :body, :marketing, :createdBy, :createdBy)
				RETURNING id
				""")
			.param("name", name)
			.param("title", title)
			.param("body", body)
			.param("marketing", marketing)
			.param("createdBy", createdBy)
			.query(UUID.class)
			.single();
	}

	void update(UUID id, String name, String title, String body, boolean marketing, UUID updatedBy) {
		jdbc.sql("""
				UPDATE announcement_template
				SET name = :name, title = :title, body = :body, is_marketing = :marketing,
				    updated_by = :updatedBy, updated_at = now()
				WHERE id = :id
				""")
			.param("id", id)
			.param("name", name)
			.param("title", title)
			.param("body", body)
			.param("marketing", marketing)
			.param("updatedBy", updatedBy)
			.update();
	}

	/** Removes the template; returns whether it existed. */
	boolean delete(UUID id) {
		return jdbc.sql("DELETE FROM announcement_template WHERE id = :id").param("id", id).update() > 0;
	}

	private static AnnouncementTemplate template(ResultSet rs, int row) throws SQLException {
		return new AnnouncementTemplate(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("title"),
				rs.getString("body"), rs.getBoolean("is_marketing"),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

}
