package studioforyou.pl.backend.staff.internal;

/** Why a staff member is away. */
enum TimeOffKind {

	/** Planned leave. */
	VACATION,

	/** Illness, with or without a doctor's note. */
	SICK_LEAVE,

	/** Any other time the staff member cannot work, e.g. a personal matter. */
	UNAVAILABLE

}
