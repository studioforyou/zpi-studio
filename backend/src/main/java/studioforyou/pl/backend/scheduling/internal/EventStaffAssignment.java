package studioforyou.pl.backend.scheduling.internal;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * A staff member assigned to an event ({@code event_staff}). The staff member belongs to the
 * staff module and is referenced by id only.
 */
@Embeddable
record EventStaffAssignment(@Column(name = "staff_id") UUID staffId,
		@Enumerated(EnumType.STRING) @Column(name = "role") StaffRole role) {
}
