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

	/** Time when a room is blocked, e.g. for maintenance. */
	BLOCK;

	/** Kinds an administrator plans in the schedule, as one-off events or recurring series. */
	static final Set<EventKind> PLANNABLE = EnumSet.of(CLASS, PERSONAL_TRAINING, WORKSHOP, BLOCK);

}
