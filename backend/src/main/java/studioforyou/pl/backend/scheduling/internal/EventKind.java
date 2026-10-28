package studioforyou.pl.backend.scheduling.internal;

import java.util.EnumSet;
import java.util.Set;

/**
 * Kind of a calendar event ({@code schedule_event.kind}). All kinds share one time model.
 */
enum EventKind {

	/** A group class, usually part of a recurring series. */
	CLASS,

	PERSONAL_TRAINING,

	/** A one-off workshop. */
	WORKSHOP,

	/** A consultation, possibly with a guest without an account. */
	CONSULTATION,

	/** Time when a room or a staff member is blocked, e.g. for maintenance. */
	BLOCK;

	/** Kinds an administrator plans as a part of the studio's offer. */
	static final Set<EventKind> PLANNABLE = EnumSet.of(CLASS, PERSONAL_TRAINING, WORKSHOP);

}
