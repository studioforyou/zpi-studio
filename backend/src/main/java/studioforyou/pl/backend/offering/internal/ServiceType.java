package studioforyou.pl.backend.offering.internal;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read-only view of a service type. Managing service types is a separate feature.
 */
@Entity
@Immutable
@Table(name = "service_type")
public class ServiceType {

	@Id
	private UUID id;

	private String name;

	private String category;

	private String level;

	@Column(name = "min_age")
	private Short minAge;

	@Column(name = "max_age")
	private Short maxAge;

	@Column(name = "default_duration_minutes")
	private short defaultDurationMinutes;

	@Column(name = "max_participants")
	private Short maxParticipants;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "calendar_color", length = 7)
	private String calendarColor;

	@Column(name = "is_active")
	private boolean active;

	protected ServiceType() {
	}

	public UUID getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public String getCategory() {
		return this.category;
	}

	public String getLevel() {
		return this.level;
	}

	/** The youngest allowed participant's age, or {@code null} for no lower limit. */
	public Short getMinAge() {
		return this.minAge;
	}

	/** The oldest allowed participant's age, or {@code null} for no upper limit. */
	public Short getMaxAge() {
		return this.maxAge;
	}

	public short getDefaultDurationMinutes() {
		return this.defaultDurationMinutes;
	}

	/** The usual maximum number of participants, or {@code null} for no limit. */
	public Short getMaxParticipants() {
		return this.maxParticipants;
	}

	/** Colour of the service in calendars, {@code #RRGGBB}, or {@code null}. */
	public String getCalendarColor() {
		return this.calendarColor;
	}

	public boolean isActive() {
		return this.active;
	}

}
