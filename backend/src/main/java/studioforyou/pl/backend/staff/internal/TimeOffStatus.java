package studioforyou.pl.backend.staff.internal;

/**
 * Where a time-off request stands. Requested and approved time off is shown in the schedule;
 * rejected and cancelled requests are kept only as history.
 */
enum TimeOffStatus {

	REQUESTED,
	APPROVED,
	REJECTED,
	CANCELLED;

	boolean isActive() {
		return this == REQUESTED || this == APPROVED;
	}

}
