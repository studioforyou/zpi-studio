package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A recurrence rule in the studio's local time, e.g. "every Tuesday at 18:00 for 60 minutes".
 * One series has one weekday; a class on several weekdays is several series. The series keeps
 * the lead staff member and the main room as defaults; the occurrences it generated keep their
 * own assignments.
 */
@Entity
@Table(name = "recurrence_series")
class RecurrenceSeries {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "service_type_id", updatable = false)
	private UUID serviceTypeId;

	private String title;

	/** ISO weekday, 1 = Monday. */
	private short weekday;

	@Column(name = "local_start_time")
	private LocalTime localStartTime;

	@Column(name = "duration_minutes")
	private short durationMinutes;

	@Column(name = "interval_weeks")
	private short intervalWeeks;

	@Column(name = "starts_on")
	private LocalDate startsOn;

	@Column(name = "ends_on")
	private LocalDate endsOn;

	@Column(name = "default_resource_id")
	private UUID defaultResourceId;

	@Column(name = "default_staff_id")
	private UUID defaultStaffId;

	private Short capacity;

	@Enumerated(EnumType.STRING)
	private SeriesStatus status;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	protected RecurrenceSeries() {
	}

	RecurrenceSeries(UUID serviceTypeId, String title, int weekday, LocalTime localStartTime, int durationMinutes,
			int intervalWeeks, LocalDate startsOn, LocalDate endsOn, Short capacity, UUID createdBy) {
		this.serviceTypeId = serviceTypeId;
		this.title = title;
		this.weekday = (short) weekday;
		this.localStartTime = localStartTime;
		this.durationMinutes = (short) durationMinutes;
		this.intervalWeeks = (short) intervalWeeks;
		this.startsOn = startsOn;
		this.endsOn = endsOn;
		this.capacity = capacity;
		this.status = SeriesStatus.ACTIVE;
		this.createdBy = createdBy;
		this.updatedAt = Instant.now();
	}

	/**
	 * @param staffId the lead staff member of new occurrences, or {@code null}
	 * @param resourceId the main room of new occurrences, or {@code null}
	 */
	void setDefaults(UUID staffId, UUID resourceId) {
		this.defaultStaffId = staffId;
		this.defaultResourceId = resourceId;
		this.updatedAt = Instant.now();
	}

	UUID getId() {
		return this.id;
	}

	UUID getServiceTypeId() {
		return this.serviceTypeId;
	}

	String getTitle() {
		return this.title;
	}

	int getWeekday() {
		return this.weekday;
	}

	LocalTime getLocalStartTime() {
		return this.localStartTime;
	}

	int getDurationMinutes() {
		return this.durationMinutes;
	}

	int getIntervalWeeks() {
		return this.intervalWeeks;
	}

	LocalDate getStartsOn() {
		return this.startsOn;
	}

	LocalDate getEndsOn() {
		return this.endsOn;
	}

	UUID getDefaultResourceId() {
		return this.defaultResourceId;
	}

	UUID getDefaultStaffId() {
		return this.defaultStaffId;
	}

	Short getCapacity() {
		return this.capacity;
	}

	SeriesStatus getStatus() {
		return this.status;
	}

}
