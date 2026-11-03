package studioforyou.pl.backend.membership.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A period in which a pass cannot be used. Both days are inclusive. The pass's last valid day
 * moves by the length of the period, so the participant does not lose those days.
 */
@Entity
@Table(name = "pass_suspension")
public class PassSuspension {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "pass_id", updatable = false)
	private UUID passId;

	@Column(name = "starts_on", updatable = false)
	private LocalDate startsOn;

	@Column(name = "ends_on")
	private LocalDate endsOn;

	private String reason;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	protected PassSuspension() {
	}

	PassSuspension(UUID passId, LocalDate startsOn, LocalDate endsOn, String reason, UUID createdBy) {
		this.passId = passId;
		this.startsOn = startsOn;
		this.endsOn = endsOn;
		this.reason = reason;
		this.createdBy = createdBy;
	}

	/** Number of suspended days, counting both ends. */
	static long days(LocalDate startsOn, LocalDate endsOn) {
		return ChronoUnit.DAYS.between(startsOn, endsOn) + 1;
	}

	long days() {
		return days(this.startsOn, this.endsOn);
	}

	/**
	 * Cancels the days from {@code today} on. A suspension that has not started yet is
	 * cancelled as a whole; one in progress ends yesterday.
	 *
	 * @return the number of days taken off the suspension
	 */
	long cancelFrom(LocalDate today) {
		long before = days();
		if (today.isAfter(this.startsOn)) {
			this.endsOn = today.minusDays(1);
			return before - days();
		}
		this.cancelledAt = Instant.now();
		return before;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getPassId() {
		return this.passId;
	}

	public LocalDate getStartsOn() {
		return this.startsOn;
	}

	/** The last suspended day, inclusive. */
	public LocalDate getEndsOn() {
		return this.endsOn;
	}

	public String getReason() {
		return this.reason;
	}

	public UUID getCreatedBy() {
		return this.createdBy;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	/** When the suspension was cancelled before it started, or {@code null}. */
	public Instant getCancelledAt() {
		return this.cancelledAt;
	}

	boolean includes(LocalDate day) {
		return this.cancelledAt == null && !day.isBefore(this.startsOn) && !day.isAfter(this.endsOn);
	}

}
