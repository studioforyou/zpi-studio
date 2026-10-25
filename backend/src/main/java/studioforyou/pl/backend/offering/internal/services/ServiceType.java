package studioforyou.pl.backend.offering.internal.services;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** A service definition and its participation rules. Deactivated definitions remain available to existing references. */
@Entity
@Table(name = "service_type")
public class ServiceType {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String name;

	private String description;

	private String category;

	private String level;

	@Column(name = "min_age")
	private Short minAge;

	@Column(name = "max_age")
	private Short maxAge;

	@Column(name = "default_duration_minutes")
	private Short defaultDurationMinutes;

	@Column(name = "min_participants")
	private Short minParticipants;

	@Column(name = "max_participants")
	private Short maxParticipants;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "calendar_color", length = 7)
	private String calendarColor;

	@Column(name = "is_active")
	private boolean active = true;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private Instant updatedAt;

	protected ServiceType() {
	}

	public UUID getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return this.description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getCategory() {
		return this.category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public String getLevel() {
		return this.level;
	}

	public void setLevel(String level) {
		this.level = level;
	}

	public Short getMinAge() {
		return this.minAge;
	}

	public void setMinAge(Short minAge) {
		this.minAge = minAge;
	}

	public Short getMaxAge() {
		return this.maxAge;
	}

	public void setMaxAge(Short maxAge) {
		this.maxAge = maxAge;
	}

	public Short getDefaultDurationMinutes() {
		return this.defaultDurationMinutes;
	}

	public void setDefaultDurationMinutes(Short defaultDurationMinutes) {
		this.defaultDurationMinutes = defaultDurationMinutes;
	}

	public Short getMinParticipants() {
		return this.minParticipants;
	}

	public void setMinParticipants(Short minParticipants) {
		this.minParticipants = minParticipants;
	}

	public Short getMaxParticipants() {
		return this.maxParticipants;
	}

	public void setMaxParticipants(Short maxParticipants) {
		this.maxParticipants = maxParticipants;
	}

	public String getCalendarColor() {
		return this.calendarColor;
	}

	public void setCalendarColor(String calendarColor) {
		this.calendarColor = calendarColor;
	}

	public boolean isActive() {
		return this.active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

}
